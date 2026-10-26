package com.javalearn.lab.seckill.lab.cache.preheat;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Per-instance hot-key detector (design 8.4, M4-3).
 *
 * <p>Every product read is recorded in a Caffeine cache capped at
 * {@code tracker.capacity} entries (10 万 by default). A counter is not a plain
 * {@code AtomicLong}: each sample carries the decay epoch it was written in, so
 * its effective score is {@code count >> (currentEpoch - sampleEpoch)}. That is
 * exactly "halve every 30s" without a background sweep over up to 100k entries
 * and — more importantly for the lab — makes decay deterministic under a
 * controllable {@link Clock} in tests.</p>
 *
 * <p>The Top-N snapshot is published to one shared Redis ZSet
 * ({@code hot:products}) so every instance preheats from the same list. Redis is
 * best-effort like the rest of the cache: a failed publish is logged, counted as
 * {@code cache_hotkey_publish{result="failure"}}, and the previous Redis list is
 * left in place; the read path never sees the exception. Publishing replaces the
 * whole ZSet through a staging key and an atomic {@code RENAME}, so readers never
 * see an empty or partial list and old members do not linger with stale scores.</p>
 */
public final class HotKeyTracker {

    private static final Logger log = LoggerFactory.getLogger(HotKeyTracker.class);

    static final String CACHE_TAG = "product";

    /**
     * Side key a Top-N publish stages into before an atomic {@code RENAME}
     * moves it onto the shared hot list. One key per hot list; a leftover from
     * a crashed attempt is pre-cleared by the next publish.
     */
    static final String STAGING_SUFFIX = ":staging";

    private final LabCachePreheatProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final Clock clock;
    private final Cache<Long, Sample> counters;
    private final Counter publishSuccess;
    private final Counter publishFailure;
    private final AtomicLong hotListSize = new AtomicLong();

    public HotKeyTracker(LabCachePreheatProperties properties,
                         StringRedisTemplate redisTemplate,
                         MeterRegistry meterRegistry,
                         Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.clock = Objects.requireNonNull(clock, "clock");
        Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.counters = Caffeine.newBuilder()
                .maximumSize(properties.getTracker().getCapacity())
                .build();
        this.publishSuccess = Counter.builder("cache_hotkey_publish")
                .description("Top-N hot-list publishes to the shared Redis ZSet")
                .tag("cache", CACHE_TAG).tag("result", "success")
                .register(meterRegistry);
        this.publishFailure = Counter.builder("cache_hotkey_publish")
                .description("Top-N hot-list publishes to the shared Redis ZSet")
                .tag("cache", CACHE_TAG).tag("result", "failure")
                .register(meterRegistry);
        Gauge.builder("cache_preheat_hot_list_size", hotListSize, AtomicLong::doubleValue)
                .description("Members of the shared hot-products ZSet as last observed")
                .tag("cache", CACHE_TAG)
                .register(meterRegistry);
    }

    /**
     * Records one product read. This is called from the MVC interceptor before
     * the cache/DB read, so it must stay allocation-cheap and never touch Redis.
     */
    public void record(long productId) {
        long epoch = currentEpoch();
        counters.asMap().compute(productId, (id, previous) -> {
            long count = previous == null ? 0L : previous.decayedTo(epoch);
            return new Sample(epoch, count + 1L);
        });
    }

    /** The current Top-N as an immutable, score-desc/product-id-asc snapshot. */
    public List<ScoredProduct> topN() {
        // Caffeine enforces maximumSize through amortized maintenance; the
        // periodic publish is the right place to drain it so the snapshot never
        // reports more keys than the configured capacity (or Top-N).
        counters.cleanUp();
        long epoch = currentEpoch();
        long limit = properties.getTopN();
        List<ScoredProduct> scored = new ArrayList<>();
        counters.asMap().forEach((productId, sample) -> {
            long count = sample.decayedTo(epoch);
            if (count > 0L) {
                scored.add(new ScoredProduct(productId, count));
            }
        });
        scored.sort(Comparator.comparingLong(ScoredProduct::score).reversed()
                .thenComparingLong(ScoredProduct::productId));
        int size = (int) Math.min(limit, scored.size());
        return List.copyOf(scored.subList(0, size));
    }

    /**
     * Replaces the shared ZSet with the current Top-N. Scores are the decayed
     * counts; expiry of the list itself is intentionally not set — the list is
     * exactly what lets a restarted instance preheat (design 8.4: 跨实例共享).
     *
     * <p>The replacement is staged under {@code <hot-list-key>:staging} and
     * moved onto the shared key with {@code RENAME}, which Redis executes
     * atomically: readers never observe an empty or partial list, and a Redis
     * outage mid-publish leaves the previously shared list in place (the
     * original delete-first/add-second order destroyed it on failure,
     * contradicting this method's own contract). Cross-instance semantics are
     * last-writer-wins: each publisher replaces the list with its own Top-N
     * view, which converges while instances share traffic.</p>
     *
     * <p>A flush whose local Top-N is empty is a no-op: an instance that has
     * seen no reads yet (fresh boot, idle replica) must not wipe the list the
     * other instances' startup preheat depends on.</p>
     *
     * @return the number of members published; 0 for an empty view or when
     *         Redis refused the write
     */
    public int publishTopN() {
        List<ScoredProduct> top = topN();
        String key = properties.getHotListKey();
        if (top.isEmpty()) {
            refreshHotListSize();
            return 0;
        }
        String stagingKey = key + STAGING_SUFFIX;
        try {
            Set<ZSetOperations.TypedTuple<String>> tuples = new LinkedHashSet<>();
            for (ScoredProduct product : top) {
                tuples.add(ZSetOperations.TypedTuple.of(
                        Long.toString(product.productId()), (double) product.score()));
            }
            redisTemplate.delete(stagingKey);
            redisTemplate.opsForZSet().add(stagingKey, tuples);
            redisTemplate.rename(stagingKey, key);
            Long size = redisTemplate.opsForZSet().zCard(key);
            hotListSize.set(size == null ? 0L : size);
            publishSuccess.increment();
            return top.size();
        } catch (RuntimeException ex) {
            publishFailure.increment();
            log.warn("Hot-key publish to Redis ZSet '{}' failed; keeping the previous list: {}",
                    key, ex.toString());
            deleteQuietly(stagingKey);
            return 0;
        }
    }

    /** Best-effort staging cleanup; the next flush pre-clears the key anyway. */
    private void deleteQuietly(String key) {
        try {
            redisTemplate.delete(key);
        } catch (RuntimeException ignored) {
            // Redis is already counted as failing; a stale staging key is
            // invisible to readers and is deleted by the next publish attempt.
        }
    }

    /**
     * Best-effort live size of the shared list for the state endpoint. Keeps the
     * gauge on the last successful observation when Redis is unreachable.
     */
    public long refreshHotListSize() {
        try {
            Long size = redisTemplate.opsForZSet().zCard(properties.getHotListKey());
            long value = size == null ? 0L : size;
            hotListSize.set(value);
            return value;
        } catch (RuntimeException ex) {
            log.debug("Hot-list size read failed (Redis unavailable): {}", ex.toString());
            return hotListSize.get();
        }
    }

    /** Last observed shared-list size (0 before the first publish/read). */
    public long hotListSize() {
        return hotListSize.get();
    }

    /** Number of product ids currently tracked locally (capped by Caffeine). */
    public long trackedKeys() {
        counters.cleanUp();
        return counters.estimatedSize();
    }

    private long currentEpoch() {
        return clock.millis() / (properties.getTracker().getDecayIntervalSeconds() * 1_000L);
    }

    /** One product's decayed score in a stable, immutable snapshot. */
    public record ScoredProduct(long productId, long score) {
    }

    /** Immutable counter sample stamped with the decay epoch it belongs to. */
    private record Sample(long epoch, long count) {

        long decayedTo(long currentEpoch) {
            long elapsed = currentEpoch - epoch;
            if (elapsed <= 0L) {
                return count;
            }
            // 2^63 halvings always round to zero; avoid a JLS shift overflow.
            return elapsed >= Long.SIZE ? 0L : count >>> (int) elapsed;
        }
    }
}
