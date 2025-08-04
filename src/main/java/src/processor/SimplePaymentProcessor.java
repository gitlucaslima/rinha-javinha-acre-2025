package src.processor;

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

    // Queue menor para evitar memory bloat
    private static final BlockingQueue<PaymentRequest> paymentQueue = new LinkedBlockingQueue<>(5000);

    private static volatile PaymentRequest successfulPaymentForTest = null;
    private static volatile boolean defaultProcessorHealthy = true;

    // Pool MUITO reduzido - menos overhead
    private static final int AVAILABLE_CORES = Runtime.getRuntime().availableProcessors();
    private static final int WORKER_POOL_SIZE = Math.max(2, AVAILABLE_CORES / 2); // REDUZIDO drasticamente
    private static final int HEALTH_CHECK_THREADS = 1;

    private static final ExecutorService workerExecutor = Executors.newFixedThreadPool(WORKER_POOL_SIZE);
    private static final ScheduledExecutorService healthCheckExecutor = Executors
            .newScheduledThreadPool(HEALTH_CHECK_THREADS);

    public static void enqueuePayment(PaymentRequest req) {
        // Fire-and-forget - se queue cheia, dropa
        paymentQueue.offer(req);
    }

    public static void startPaymentWorker() {
        // Menos workers = menos overhead
        for (int i = 0; i < WORKER_POOL_SIZE; i++) {
            workerExecutor.submit(SimplePaymentProcessor::processPaymentsSimple);
        }
    }

    // Versão SIMPLIFICADA - menos async overhead
    private static void processPaymentsSimple() {
        while (true) {
            try {
                // Timeout maior para reduzir CPU usage
                PaymentRequest payment = paymentQueue.poll(50, TimeUnit.MILLISECONDS);
                if (payment == null) continue;

                if (!defaultProcessorHealthy) {
                    continue; // Skip se unhealthy
                }

                // Processamento MAIS DIRETO
                processPaymentDirect(payment);

            } catch (Exception ignored) {
                // Ignora erros
            }
        }
    }

    private static void processPaymentDirect(PaymentRequest payment) {
        try {
            // Check duplicata SÍNCRONO e rápido (timeout baixo)
            Boolean exists = RedisAsyncManager.existsAsync(payment.correlationId)
                    .orTimeout(15, TimeUnit.MILLISECONDS) // Timeout ULTRA baixo
                    .join(); // Transforma em síncrono

            if (Boolean.TRUE.equals(exists)) {
                return; // Duplicata - dropa
            }

            // Envia para processor
            Boolean success = PaymentService.sendPaymentToProcessor(payment, DEFAULT_PROCESSOR);
            
            if (Boolean.TRUE.equals(success)) {
                // Redis operations FIRE-AND-FORGET (sem aguardar)
                RedisAsyncManager.incrementTotalRequests();
                RedisAsyncManager.createRedisRequest(payment.requestedAt, payment.correlationId, payment.amount);

                if (successfulPaymentForTest == null) {
                    successfulPaymentForTest = payment;
                }
            } else {
                defaultProcessorHealthy = false;
            }

        } catch (Exception e) {
            // Se Redis timeout ou falha, marca processor como unhealthy
            defaultProcessorHealthy = false;
        }
    }

    public static void startHealthCheckMonitoring() {
        // Health check MUITO menos frequente
        healthCheckExecutor.scheduleWithFixedDelay(() -> {
            if (successfulPaymentForTest != null && !defaultProcessorHealthy) {
                defaultProcessorHealthy = HealthCheckService.checkDefaultProcessorHealth(DEFAULT_PROCESSOR,
                        successfulPaymentForTest);
            }
        }, 500, 1000, TimeUnit.MILLISECONDS); // Intervalos maiores
    }
}