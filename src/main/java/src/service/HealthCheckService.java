package src.service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import src.model.PaymentRequest;

public class HealthCheckService {
    private static final HttpClient httpClient = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(1))
            .build();

    public static boolean checkDefaultProcessorHealth(String processorUrl, PaymentRequest payment) {
        try {
            String jsonPayload = String.format(
                    "{\"correlationId\":\"%s\",\"amount\":%.2f,\"requestedAt\":\"%s\"}",
                    payment.correlationId, payment.amount, payment.requestedAt
            );
            HttpRequest request = HttpRequest.newBuilder()
                    .uri(URI.create(processorUrl + "/payments"))
                    .timeout(Duration.ofMillis(200))
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(jsonPayload))
                    .build();
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int statusCode = response.statusCode();
            return (statusCode >= 200 && statusCode < 420);
        } catch (Exception e) {
            return false;
        }
    }
}