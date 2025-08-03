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

    // Queue ULTRA-OTIMIZADA
    private static final BlockingQueue<PaymentRequest> paymentQueue = new LinkedBlockingQueue<>(1000000);

    private static volatile PaymentRequest successfulPaymentForTest = null;
    private static volatile boolean defaultProcessorHealthy = true;

    // Pool MAIS AGRESSIVO para máxima performance
    private static final int AVAILABLE_CORES = Runtime.getRuntime().availableProcessors();
    private static final int WORKER_POOL_SIZE = Math.max(12, AVAILABLE_CORES * 3); // Aumentado de *2 para *3
    private static final int HEALTH_CHECK_THREADS = 1; // Reduzido para 1 thread apenas

    private static final ExecutorService workerExecutor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
    private static final ScheduledExecutorService healthCheckExecutor = Executors
            .newScheduledThreadPool(HEALTH_CHECK_THREADS);

    public static void enqueuePayment(PaymentRequest req) {
        // Oferece sem bloqueio - se queue cheia, dropa (fail-fast)
        paymentQueue.offer(req);
    }

    public static void startPaymentWorker() {
        java.util.stream.IntStream.range(0, WORKER_POOL_SIZE)
                .parallel()
                .forEach(i -> workerExecutor.submit(SimplePaymentProcessor::processPaymentsOptimized));
    }

    // Versão ULTRA-OTIMIZADA do processPayments
    private static void processPaymentsOptimized() {
        while (true) {
            try {
                // Poll com timeout ao invés de take() bloqueante
                PaymentRequest payment = paymentQueue.poll(10, TimeUnit.MILLISECONDS);
                if (payment == null) continue;

                if (!defaultProcessorHealthy) {
                    // Rejeita imediatamente se unhealthy
                    continue;
                }

                // Check Redis MAIS AGRESSIVO
                RedisAsyncManager.existsAsync(payment.correlationId)
                        .orTimeout(25, TimeUnit.MILLISECONDS) // Timeout ULTRA agressivo
                        .whenComplete((exists, throwable) -> {
                            if (throwable != null || Boolean.TRUE.equals(exists)) {
                                return; // Dropa duplicata ou erro
                            }

                            processPaymentAsyncOptimized(payment);
                        });

            } catch (Exception ignored) {
                // Ignora erros para manter performance
            }
        }
    }

    private static void processPaymentAsyncOptimized(PaymentRequest payment) {
        java.util.concurrent.CompletableFuture
                .supplyAsync(() -> PaymentService.sendPaymentToProcessor(payment, DEFAULT_PROCESSOR))
                .orTimeout(500, TimeUnit.MILLISECONDS) // Timeout mais agressivo
                .whenComplete((success, throwable) -> {
                    if (throwable != null) {
                        defaultProcessorHealthy = false;
                        return; // Não requeue - dropa
                    }

                    if (Boolean.TRUE.equals(success)) {
                        // Fire-and-forget Redis operations
                        RedisAsyncManager.incrementTotalRequests();
                        RedisAsyncManager.createRedisRequest(payment.requestedAt, payment.correlationId,
                                payment.amount);

                        if (successfulPaymentForTest == null) {
                            successfulPaymentForTest = payment;
                        }
                    } else {
                        defaultProcessorHealthy = false;
                    }
                });
    }

    public static void startHealthCheckMonitoring() {
        // Health check MENOS agressivo
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (successfulPaymentForTest != null && !defaultProcessorHealthy) {
                defaultProcessorHealthy = HealthCheckService.checkDefaultProcessorHealth(DEFAULT_PROCESSOR,
                        successfulPaymentForTest);
            }
        }, 200, 500, TimeUnit.MILLISECONDS); // Aumentado intervalo
    }
}