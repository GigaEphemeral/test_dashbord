package com.javalearn.lab.seckill.catalog.cache;

import java.time.Duration;

/**
 * The narrow seam between the jittering cache decorator and Redis: read the TTL
 * a write just produced and rewrite that key's TTL. Keeping it behind an
 * interface lets the decorator be verified without a Redis container, exactly
 * like the M4-1/M4-2 guards fake Redisson.
 */
public interface L2TtlAdjuster {

    /**
     * @return the remaining TTL of the L2 entry for {@code key}, or {@code null}
     *         when the key is not a product id, does not exist or carries no TTL
     */
    Duration remainingTtl(Object key);

    /** Best-effort TTL rewrite; failures are counted by the caller, never thrown to a reader. */
    void rewrite(Object key, Duration ttl);
}
