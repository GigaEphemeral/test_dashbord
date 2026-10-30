package com.javalearn.lab.seckill.jdk8.admission.app;

/**
 * Per-product distributed lock. The production implementation is Redisson;
 * abstracting the two operations keeps the acceptance logic unit-testable
 * without Redis.
 */
public interface SeckillLockGuard {

    /**
     * Tries to acquire the product lock.
     *
     * @return an open handle, or {@code null} when the lock was not acquired
     *         within the configured wait time
     */
    LockHandle tryLock(long productId);

    interface LockHandle extends AutoCloseable {

        @Override
        void close();
    }
}
