package com.javalearn.lab.seckill.lab.cache;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Per-JVM Caffeine L1 switch (B3-01).
 *
 * <p>The state lives in this process only: each app instance decides whether its
 * own Caffeine layer participates, which is exactly what the A/B cache experiment
 * needs. The switch defaults to enabled and is lock-free on the read path. It
 * only holds the flag; {@link com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheManager}
 * fans a change out to every cache it manages so disabling also clears the
 * already-cached entries.</p>
 */
public final class L1Switch {

    private final AtomicBoolean enabled = new AtomicBoolean(true);

    public boolean isEnabled() {
        return enabled.get();
    }

    /**
     * Applies a new value.
     *
     * @return the previous value, so callers can detect an idempotent update
     *         and skip clearing caches when nothing changed
     */
    public boolean setEnabled(boolean value) {
        return enabled.getAndSet(value);
    }
}
