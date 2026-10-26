package com.javalearn.lab.seckill.admission.infra;

import com.javalearn.lab.seckill.admission.app.SeckillIdempotencyStore;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;

/**
 * Redis {@code SET NX EX} idempotency claim.
 */
public final class RedisSeckillIdempotencyStore implements SeckillIdempotencyStore {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;
    private final Duration ttl;

    public RedisSeckillIdempotencyStore(StringRedisTemplate redisTemplate,
                                        String keyPrefix,
                                        Duration ttl) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.ttl = Objects.requireNonNull(ttl, "ttl");
    }

    @Override
    public String claim(String idempotencyKey, String requestId) {
        String key = key(idempotencyKey);
        Boolean claimed = redisTemplate.opsForValue().setIfAbsent(key, requestId, ttl);
        if (Boolean.TRUE.equals(claimed)) {
            return null;
        }
        String existing = redisTemplate.opsForValue().get(key);
        if (existing == null) {
            // The key expired between SET NX and GET; the caller can retry with
            // the same idempotency key safely.
            throw new IllegalStateException(
                    "Idempotency key expired during claim: " + idempotencyKey);
        }
        return existing;
    }

    @Override
    public void release(String idempotencyKey) {
        redisTemplate.delete(key(idempotencyKey));
    }

    private String key(String idempotencyKey) {
        return keyPrefix + idempotencyKey;
    }
}
