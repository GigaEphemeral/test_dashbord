package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.CacheLoader;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.micrometer.core.instrument.Counter;
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

/**
 * A true two-level cache behind the Spring Cache abstraction.
 *
 * <p>L1 is an in-process Caffeine cache whose capacity and write/refresh
 * windows come from {@link TwoLevelCacheSettings} (design 8.4: 10k entries,
 * refresh from L2 after 10s, 60s write expiry as the final bound). L2 is Redis
 * with JSON values and a 5 minute TTL, keyed by {@code cache:<cacheName>:<key>}.
 * Reads go L1 -> L2 -> (miss) source; writes go to both layers.</p>
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
 * {@code cache_l1_hit_ratio} gauge is derived from the L1 hit/miss counters.</p>
 */
public final class TwoLevelCache implements org.springframework.cache.Cache {

    private static final Logger log = LoggerFactory.getLogger(TwoLevelCache.class);

    static final Duration L2_TTL = Duration.ofMinutes(5);
    static final String KEY_PREFIX = "cache:";

    private final String name;
    private final String redisKeyPrefix;
    private final Class<?> valueType;
    private final Cache<Object, Object> l1;
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
        this.redisKeyPrefix = l2KeyPrefix(name);
        this.valueType = valueType;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.l1 = Caffeine.newBuilder()
                .maximumSize(settings.maximumSize())
                .refreshAfterWrite(settings.refreshAfterWrite())
                .expireAfterWrite(settings.expireAfterWrite())
                .recordStats()
                .build(new CacheLoader<Object, Object>() {
                    @Override
                    public Object load(Object key) {
                        return readL2(key);
                    }

                    @Override
                    public Object reload(Object key, Object oldValue) {
                        return reloadFromL2(key);
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
    }

    @Override
    public String getName() {
        return name;
    }

    @Override
    public Object getNativeCache() {
        return l1;
    }

    @Override
    public ValueWrapper get(Object key) {
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
            l1.put(key, l2Value);
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
        l1.put(key, value);
        try {
            String json = objectMapper.writeValueAsString(value);
            redisTemplate.opsForValue().set(redisKey(key), json, L2_TTL);
        } catch (JsonProcessingException | RuntimeException ex) {
            countL2Error("write", key, ex);
        }
    }

    @Override
    public void evict(Object key) {
        l1.invalidate(key);
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
                ScanOptions.scanOptions().match(redisKeyPrefix + "*").count(500).build())) {
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

    private record L2Read(Object value, boolean failed) {
    }

    private String redisKey(Object key) {
        return redisKeyPrefix + key;
    }

    /**
     * The single source of truth for the L2 key layout. The lab cache seeder and
     * state reader go through {@link TwoLevelCacheManager#productL2KeyPrefix()}
     * instead of repeating the format, so they cannot drift away from this cache.
     */
    static String l2KeyPrefix(String cacheName) {
        return KEY_PREFIX + cacheName + ":";
    }

    private void countL2Error(String operation, Object key, Exception ex) {
        l2Errors.increment();
        log.warn("Redis L2 {} failed for cache '{}' key '{}'; continuing with L1/source: {}",
                operation, name, key, ex.toString());
    }

    // Visible for diagnostics and tests; never used to register meters.
    String redisKeyPrefix() {
        return redisKeyPrefix;
    }

    Duration l2Ttl() {
        return L2_TTL;
    }
}
