package src.processor;

import java.math.BigDecimal;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import src.model.PaymentRequest;
import src.service.HealthCheckService;
import src.service.PaymentService;
import src.service.RedisAsyncManager;

public class SimplePaymentProcessor {
    private static final String DEFAULT_PROCESSOR = System.getenv().getOrDefault("DEFAULT_PROCESSOR_URL",
            "http://localhost:8001");

    private static final BlockingQueue<PaymentRequest> paymentQueue = new LinkedBlockingQueue<>();

    private static volatile PaymentRequest successfulPaymentForTest = null;
    private static volatile boolean defaultProcessorHealthy = true;
    private static final int WORKER_POOL_SIZE = Runtime.getRuntime().availableProcessors();
    private static final ExecutorService workerExecutor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
    private static final ScheduledExecutorService healthCheckExecutor = Executors.newScheduledThreadPool(4);

    // Enfileira pagamento vindo do endpoint
    public static void enqueuePayment(PaymentRequest req) {
        paymentQueue.offer(req);
    }

    public static void startPaymentWorker() {
        for (int i = 0; i < WORKER_POOL_SIZE; i++) {
            workerExecutor.submit(() -> {
                while (true) {
                    try {
                        PaymentRequest payment = paymentQueue.take();
    
                        boolean exists = RedisAsyncManager.existsAsync(payment.correlationId)
                            .get(2, TimeUnit.SECONDS);
    
                        if (exists) {
                            continue; // já processado, pega próximo
                        }
    
                        if (!defaultProcessorHealthy) {
                            paymentQueue.offer(payment);
                            Thread.sleep(50);
                            continue;
                        }
    
                        boolean success = PaymentService.sendPaymentToProcessor(payment, DEFAULT_PROCESSOR);
    
                        if (success) {
                            RedisAsyncManager.incrementTotalRequests().get(1, TimeUnit.SECONDS);
                            RedisAsyncManager.incrementTotalAmount(new BigDecimal(payment.amount)).get(1, TimeUnit.SECONDS);
                            RedisAsyncManager.createRedisRequest(payment.requestedAt, payment.correlationId, payment.amount).get(1, TimeUnit.SECONDS);
    
                            if (successfulPaymentForTest == null) {
                                successfulPaymentForTest = payment;
                            }
                        } else {
                            defaultProcessorHealthy = false;
                            paymentQueue.offer(payment);
                            Thread.sleep(50);
                        }
                    } catch (Exception e) {
                        e.printStackTrace();
                        // Em caso de erro, opcional: esperar um pouco antes de continuar para evitar loop rápido
                        try {
                            Thread.sleep(100);
                        } catch (InterruptedException ignored) {}
                    }
                }
            });
        }
    }
    
    public static void startHealthCheckMonitoring() {
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (successfulPaymentForTest != null && !defaultProcessorHealthy) {
                boolean healthy = HealthCheckService.checkDefaultProcessorHealth(DEFAULT_PROCESSOR,
                        successfulPaymentForTest);
                if (healthy)
                    defaultProcessorHealthy = true;
            }
        }, 100, 10, TimeUnit.MILLISECONDS);
    }
}