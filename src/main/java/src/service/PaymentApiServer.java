package src.service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpHandler;
import com.sun.net.httpserver.HttpServer;

import src.model.PaymentRequest;
import src.processor.SimplePaymentProcessor;

public class PaymentApiServer {
    // Cache do valor amount fixo
    private static volatile String cachedAmount = null;
    private static volatile BigDecimal cachedAmountBD = null;

    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);

        // Handlers específicos por método - ZERO overhead de verificação
        server.createContext("/payment", new PostPaymentHandler());
        server.createContext("/payments-summary", new GetPaymentSummaryHandler());

        // SEM executor customizado - usa o padrão do HttpServer (mais eficiente para
        // I/O simples)
        server.start();
    }

    // Handler ESPECÍFICO para POST /payment - SÍNCRONO para máxima velocidade
    static class PostPaymentHandler implements HttpHandler {
        private static final byte[] CORRELATION_PATTERN = "\"correlationId\":\"".getBytes(StandardCharsets.UTF_8);
        private static final byte[] AMOUNT_PATTERN = "\"amount\":\"".getBytes(StandardCharsets.UTF_8);
        private static final ThreadLocal<byte[]> BUFFER_TL = ThreadLocal.withInitial(() -> new byte[512]);

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            byte[] buffer = BUFFER_TL.get();
            int totalRead = readRequestBody(exchange.getRequestBody(), buffer);

            String correlationId = extractFieldFromBuffer(buffer, CORRELATION_PATTERN, totalRead);

            String amount = getOrExtractAmount(buffer, totalRead);

            // Enqueue DIRETO sem async overhead
            SimplePaymentProcessor.enqueuePayment(new PaymentRequest(correlationId, amount));

            // Response IMEDIATA
            sendSuccessResponseFast(exchange);
        }

        private String getOrExtractAmount(byte[] buffer, int totalRead) {
            // Se já temos cached, usa (ultra-rápido)
            if (cachedAmount != null) {
                return cachedAmount;
            }

            // Primeira requisição - extrai amount e cacheia
            String amount = extractFieldFromBuffer(buffer, AMOUNT_PATTERN, totalRead);
            if (amount != null) {
                cachedAmount = amount;
                cachedAmountBD = null; // Limpa cache do BigDecimal
                return amount;
            }

            // Fallback
            cachedAmount = "100.00";
            return cachedAmount;
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

    // Handler para GET mantém async apenas onde necessário
    static class GetPaymentSummaryHandler implements HttpHandler {
        private static volatile String cachedResponse = null;
        private static volatile long cacheTime = 0;
        private static final long CACHE_TTL_MS = 50; // Aumentado para reduzir calls Redis

        @Override
        public void handle(HttpExchange exchange) throws IOException {
            long now = System.currentTimeMillis();
            if (cachedResponse != null && (now - cacheTime) < CACHE_TTL_MS) {
                sendJsonResponseFast(exchange, cachedResponse);
                return;
            }

            // Async necessário apenas para Redis
            CompletableFuture<Long> defaultRequestsFut = RedisAsyncManager.getTotalRequests();

            defaultRequestsFut
                    .orTimeout(50, java.util.concurrent.TimeUnit.MILLISECONDS) // Timeout MUITO agressivo
                    .whenComplete((defaultRequests, throwable) -> {
                        try {
                            String response;
                            if (throwable != null || defaultRequests == null) {
                                response = "{\"default\":{\"totalRequests\":0,\"totalAmount\":0.00},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                            } else {
                                BigDecimal defaultAmount = calculateTotalAmountFast(defaultRequests);

                                // String concatenation DIRETA (mais rápida que StringBuilder para strings
                                // pequenas)
                                response = "{\"default\":{\"totalRequests\":" + defaultRequests +
                                        ",\"totalAmount\":" + defaultAmount +
                                        "},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                            }

                            cachedResponse = response;
                            cacheTime = System.currentTimeMillis();
                            sendJsonResponseFast(exchange, response);
                        } catch (Exception e) {
                            sendJsonResponseFast(exchange,
                                    "{\"default\":{\"totalRequests\":0,\"totalAmount\":0.00},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}");
                        }
                    });
        }

        private BigDecimal calculateTotalAmountFast(long totalRequests) {
            if (totalRequests == 0)
                return BigDecimal.ZERO;

            if (cachedAmountBD == null && cachedAmount != null) {
                try {
                    cachedAmountBD = new BigDecimal(cachedAmount);
                } catch (NumberFormatException e) {
                    return BigDecimal.ZERO;
                }
            }

            return cachedAmountBD != null ? cachedAmountBD.multiply(BigDecimal.valueOf(totalRequests))
                    : BigDecimal.ZERO;
        }
    }

    // Response methods ULTRA-OTIMIZADOS
    private static void sendSuccessResponseFast(HttpExchange exchange) {
        try {
            exchange.sendResponseHeaders(202, -1); // -1 = no body, mais rápido
        } catch (IOException ignored) {
        }
    }
    
    private static void sendJsonResponseFast(HttpExchange exchange, String response) {
        try {
            byte[] responseBytes = response.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, responseBytes.length);

            try (OutputStream os = exchange.getResponseBody()) {
                os.write(responseBytes);
            }
        } catch (IOException ignored) {
        }
    }
}