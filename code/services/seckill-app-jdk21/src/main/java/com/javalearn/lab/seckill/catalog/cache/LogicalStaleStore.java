package com.javalearn.lab.seckill.catalog.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Ticker;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;

/**
 * The "old value with its own logical expiry" store behind the M4-2
 * {@code logical-expire} breakdown strategy (design 8.4 击穿 row).
 *
 * <p>Every rebuild is recorded with two clocks: a <em>logical</em> expiry
 * carried inside the entry (when the value stops being trustworthy) and a
 * longer <em>physical</em> retention enforced by Caffeine's
 * {@code expireAfterWrite} (how long the stale value is still worth serving as
 * the fallback). That gap is exactly the strategy: once the physical L2 entry
 * is gone, the last good value keeps answering while one async refresh
 * rebuilds it — and the retention bound stops an eternally stale value from
 * being served forever.</p>
 *
 * <p>The store is per-instance and bounded ({@code max-entries}), so a key
 * storm can never grow it without limit. The Caffeine {@link Ticker} is
 * injectable so the physical expiry is testable without sleeping.</p>
 */
public final class LogicalStaleStore {

    /** A value plus the wall-clock millis at which it stopped being fresh. */
    public record StaleValue(ProductResponse value, long logicalExpireAtMillis) {
    }

    private final Cache<Long, StaleValue> entries;
    private final Duration logicalTtl;
    private final Duration retention;

    public LogicalStaleStore(long maxEntries, Duration retention, Duration logicalTtl, Ticker ticker) {
        Objects.requireNonNull(retention, "retention");
        Objects.requireNonNull(logicalTtl, "logicalTtl");
        if (maxEntries <= 0L) {
            throw new IllegalArgumentException("maxEntries must be positive: " + maxEntries);
        }
        if (retention.compareTo(logicalTtl) <= 0) {
            throw new IllegalArgumentException("stale retention " + retention
                    + " must exceed the logical ttl " + logicalTtl + " or there is nothing to serve");
        }
        this.logicalTtl = logicalTtl;
        this.retention = retention;
        this.entries = Caffeine.newBuilder()
                .maximumSize(maxEntries)
                .expireAfterWrite(retention)
                .ticker(ticker)
                .build();
    }

    /** Records a freshly rebuilt value; the logical deadline is {@code nowMillis + logicalTtl}. */
    public void put(long id, ProductResponse value, long nowMillis) {
        entries.put(id, new StaleValue(Objects.requireNonNull(value, "value"),
                nowMillis + logicalTtl.toMillis()));
    }

    /**
     * @return the entry while it is physically retained; empty once the
     *         retention window has passed (Caffeine evicted it)
     */
    public Optional<StaleValue> find(long id) {
        return Optional.ofNullable(entries.getIfPresent(id));
    }

    /** Live entry count, for {@code GET /lab/cache/state}. */
    public long size() {
        entries.cleanUp();
        return entries.estimatedSize();
    }

    Duration retention() {
        return retention;
    }

    Duration logicalTtl() {
        return logicalTtl;
    }
}
