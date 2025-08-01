package src.service;

import java.math.BigDecimal;
import java.util.concurrent.CompletableFuture;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.async.RedisAsyncCommands;

public class RedisAsyncManager {
    private static final String REDIS_URI = System.getenv().getOrDefault("REDIS_URI", "redis://localhost:6379");
    private static final RedisClient redisClient = RedisClient.create(REDIS_URI);
    private static final StatefulRedisConnection<String, String> connection = redisClient.connect();
    private static final RedisAsyncCommands<String, String> async = connection.async();

    public static CompletableFuture<Boolean> createRedisRequest(String requestedAt, String correlationId, String amount) {
        String value = String.format("{\"requestedAt\":\"%s\",\"amount\":\"%s\"}", requestedAt, amount);
        return async.setnx(correlationId, value) // retorna true se a chave foi criada
                    .toCompletableFuture();
    }

    public static CompletableFuture<Long> incrementTotalRequests() {
        return async.incr("summary:default:totalRequests")
                    .toCompletableFuture();
    }

    public static CompletableFuture<Long> getTotalRequests() {
        return async.get("summary:default:totalRequests")
                    .toCompletableFuture()
                    .thenApply(value -> {
                        if (value == null) {
                            return 0L;
                        }
                        return Long.parseLong(value);
                    });
    }

    public static CompletableFuture<BigDecimal> incrementTotalAmount(BigDecimal amount) {
        return async.incrbyfloat("summary:default:totalAmount", amount.doubleValue())
                    .toCompletableFuture()
                    .thenApply(BigDecimal::valueOf);
    }

    public static CompletableFuture<BigDecimal> getAmountAsBigDecimal() {
        return async.get("summary:default:totalAmount")
                    .toCompletableFuture()
                    .thenApply(val -> val == null ? BigDecimal.ZERO : new BigDecimal(val));
    }   

    public static CompletableFuture<Boolean> existsAsync(String key) {
        return async.exists(key)
                    .toCompletableFuture()
                    .thenApply(count -> count > 0);
    }
   
    public static void shutdown() {
        connection.close();
        redisClient.shutdown();
    }
}

