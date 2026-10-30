package com.javalearn.lab.seckill.jdk8.catalog.cache;

import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Production {@link L2TtlAdjuster}: reads the remaining TTL of
 * {@code cache:product:<id>} and rewrites it with Redis {@code EXPIRE}.
 *
 * <p>Reading the TTL the cache actually wrote (instead of a duplicated L2-TTL
 * constant) keeps the jitter correct even if the L2 TTL is externalized later:
 * the spread is always relative to what the write really produced.</p>
 */
final class RedisL2TtlAdjuster implements L2TtlAdjuster {

    private final StringRedisTemplate redisTemplate;
    private final String keyPrefix;

    RedisL2TtlAdjuster(StringRedisTemplate redisTemplate, String keyPrefix) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
    }

    @Override
    public Duration remainingTtl(Object key) {
        if (!(key instanceof Long)) {
            return null;
        }
        Long id = (Long) key;
        Long seconds = redisTemplate.getExpire(keyPrefix + id, TimeUnit.SECONDS);
        if (seconds == null || seconds <= 0L) {
            return null;
        }
        return Duration.ofSeconds(seconds);
    }

    @Override
    public void rewrite(Object key, Duration ttl) {
        if (!(key instanceof Long)) {
            return;
        }
        Long id = (Long) key;
        redisTemplate.expire(keyPrefix + id, ttl);
    }
}
