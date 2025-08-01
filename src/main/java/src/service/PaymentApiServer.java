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
    public static void start(int port) throws IOException {
        HttpServer server = HttpServer.create(new InetSocketAddress(port), 0);
        server.createContext("/payment", new PaymentHandler());
        server.createContext("/payments-summary", new PaymentSummaryHandler());
        server.setExecutor(null); // default executor
        server.start();
    }

    static class PaymentHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("POST".equalsIgnoreCase(exchange.getRequestMethod())) {
                InputStream is = exchange.getRequestBody();
                byte[] body = is.readAllBytes();
                String bodyStr = new String(body, StandardCharsets.UTF_8);

                String correlationId = extractJsonField(bodyStr, "correlationId");
                String amount = extractJsonField(bodyStr, "amount");
                
                SimplePaymentProcessor.enqueuePayment(new PaymentRequest(correlationId, amount));
                              
                exchange.sendResponseHeaders(202, 0);
                exchange.getResponseBody().close();
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }

        private String extractJsonField(String body, String field) {
            String search = "\"" + field + "\":";
            int i = body.indexOf(search);
            if (i == -1) return "";
            int start = i + search.length();
            int end = body.indexOf(",", start);
            if (end == -1) end = body.indexOf("}", start);
            String value = body.substring(start, end).replaceAll("[\"{}]", "").trim();
            return value;
        }
    }

    static class PaymentSummaryHandler implements HttpHandler {
        @Override
        public void handle(HttpExchange exchange) throws IOException {
            if ("GET".equalsIgnoreCase(exchange.getRequestMethod())) {
                CompletableFuture<Long> defaultRequestsFut = RedisAsyncManager.getTotalRequests();
                CompletableFuture<BigDecimal> defaultAmountFut = RedisAsyncManager.getAmountAsBigDecimal();
                CompletableFuture<Long> fallbackRequestsFut = CompletableFuture.completedFuture(0L);
                CompletableFuture<BigDecimal> fallbackAmountFut = CompletableFuture.completedFuture(BigDecimal.ZERO);

                CompletableFuture.allOf(defaultRequestsFut, defaultAmountFut, fallbackRequestsFut, fallbackAmountFut)
                        .thenAccept(v -> {
                            long defaultRequests = defaultRequestsFut.join();
                            BigDecimal defaultAmount = defaultAmountFut.join();
                            long fallbackRequests = fallbackRequestsFut.join();
                            BigDecimal fallbackAmount = fallbackAmountFut.join();

                            String response = String.format(
                                    "{\"default\":{\"totalRequests\":%d,\"totalAmount\":%.2f},\"fallback\":{\"totalRequests\":%d,\"totalAmount\":%.2f}}",
                                    defaultRequests, defaultAmount, fallbackRequests, fallbackAmount
                            );
                            try {
                                exchange.getResponseHeaders().set("Content-Type", "application/json");
                                exchange.sendResponseHeaders(200, response.getBytes().length);
                                OutputStream os = exchange.getResponseBody();
                                os.write(response.getBytes());
                                os.close();
                            } catch (IOException e) {
                                e.printStackTrace();
                            }
                        });
            } else {
                exchange.sendResponseHeaders(405, -1);
            }
        }
    }
}