package com.javalearn.lab.seckill.admission.infra;

import com.javalearn.lab.seckill.admission.app.SeckillLockGuard;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * Redisson per-product distributed lock.
 *
 * <p>{@code tryLock(wait-ms, lease-ms)} bounds both the wait and the hold
 * time. Both values come from {@code app.seckill.lock.*}. Unlock is best-effort:
 * a lease that expired while the request was still running is logged instead of
 * masking the original outcome.</p>
 */
public final class RedissonSeckillLockGuard implements SeckillLockGuard {

    private static final Logger log = LoggerFactory.getLogger(RedissonSeckillLockGuard.class);

    private final RedissonClient redissonClient;
    private final String keyPrefix;
    private final long waitMs;
    private final long leaseMs;

    public RedissonSeckillLockGuard(RedissonClient redissonClient,
                                    String keyPrefix,
                                    long waitMs,
                                    long leaseMs) {
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.keyPrefix = Objects.requireNonNull(keyPrefix, "keyPrefix");
        this.waitMs = waitMs;
        this.leaseMs = leaseMs;
    }

    @Override
    public LockHandle tryLock(long productId) {
        RLock lock = redissonClient.getLock(keyPrefix + productId);
        boolean acquired;
        try {
            acquired = lock.tryLock(waitMs, leaseMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return null;
        }
        if (!acquired) {
            return null;
        }
        return () -> release(lock, productId);
    }

    private static void release(RLock lock, long productId) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            } else {
                log.warn("Seckill lock for product {} is no longer held by this thread "
                        + "(lease expired)", productId);
            }
        } catch (RuntimeException releaseFailure) {
            log.warn("Best-effort seckill unlock failed for product {}", productId,
                    releaseFailure);
        }
    }
}
