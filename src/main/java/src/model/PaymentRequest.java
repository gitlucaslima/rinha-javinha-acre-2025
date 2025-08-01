package src.model;

import java.time.Instant;

public class PaymentRequest {
    public String correlationId;
    public String amount;
    public String requestedAt;

    public PaymentRequest(String correlationId, String amount) {
        this.correlationId = correlationId;
        this.amount = amount;
        this.requestedAt = Instant.now().toString();
    }
}