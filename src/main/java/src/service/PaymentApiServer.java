package src.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadPoolExecutor;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import src.model.PaymentRequest;
import src.processor.SimplePaymentProcessor;

public class PaymentApiServer {
    // Cache do valor amount fixo
    private static volatile String cachedAmount = null;
    private static final Object amountLock = new Object();
    private static final ThreadPoolExecutor httpExecutor = (ThreadPoolExecutor) Executors.newFixedThreadPool(
            Math.max(8, Runtime.getRuntime().availableProcessors() * 4));

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/payment", new PaymentHandler());
        server.createContext("/payments-summary", new PaymentSummaryHandler());
        server.setExecutor(httpExecutor);
        server.start();
    }

    static class PaymentHandler implements HttpHandler {
        // Pattern matching otimizado para JSON conhecido
        private static final byte[] CORRELATION_PATTERN = "\"correlationId\":\"".getBytes(StandardCharsets.UTF_8);
        private static final byte[] AMOUNT_PATTERN = "\"amount\":\"".getBytes(StandardCharsets.UTF_8);
        private static final ThreadLocal<byte[]> BUFFER_TL = ThreadLocal.withInitial(() -> new byte[512]); // Buffer
                                                                                                           // maior

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] buffer = BUFFER_TL.get();
                int totalRead = readRequestBody(exchange.getRequestBody(), buffer);

                String correlationId = extractFieldFromBuffer(buffer, CORRELATION_PATTERN, totalRead);

                if (correlationId != null) {
                    // Extrai amount da primeira requisição, depois usa cache
                    String amount = getOrExtractAmount(buffer, totalRead);

                    SimplePaymentProcessor.enqueuePayment(new PaymentRequest(correlationId, amount));
                    sendSuccessResponse(exchange, 202);
                } else {
                    sendSuccessResponse(exchange, 400);
                }
            } else {
                sendSuccessResponse(exchange, 405);
            }
        }

        private String getOrExtractAmount(byte[] buffer, int totalRead) {
            // Se já temos cached, usa
            if (cachedAmount != null) {
                return cachedAmount;
            }

            // Primeira requisição - extrai amount e cacheia
            String amount = extractFieldFromBuffer(buffer, AMOUNT_PATTERN, totalRead);
            if (amount != null) {
                cachedAmount = amount; // Cacheia para próximas requisições
                return amount;
            }

            // Fallback se não conseguir extrair
            return "100.00";
        }

        private int readRequestBody(InputStream is, byte[] buffer) throws IOException {
            int totalRead = 0;
            int bytesRead;

            while (totalRead < buffer.length
                    && (bytesRead = is.read(buffer, totalRead, buffer.length - totalRead)) != -1) {
                totalRead += bytesRead;
            }

            return totalRead;
        }

        private String extractFieldFromBuffer(byte[] buffer, byte[] pattern, int totalRead) {
            int patternIndex = indexOf(buffer, pattern, totalRead);
            if (patternIndex != -1) {
                int start = patternIndex + pattern.length;
                int end = start;

                // Encontra fim do valor (até aspas)
                while (end < totalRead && buffer[end] != '"') {
                    end++;
                }

                if (end < totalRead) {
                    return new String(buffer, start, end - start, StandardCharsets.UTF_8);
                }
            }
            return null;
        }

        private int indexOf(byte[] array, byte[] target, int arrayLength) {
            for (int i = 0; i <= arrayLength - target.length; i++) {
                boolean found = true;
                for (int j = 0; j < target.length; j++) {
                    if (array[i + j] != target[j]) {
                        found = false;
                        break;
                    }
                }
                if (found)
                    return i;
            }
            return -1;
        }
    }

    static class PaymentSummaryHandler implements HttpHandler {
        private static volatile String cachedResponse = null;
        private static volatile long cacheTime = 0;
        private static final long CACHE_TTL_MS = 50;

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                long now = System.currentTimeMillis();
                if (cachedResponse != null && (now - cacheTime) < CACHE_TTL_MS) {
                    sendJsonResponse(exchange, cachedResponse);
                    return;
                }

                CompletableFuture<Long> defaultRequestsFut = RedisAsyncManager.getTotalRequests();

                defaultRequestsFut
                        .orTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS)
                        .whenComplete((defaultRequests, throwable) -> {
                            try {
                                String response;
                                if (throwable != null || defaultRequests == null) {
                                    response = "{\"default\":{\"totalRequests\":0,\"totalAmount\":0.00},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                                } else {
                                    // Calcula defaultAmount = cachedAmount * defaultRequests
                                    BigDecimal defaultAmount = calculateTotalAmount(defaultRequests);

                                    response = "{\"default\":{\"totalRequests\":" + defaultRequests +
                                            ",\"totalAmount\":" + defaultAmount +
                                            "},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                                }

                                cachedResponse = response;
                                cacheTime = System.currentTimeMillis();

                                sendJsonResponse(exchange, response);
                            } catch (Exception e) {
                                String fallbackResponse = "{\"default\":{\"totalRequests\":0,\"totalAmount\":0.00},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                                sendJsonResponse(exchange, fallbackResponse);
                            }
                        });
            }
        }

        private BigDecimal calculateTotalAmount(long totalRequests) {
            if (cachedAmount == null || totalRequests == 0) {
                return BigDecimal.ZERO;
            }

            try {
                BigDecimal amountPerRequest = new BigDecimal(cachedAmount);
                return amountPerRequest.multiply(BigDecimal.valueOf(totalRequests));
            } catch (NumberFormatException e) {
                return BigDecimal.ZERO;
            }
        }
    }

    private static void sendSuccessResponse(HttpExchange exchange, int statusCode) {
        try {
            exchange.sendResponseHeaders(statusCode, 0);
            exchange.getResponseBody().close();
        } catch (IOException e) {
            // Ignore
        }
    }

    private static void sendJsonResponse(HttpExchange exchange, String response) {
        try {
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);

            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException e) {
        }
    }
}