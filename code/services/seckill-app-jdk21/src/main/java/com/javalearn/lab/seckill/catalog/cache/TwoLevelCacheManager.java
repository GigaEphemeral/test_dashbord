package com.javalearn.lab.seckill.catalog.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.cache.L1Switch;
import com.javalearn.lab.seckill.lab.toggle.LabToggle;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Cache manager for the two-level cache. {@code product} is created eagerly so
 * its counters are registered at startup; any future cache name is created on
 * first use with the same Caffeine + Redis layering.
 *
 * <p>The {@code cache} lab toggle is evaluated per {@link #getCache(String)}
 * call: while off, a {@link NoOpCache} is returned so {@code @Cacheable}
 * neither reads L1/L2 nor writes them; turning it back on returns the original
 * two-level cache (whose existing entries remain valid) without a restart.</p>
 */
public final class TwoLevelCacheManager implements CacheManager {

    public static final String PRODUCT_CACHE = "product";

    private final ConcurrentMap<String, TwoLevelCache> caches = new ConcurrentHashMap<>();
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final MeterRegistry meterRegistry;
    private final LabToggleService toggles;
    private final TwoLevelCacheSettings settings;
    private final L1Switch l1Switch;

    public TwoLevelCacheManager(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                LabToggleService toggles) {
        this(redisTemplate, objectMapper, meterRegistry, toggles,
                TwoLevelCacheSettings.defaults());
    }

    public TwoLevelCacheManager(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                LabToggleService toggles,
                                TwoLevelCacheSettings settings) {
        this(redisTemplate, objectMapper, meterRegistry, toggles, settings, new L1Switch());
    }

    public TwoLevelCacheManager(StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper,
                                MeterRegistry meterRegistry,
                                LabToggleService toggles,
                                TwoLevelCacheSettings settings,
                                L1Switch l1Switch) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.toggles = Objects.requireNonNull(toggles, "toggles");
        this.settings = Objects.requireNonNull(settings, "settings");
        this.l1Switch = Objects.requireNonNull(l1Switch, "l1Switch");
    }

    @Override
    public Cache getCache(String name) {
        if (!toggles.isEnabled(LabToggle.CACHE)) {
            return new NoOpCache(name);
        }
        TwoLevelCache cache = caches.computeIfAbsent(name,
                cacheName -> new TwoLevelCache(cacheName, valueType(cacheName),
                        redisTemplate, objectMapper, meterRegistry, settings));
        // B3-01: a cache created while the switch is off starts disabled and
        // therefore empty. This is also the catch-up path for a cache created
        // after setL1Enabled iterated the registry.
        if (!l1Switch.isEnabled() && cache.isL1Enabled()) {
            cache.setL1Enabled(false);
        }
        return cache;
    }

    @Override
    public Collection<String> getCacheNames() {
        return Collections.unmodifiableSet(caches.keySet());
    }

    /** Whether this JVM's Caffeine L1 currently participates. */
    public boolean isL1Enabled() {
        return l1Switch.isEnabled();
    }

    /**
     * B3-01: applies the per-JVM L1 switch to every managed cache.
     *
     * <p>Setting the current value is idempotent and clears nothing. On a real
     * transition each cache clears its local entries (both disable and enable
     * start from an empty L1) while L2/Redis is left untouched.</p>
     *
     * @return the total number of L1 entries cleared by the transition
     */
    public long setL1Enabled(boolean enabled) {
        boolean previous = l1Switch.setEnabled(enabled);
        if (previous == enabled) {
            return 0L;
        }
        long cleared = 0L;
        for (TwoLevelCache cache : caches.values()) {
            cleared += cache.setL1Enabled(enabled);
        }
        return cleared;
    }

    /** Managed cache names with their current (exact) L1 entry counts, name-sorted. */
    public List<CacheStatus> cacheStatuses() {
        return caches.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> new CacheStatus(entry.getKey(), entry.getValue().l1Size()))
                .toList();
    }

    /** One managed cache as exposed by {@code GET /lab/cache/l1}. */
    public record CacheStatus(String name, long size) {
    }

    /**
     * L2 Redis key prefix of the product cache for THIS manager's bound
     * settings, for the lab seeder and state reader that must write/inspect
     * exactly the keys this cache reads. The layout itself lives in
     * {@link TwoLevelCacheSettings#l2PrefixFor(String)}
     * ({@code app.lab.cache.l2.key-prefix}), so nothing here repeats the format.
     */
    public String productL2KeyPrefix() {
        return settings.l2PrefixFor(PRODUCT_CACHE);
    }

    /** The bound settings this manager hands to every cache it creates. */
    public TwoLevelCacheSettings settings() {
        return settings;
    }

    private static Class<?> valueType(String cacheName) {
        if (PRODUCT_CACHE.equals(cacheName)) {
            return ProductResponse.class;
        }
        return Object.class;
    }
}
