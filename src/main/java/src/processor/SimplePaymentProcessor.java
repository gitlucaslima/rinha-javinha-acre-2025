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

    // Configuração dinâmica baseada no hardware
    private static final int AVAILABLE_CORES = Runtime.getRuntime().availableProcessors();
    private static final int WORKER_POOL_SIZE = Math.max(8, AVAILABLE_CORES * 2); 
    private static final int HEALTH_CHECK_THREADS = Math.max(2, AVAILABLE_CORES / 4);

    private static final ExecutorService workerExecutor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
    private static final ScheduledExecutorService healthCheckExecutor = Executors
            .newScheduledThreadPool(HEALTH_CHECK_THREADS);

    public static void enqueuePayment(PaymentRequest req) {
        paymentQueue.offer(req);
    }

    public static void startPaymentWorker() {
        java.util.stream.IntStream.range(0, WORKER_POOL_SIZE)
                .parallel() // Cria workers em paralelo
                .forEach(i -> workerExecutor.submit(SimplePaymentProcessor::processPayments));
    }

    private static void processPayments() {
        while (true) {
            try {
                PaymentRequest payment = paymentQueue.take();

                if (!defaultProcessorHealthy) {
                    paymentQueue.offer(payment);
                    continue;
                }

                RedisAsyncManager.existsAsync(payment.correlationId)
                        .whenComplete((exists, throwable) -> {
                            if (throwable != null || exists) {
                                return;
                            }

                            processPaymentAsync(payment);
                        });

            } catch (Exception ignored) {
            }
        }
    }

    private static void processPaymentAsync(PaymentRequest payment) {
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> PaymentService.sendPaymentToProcessor(payment, DEFAULT_PROCESSOR))
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        defaultProcessorHealthy = false;
                        paymentQueue.offer(payment);
                        return;
                    }

                    if (success) {
                        RedisAsyncManager.incrementTotalRequests();
                        RedisAsyncManager.incrementTotalAmount(new BigDecimal(payment.amount));
                        RedisAsyncManager.createRedisRequest(payment.requestedAt, payment.correlationId,
                                payment.amount);

                        if (successfulPaymentForTest == null) {
                            successfulPaymentForTest = payment;
                        }
                    } else {
                        defaultProcessorHealthy = false;
                        paymentQueue.offer(payment);
                    }
                });
    }

    public static void startHealthCheckMonitoring() {
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (successfulPaymentForTest != null && !defaultProcessorHealthy) {
                defaultProcessorHealthy = HealthCheckService.checkDefaultProcessorHealth(DEFAULT_PROCESSOR,
                        successfulPaymentForTest);
            }
        }, 100, 200, TimeUnit.MILLISECONDS);
    }
}