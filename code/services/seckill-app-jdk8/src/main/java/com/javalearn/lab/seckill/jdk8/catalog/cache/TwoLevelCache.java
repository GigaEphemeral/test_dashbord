package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.FunctionCounter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.support.SimpleValueWrapper;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * A true two-level cache behind the Spring Cache abstraction.
 *
 * <p>L1 is an in-process Caffeine cache whose capacity and write/refresh
 * windows come from {@link TwoLevelCacheSettings} (design 8.4: 10k entries,
 * refresh from L2 after 10s, 60s write expiry as the final bound). L2 is Redis
 * with JSON values, whose write TTL and key layout also come from
 * {@link TwoLevelCacheSettings} ({@code app.lab.cache.l2.*}; historically
 * 5 minutes and {@code cache:<cacheName>:<key>}). Reads go L1 -> L2 -> (miss)
 * source; writes go to both layers.</p>
 *
 * <p>A read that hits L1 doubles as Caffeine's refresh trigger: once the entry
 * is older than {@code refreshAfterWrite}, Caffeine asynchronously reloads it
 * from L2 (the shared source of truth for the lab's L1-warm-up story) while the
 * caller keeps the current value. Redis is best-effort: any Redis failure is
 * logged, counted as {@code cache_l2_errors}, and treated as an L2 miss so
 * callers still fall through to the database instead of receiving a 500. A
 * failed background refresh keeps the old L1 value until {@code expireAfterWrite}
 * removes it; a genuine L2 miss drops the L1 entry so the next request rebuilds
 * it through the normal path.</p>
 *
 * <p>Counters are created once per cache instance (never per request):
 * {@code cache_l1_requests{result}}, {@code cache_l2_requests{result}},
 * {@code cache_db_fallbacks{reason=miss}} and {@code cache_l2_errors}. The
 * {@code cache_l1_hit_ratio} gauge is derived from the L1 hit/miss counters.
 * Since B0-03 the Caffeine-native {@code recordStats()} snapshot is exported as
 * a second, source-labelled view ({@code cache_l1_caffeine_requests},
 * {@code cache_l1_caffeine_hit_ratio}, {@code cache_l1_caffeine_evictions}) plus
 * the {@code cache_l1_estimated_size} gauge, gated by
 * {@link TwoLevelCacheSettings#micrometerStatsEnabled()}.</p>
 *
 * <p>B3-01 adds an independent per-JVM L1 switch
 * ({@link #setL1Enabled(boolean)} / {@link #isL1Enabled()}): while off, reads
 * and writes bypass Caffeine (reads still go L2 -> source, writes still fill
 * L2), the L1 entries are cleared, and neither {@code cache_l1_requests} nor
 * Caffeine's own stats move. The {@code cache_l1_enabled{cache}} gauge stays
 * 1/0 so a dashboard can tell "switched off" from a real 0% hit ratio.</p>
 */
public final class TwoLevelCache implements org.springframework.cache.Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    private final String name;
    private final String redisKeyPrefix;
    private final Duration l2Ttl;
    private final long l2ClearScanCount;
    private final Class<?> valueType;
    private final Cache<Object, Object> l1;
    /**
     * B3-01: per-cache L1 bypass switch. Reads and writes consult this flag
     * before touching Caffeine; disabling clears the local entries and leaves
     * L2 alone. An {@link AtomicBoolean} keeps the hot read path lock-free.
     */
    private final AtomicBoolean l1Enabled = new AtomicBoolean(true);
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    private final Counter l1Hits;
    private final Counter l1Misses;
    private final Counter l2Hits;
    private final Counter l2Misses;
    private final Counter dbFallbacks;
    private final Counter l2Errors;

    public TwoLevelCache(String name,
                         Class<?> valueType,
                         StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry) {
        this(name, valueType, redisTemplate, objectMapper, meterRegistry,
                TwoLevelCacheSettings.defaults());
    }

    public TwoLevelCache(String name,
                         Class<?> valueType,
                         StringRedisTemplate redisTemplate,
                         ObjectMapper objectMapper,
                         MeterRegistry meterRegistry,
                         TwoLevelCacheSettings settings) {
        this.name = name;
        this.redisKeyPrefix = settings.l2PrefixFor(name);
        this.l2Ttl = settings.l2Ttl();
        this.l2ClearScanCount = settings.l2ClearScanCount();
        this.valueType = valueType;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        // B4-01: the switch starts at the configured value (env
        // LAB_CACHE_L1_ENABLED). A JVM started with L1 off therefore has an
        // empty Caffeine layer from the first request, exactly like a runtime
        // disable, while POST /lab/cache/l1 can still flip it afterwards.
        this.l1Enabled.set(settings.l1Enabled());
        Caffeine<Object, Object> builder = Caffeine.newBuilder()
                .maximumSize(settings.maximumSize())
                .refreshAfterWrite(settings.refreshAfterWrite())
                .expireAfterWrite(settings.expireAfterWrite());
        if (!settings.expireAfterAccess().isZero()) {
            builder = builder.expireAfterAccess(settings.expireAfterAccess());
        }
        this.l1 = builder
                .recordStats()
                .build(new CacheLoader<Object, Object>() {
                    @Override
                    public Object load(Object key) {
                        // While L1 is off this loader is never reached by the
                        // application path, but a concurrent disable can still
                        // race with a refresh; returning null avoids
                        // repopulating an intentionally emptied L1.
                        return l1Enabled.get() ? readL2(key) : null;
                    }

                    @Override
                    public Object reload(Object key, Object oldValue) {
                        if (!l1Enabled.get()) {
                            return null;
                        }
                        Object value = reloadFromL2(key);
                        // A disable that landed while the L2 read was in flight
                        // must not reinsert the entry it just cleared.
                        return l1Enabled.get() ? value : null;
                    }
                });
        this.l1Hits = Counter.builder("cache_l1_requests")
                .tag("cache", name).tag("result", "hit")
                .register(meterRegistry);
        this.l1Misses = Counter.builder("cache_l1_requests")
                .tag("cache", name).tag("result", "miss")
                .register(meterRegistry);
        this.l2Hits = Counter.builder("cache_l2_requests")
                .tag("cache", name).tag("result", "hit")
                .register(meterRegistry);
        this.l2Misses = Counter.builder("cache_l2_requests")
                .tag("cache", name).tag("result", "miss")
                .register(meterRegistry);
        this.dbFallbacks = Counter.builder("cache_db_fallbacks")
                .tag("cache", name).tag("reason", "miss")
                .register(meterRegistry);
        this.l2Errors = Counter.builder("cache_l2_errors")
                .tag("cache", name)
                .register(meterRegistry);
        Gauge.builder("cache_l1_hit_ratio", this, TwoLevelCache::l1HitRatio)
                .description("L1 hit ratio derived from cache_l1_requests")
                .tag("cache", name)
                .register(meterRegistry);
        // B3-01: 1 = L1 participates, 0 = L1 bypassed and cleared. This gauge
        // is what lets a dashboard tell "switched off" apart from "0% hit
        // ratio"; cache_l1_requests is intentionally not incremented while off.
        Gauge.builder("cache_l1_enabled", this, cache -> cache.isL1Enabled() ? 1.0D : 0.0D)
                .description("1 when the Caffeine L1 participates, 0 when it is bypassed "
                        + "and its entries have been cleared")
                .tag("cache", name)
                .register(meterRegistry);
        if (settings.micrometerStatsEnabled()) {
            registerCaffeineStats(meterRegistry);
        }
    }

    /**
     * B0-03: export the Caffeine-native L1 statistics (the {@code recordStats()}
     * snapshot plus {@code estimatedSize()}) as real Micrometer series, so the
     * dashboard no longer depends on the in-app aggregation only.
     *
     * <ul>
     *   <li>{@code cache_l1_caffeine_requests} FunctionCounters tagged
     *       {@code result=hit|miss} — exported as
     *       {@code cache_l1_caffeine_requests_total}. Deliberately NOT named like
     *       {@code cache_l1_requests}: one Spring-cache hit performs two Caffeine
     *       reads ({@code getIfPresent} + refresh trigger), so the Caffeine stats
     *       are a different, source-labelled view of the same L1.</li>
     *   <li>{@code cache_l1_caffeine_hit_ratio} gauge — Caffeine's own
     *       {@code stats().hitRate()} (0..1) of the cumulative snapshot.</li>
     *   <li>{@code cache_l1_caffeine_evictions} FunctionCounter — cumulative
     *       eviction count (size, expiry and refresh removals all included).</li>
     *   <li>{@code cache_l1_estimated_size} gauge — {@code estimatedSize()},
     *       "estimated" is in the metric name AND the help text: Caffeine
     *       maintains the size lazily, so it is never an exact count and must
     *       not be read as one.</li>
     * </ul>
     *
     * <p>Only the low-cardinality {@code cache} tag is added; the per-instance
     * dimension comes from the scrape target (Prometheus {@code instance} /
     * relabelled {@code exported_instance}), never from a Java-side tag. The
     * FunctionCounters read the monotonically growing cumulative stats, which is
     * exactly what a counter binding requires. When the lab {@code cache} toggle
     * is off no TwoLevelCache exists at all, so these series are absent — that
     * absence IS the "L1 not applicable" signal; a fake zero is never published.</p>
     */
    private void registerCaffeineStats(MeterRegistry meterRegistry) {
        // The state object is the cache itself: stats() must be re-evaluated on
        // every read (a captured CacheStats snapshot would freeze at zero).
        FunctionCounter.builder("cache_l1_caffeine_requests", l1,
                cache -> cache.stats().hitCount())
                .description("L1 requests counted by Caffeine's own stats (source=caffeine; "
                        + "cache_l1_requests counts the application path)")
                .tag("cache", name).tag("result", "hit")
                .register(meterRegistry);
        FunctionCounter.builder("cache_l1_caffeine_requests", l1,
                cache -> cache.stats().missCount())
                .description("L1 requests counted by Caffeine's own stats (source=caffeine; "
                        + "cache_l1_requests counts the application path)")
                .tag("cache", name).tag("result", "miss")
                .register(meterRegistry);
        Gauge.builder("cache_l1_caffeine_hit_ratio", l1,
                cache -> cache.stats().hitRate())
                .description("L1 hit ratio (0..1) from Caffeine's own cumulative stats")
                .tag("cache", name)
                .register(meterRegistry);
        FunctionCounter.builder("cache_l1_caffeine_evictions", l1,
                cache -> cache.stats().evictionCount())
                .description("Cumulative L1 evictions reported by Caffeine stats")
                .tag("cache", name)
                .register(meterRegistry);
        Gauge.builder("cache_l1_estimated_size", l1,
                cache -> cache.estimatedSize())
                .description("Estimated (not exact!) current number of L1 entries "
                        + "from Caffeine estimatedSize; absence of this series means "
                        + "L1 is disabled, never a real zero")
                .tag("cache", name)
                .register(meterRegistry);
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    /** Whether the Caffeine L1 currently participates in reads and writes. */
    public boolean isL1Enabled() {
        return l1Enabled.get();
    }

    /**
     * B3-01: toggles the local Caffeine layer.
     *
     * <p>Any actual state transition clears L1, so disabling drops every local
     * entry immediately and enabling starts from an empty L1 (no entry that was
     * written while the switch was off can resurface). Repeating the current
     * value is a no-op and clears nothing. L2 is never touched here.</p>
     *
     * @return the number of L1 entries removed by this transition, 0 when the
     *         value did not change
     */
    public long setL1Enabled(boolean enabled) {
        boolean previous = l1Enabled.getAndSet(enabled);
        if (previous == enabled) {
            return 0L;
        }
        return clearL1();
    }

    /**
     * Removes every L1 entry without touching Redis, returning the exact
     * number of entries present before the clear.
     */
    public long clearL1() {
        long size = l1.asMap().size();
        l1.invalidateAll();
        l1.cleanUp();
        return size;
    }

    /** Exact current number of local Caffeine entries (0 while L1 is off). */
    public long l1Size() {
        return l1.asMap().size();
    }

    @Override
    public ValueWrapper get(Object key) {
        if (!l1Enabled.get()) {
            // B3-01 off semantics: bypass L1 entirely. The read goes straight to
            // L2 and then source; neither an L1 hit nor an L1 miss is counted,
            // and an L2 hit is not copied back into the disabled L1.
            Object l2Value = readL2(key);
            if (l2Value != null) {
                l2Hits.increment();
                return new SimpleValueWrapper(l2Value);
            }
            dbFallbacks.increment();
            return null;
        }
        Object l1Value = l1.getIfPresent(key);
        if (l1Value != null) {
            l1Hits.increment();
            // Caffeine only evaluates refreshAfterWrite on a get(key, mapping)
            // call; getIfPresent never refreshes. On a hit this returns the
            // current value immediately and schedules the asynchronous L2
            // reload only when the entry is older than refreshAfterWrite.
            l1.get(key, this::readL2);
            return new SimpleValueWrapper(l1Value);
        }
        l1Misses.increment();

        Object l2Value = readL2(key);
        if (l2Value != null) {
            if (l1Enabled.get()) {
                l1.put(key, l2Value);
                if (!l1Enabled.get()) {
                    l1.invalidate(key);
                }
            }
            l2Hits.increment();
            return new SimpleValueWrapper(l2Value);
        }
        dbFallbacks.increment();
        return null;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Class<T> type) {
        ValueWrapper wrapper = get(key);
        if (wrapper == null) {
            return null;
        }
        Object value = wrapper.get();
        if (value != null && type != null && !type.isInstance(value)) {
            throw new IllegalStateException("Cached value for key '" + key + "' in cache '" + name
                    + "' is not of type [" + type.getName() + "]");
        }
        return (T) value;
    }

    @Override
    @SuppressWarnings("unchecked")
    public <T> T get(Object key, Callable<T> valueLoader) {
        ValueWrapper wrapper = get(key);
        if (wrapper != null) {
            return (T) wrapper.get();
        }
        try {
            T value = valueLoader.call();
            put(key, value);
            return value;
        } catch (Exception ex) {
            throw new ValueRetrievalException(key, valueLoader, ex);
        }
    }

    @Override
    public void put(Object key, Object value) {
        if (value == null) {
            return;
        }
        if (l1Enabled.get()) {
            l1.put(key, value);
            if (!l1Enabled.get()) {
                // A concurrent disable cleared the cache after this write
                // started; do not leave the just-inserted entry behind.
                l1.invalidate(key);
            }
        }
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(redisKey(key), json, l2Ttl);
        } catch (JsonProcessingException | RuntimeException ex) {
            countL2Error("write", key, ex);
        }
    }

    @Override
    public void evict(Object key) {
        if (l1Enabled.get()) {
            l1.invalidate(key);
        }
        try {
            redisTemplate.delete(redisKey(key));
        } catch (RuntimeException ex) {
            countL2Error("evict", key, ex);
        }
    }

    @Override
    public void clear() {
        l1.invalidateAll();
        List<String> keys = new ArrayList<>();
        try (Cursor<String> cursor = redisTemplate.scan(
                ScanOptions.scanOptions().match(redisKeyPrefix + "*")
                        .count(l2ClearScanCount).build())) {
            while (cursor.hasNext()) {
                keys.add(cursor.next());
            }
        } catch (RuntimeException ex) {
            countL2Error("clear", "*", ex);
            return;
        }
        if (keys.isEmpty()) {
            return;
        }
        try {
            redisTemplate.delete(keys);
        } catch (RuntimeException ex) {
            countL2Error("clear", keys.size() + " keys", ex);
        }
    }

    private Object readL2(Object key) {
        return readL2Result(key).value();
    }

    /**
     * The refresh path must distinguish "the key is genuinely absent from L2"
     * (drop the L1 entry; the next request rebuilds it) from "Redis answered
     * with an error" (keep serving the old L1 value until expireAfterWrite).
     * Throwing is Caffeine's documented way to retain the current mapping on a
     * failed refresh; returning null removes it.
     */
    private Object reloadFromL2(Object key) {
        L2Read read = readL2Result(key);
        if (read.failed()) {
            throw new IllegalStateException(
                    "Redis L2 refresh failed for cache '" + name + "' key '" + key + "'");
        }
        if (read.value() != null) {
            // A background refresh that reaches L2 is still an L2 hit; the miss
            // side is already counted inside readL2Result.
            l2Hits.increment();
        }
        return read.value();
    }

    private L2Read readL2Result(Object key) {
        String json;
        try {
            json = redisTemplate.opsForValue().get(redisKey(key));
        } catch (RuntimeException ex) {
            l2Misses.increment();
            countL2Error("read", key, ex);
            return new L2Read(null, true);
        }
        if (json == null) {
            l2Misses.increment();
            return new L2Read(null, false);
        }
        try {
            return new L2Read(objectMapper.readValue(json, valueType), false);
        } catch (JsonProcessingException | RuntimeException ex) {
            // A corrupt entry must not break the request; drop it so the next
            // write can heal the key.
            l2Misses.increment();
            countL2Error("deserialize", key, ex);
            try {
                redisTemplate.delete(redisKey(key));
            } catch (RuntimeException deleteFailure) {
                countL2Error("delete-corrupt", key, deleteFailure);
            }
            return new L2Read(null, false);
        }
    }

    private double l1HitRatio() {
        double hits = l1Hits.count();
        double requests = hits + l1Misses.count();
        return requests == 0D ? 0D : hits / requests;
    }

    /** Result of one L2 read; {@code failed} means Redis answered with an error. */
    private static final class L2Read {

        private final Object value;
        private final boolean failed;

        private L2Read(Object value, boolean failed) {
            this.value = value;
            this.failed = failed;
        }

        private Object value() {
            return value;
        }

        private boolean failed() {
            return failed;
        }
    }

    /**
     * The single source of truth for the L2 key layout is
     * {@link TwoLevelCacheSettings#l2PrefixFor(String)} (bound from
     * {@code app.lab.cache.l2.key-prefix}). The lab cache seeder, the state
     * reader, the preheat service and the TTL-jitter adjuster resolve it
     * through that settings bean instead of repeating the format, so they cannot
     * drift away from this cache when the prefix is reconfigured.
     */
    private String redisKey(Object key) {
        return redisKeyPrefix + key;
    }

    private void countL2Error(String operation, Object key, Exception ex) {
        l2Errors.increment();
        log.warn("Redis L2 {} failed for cache '{}' key '{}'; continuing with L1/source: {}",
                operation, name, key, ex.toString());
    }

    /** The effective L2 key prefix of this cache, from the bound settings. */
    String redisKeyPrefix() {
        return redisKeyPrefix;
    }

    /** The effective L2 write TTL, from the bound settings. */
    Duration l2Ttl() {
        return l2Ttl;
    }

    /** The effective SCAN COUNT hint used by {@link #clear()}. */
    long l2ClearScanCount() {
        return l2ClearScanCount;
    }
}
