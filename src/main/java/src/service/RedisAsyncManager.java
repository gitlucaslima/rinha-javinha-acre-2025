package src.service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import io.lettuce.core.ClientOptions;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;
import io.lettuce.core.resource.ClientResources;
import io.lettuce.core.resource.DefaultClientResources;

public class RedisAsyncManager {
    private static final String REDIS_URI = System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379");
    
    // ✅ OTIMIZAÇÃO 1: ClientResources otimizado para baixa latência
    private static final ClientResources clientResources = DefaultClientResources.builder()
        .ioThreadPoolSize(Math.max(4, Runtime.getRuntime().availableProcessors()))
        .computationThreadPoolSize(Math.max(2, Runtime.getRuntime().availableProcessors() / 2))
        .build();
    
    // ✅ OTIMIZAÇÃO 2: ClientOptions para performance máxima
    private static final ClientOptions clientOptions = ClientOptions.builder()
        .autoReconnect(true)
        .pingBeforeActivateConnection(false) // Remove ping inicial
        .build();
    
    private static final RedisClient redisClient = RedisClient.create(clientResources, REDIS_URI);
    
    static {
        redisClient.setOptions(clientOptions);
    }
    
    private static final StatefulRedisConnection<String, String> connection = redisClient.connect();
    private static final RedisAsyncCommands<String, String> async = connection.async();

    // ✅ OTIMIZAÇÃO 3: Timeout agressivo em todas as operações
    private static final int REDIS_TIMEOUT_MS = 50;

    public static CompletableFuture<Boolean> createRedisRequest(String requestedAt, String correlationId, String amount) {
        // ✅ Concatenação direta - mais rápida que StringBuilder para strings pequenas
        String value = "{\"requestedAt\":\"" + requestedAt + "\",\"amount\":\"" + amount + "\"}";
        return async.setnx(correlationId, value)
                   .toCompletableFuture()
                   .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                   .exceptionally(throwable -> false); // Falha rápida
    }
    
    public static CompletableFuture<Long> incrementTotalRequests() {
        return async.incr("summary:default:totalRequests")
                    .toCompletableFuture()
                    .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .exceptionally(throwable -> 0L);
    }

    public static CompletableFuture<Long> getTotalRequests() {
        return async.get("summary:default:totalRequests")
                    .toCompletableFuture()
                    .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .thenApply(val -> {
                        if (val == null || val.isEmpty()) return 0L;
                        try {
                            return Long.parseLong(val);
                        } catch (NumberFormatException e) {
                            return 0L;
                        }
                    })
                    .exceptionally(throwable -> 0L);
    }

    public static CompletableFuture<BigDecimal> incrementTotalAmount(BigDecimal amount) {
        return async.incrbyfloat("summary:default:totalAmount", amount.doubleValue())
                    .toCompletableFuture()
                    .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .thenApply(BigDecimal::valueOf)
                    .exceptionally(throwable -> BigDecimal.ZERO);
    }

    public static CompletableFuture<BigDecimal> getAmountAsBigDecimal() {
        return async.get("summary:default:totalAmount")
                    .toCompletableFuture()
                    .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                    .thenApply(val -> {
                        if (val == null || val.isEmpty()) return BigDecimal.ZERO;
                        try {
                            return new BigDecimal(val);
                        } catch (NumberFormatException e) {
                            return BigDecimal.ZERO;
                        }
                    })
                    .exceptionally(throwable -> BigDecimal.ZERO);
    }   

    // ✅ OTIMIZAÇÃO 4: existsAsync ultra-rápido usando GET
    public static CompletableFuture<Boolean> existsAsync(String key) {
        return async.get(key)
                   .toCompletableFuture()
                   .orTimeout(REDIS_TIMEOUT_MS, TimeUnit.MILLISECONDS)
                   .thenApply(val -> val != null)
                   .exceptionally(throwable -> false);
    }

    // ✅ OTIMIZAÇÃO 5: Método de inicialização para verificar conectividade
    public static boolean initialize() {
        try {
            async.ping().get(1000, TimeUnit.MILLISECONDS);
            return true;
        } catch (Exception e) {
            System.err.println("Redis connection failed: " + e.getMessage());
            return false;
        }
    }

    public static void shutdown() {
        try {
            connection.close();
            redisClient.shutdown();
            clientResources.shutdown();
        } catch (Exception e) {
            // Ignore shutdown errors
        }
    }
}