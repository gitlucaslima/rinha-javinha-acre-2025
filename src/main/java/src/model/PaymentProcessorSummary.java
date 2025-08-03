package src.model;

import java.math.BigDecimal;

public class PaymentProcessorSummary {
    public long totalRequests;
    public BigDecimal totalAmount;

    public PaymentProcessorSummary(long totalRequests, BigDecimal totalAmount) {
        this.totalRequests = totalRequests;
        this.totalAmount = totalAmount;
    }

    @Override
    public String toString() {
        return "PaymentProcessorSummary [totalRequests=" + totalRequests + ", totalAmount=" + totalAmount + "]";
    }
}
