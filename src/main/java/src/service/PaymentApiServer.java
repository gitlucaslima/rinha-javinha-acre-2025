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
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                byte[] body = exchange.getRequestBody().readAllBytes();

                String correlationId = extractJsonFieldFromBytes(body, "correlationId");
                String amount = extractJsonFieldFromBytes(body, "amount");

                if (correlationId.isEmpty() || amount.isEmpty()) {
                    sendErrorResponse(exchange, 400);
                    return;
                }

                SimplePaymentProcessor.enqueuePayment(new PaymentRequest(correlationId, amount));

                sendSuccessResponse(exchange, 202);
            } else {
                sendErrorResponse(exchange, 405);
            }
        }

        private String extractJsonFieldFromBytes(byte[] body, String field) {
            String fieldSearch = "\"" + field + "\":";
            byte[] searchBytes = fieldSearch.getBytes(StandardCharsets.UTF_8);

            int start = indexOf(body, searchBytes);
            if (start == -1)
                return "";

            start += searchBytes.length;
            while (start < body.length && (body[start] == ' ' || body[start] == '"'))
                start++;

            int end = start;
            // Encontra fim do valor
            while (end < body.length && body[end] != ',' && body[end] != '}' && body[end] != '"')
                end++;

            return new String(body, start, end - start, StandardCharsets.UTF_8);
        }

        private int indexOf(byte[] array, byte[] target) {
            for (int i = 0; i <= array.length - target.length; i++) {
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
                String query = exchange.getRequestURI().getQuery();

                long now = System.currentTimeMillis();
                if (cachedResponse != null && (now - cacheTime) < CACHE_TTL_MS) {
                    sendJsonResponse(exchange, cachedResponse);
                    return;
                }

                CompletableFuture<Long> defaultRequestsFut = RedisAsyncManager.getTotalRequests();
                CompletableFuture<BigDecimal> defaultAmountFut = RedisAsyncManager.getAmountAsBigDecimal();

                CompletableFuture.allOf(defaultRequestsFut, defaultAmountFut)
                        .orTimeout(100, java.util.concurrent.TimeUnit.MILLISECONDS) // Timeout 100ms
                        .whenComplete((v, throwable) -> {
                            try {
                                String response;
                                if (throwable != null) {
                                    response = "{\"default\":{\"totalRequests\":0,\"totalAmount\":0.00},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                                } else {
                                    long defaultRequests = defaultRequestsFut.join();
                                    BigDecimal defaultAmount = defaultAmountFut.join();

                                    response = "{\"default\":{\"totalRequests\":" + defaultRequests +
                                            ",\"totalAmount\":" + defaultAmount +
                                            "},\"fallback\":{\"totalRequests\":0,\"totalAmount\":0.00}}";
                                }

                                cachedResponse = response;
                                cacheTime = System.currentTimeMillis();

                                sendJsonResponse(exchange, response);
                            } catch (Exception e) {
                            }
                        });
            } else {
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

    private static void sendErrorResponse(HttpExchange exchange, int statusCode) {
        try {
            exchange.sendResponseHeaders(statusCode, -1);
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