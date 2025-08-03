package src;

import src.service.PaymentApiServer;
import src.processor.SimplePaymentProcessor;

public class Main {

    public static void main(String[] args) throws Exception {
        SimplePaymentProcessor.startHealthCheckMonitoring();
        SimplePaymentProcessor.startPaymentWorker();
        PaymentApiServer.start(8080);
    }
}