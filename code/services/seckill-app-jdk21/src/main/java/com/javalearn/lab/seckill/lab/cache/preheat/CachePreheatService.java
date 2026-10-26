package com.javalearn.lab.seckill.lab.cache.preheat;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCache;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheManager;
import com.javalearn.lab.seckill.lab.api.CachePreheatStateResponse;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Reads the shared hot list and warms the local Caffeine L1 from the shared
 * Redis L2 (design 8.4, M4-3).
 *
 * <p>The source is deliberately L2, never the database: after an instance
 * restart Redis still holds the hot products, so the new instance can refill
 * L1 without touching MySQL. L2 misses are counted ({@code missing}) and left
 * for the next real request to rebuild. Entries are written to the Caffeine
 * cache only — preheat must never rewrite {@code cache:product:<id>} TTLs,
 * otherwise it would silently destroy the M4-2 avalanche experiment's
 * identical-TTL key set.</p>
 *
 * <p>Runs are single-flight per instance ({@link AtomicBoolean}): the startup,
 * periodic and manual triggers cannot overlap, and a manual call made while a
 * run is in flight returns a {@code busy} result instead of queueing. Every run
 * is rate-limited by {@code load.chunk-size} L2 reads followed by
 * {@code load.chunk-pause-ms}; the periodic run is incremental — an L1 entry
 * younger than {@code l1.refresh-after-write-seconds} is skipped without a
 * Redis read.</p>
 *
 * <p>Redis is best-effort: a failed hot-list read or L2 read ends the run with
 * {@code outcome=failed} and a failure metric, never an exception on the
 * request thread (the manual endpoint reports the result in its JSON).</p>
 */
public final class CachePreheatService {

    private static final Logger log = LoggerFactory.getLogger(CachePreheatService.class);

    static final String CACHE_TAG = "product";
    static final String FAILURE_HOT_LIST = "hot-list";
    static final String FAILURE_LOAD = "load";
    private static final int MAX_ERROR_LENGTH = 200;

    private final LabCachePreheatProperties properties;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final CacheManager cacheManager;
    private final MeterRegistry meterRegistry;
    private final HotKeyTracker tracker;
    private final Clock clock;

    private final Map<PreheatTrigger, Counter> runs = new EnumMap<>(PreheatTrigger.class);
    private final Map<PreheatTrigger, Counter> loaded = new EnumMap<>(PreheatTrigger.class);
    private final Map<PreheatTrigger, Timer> durations = new EnumMap<>(PreheatTrigger.class);
    private final Map<String, Counter> failures = new HashMap<>();

    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicReference<PreheatRun> lastRun = new AtomicReference<>();

    public CachePreheatService(LabCachePreheatProperties properties,
                               StringRedisTemplate redisTemplate,
                               ObjectMapper objectMapper,
                               CacheManager cacheManager,
                               MeterRegistry meterRegistry,
                               HotKeyTracker tracker,
                               Clock clock) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.objectMapper = Objects.requireNonNull(objectMapper, "objectMapper");
        this.cacheManager = Objects.requireNonNull(cacheManager, "cacheManager");
        this.meterRegistry = Objects.requireNonNull(meterRegistry, "meterRegistry");
        this.tracker = Objects.requireNonNull(tracker, "tracker");
        this.clock = Objects.requireNonNull(clock, "clock");

        for (PreheatTrigger trigger : PreheatTrigger.values()) {
            runs.put(trigger, Counter.builder("cache_preheat_runs")
                    .description("Preheat runs by trigger")
                    .tag("cache", CACHE_TAG).tag("trigger", trigger.key())
                    .register(meterRegistry));
            loaded.put(trigger, Counter.builder("cache_preheat_loaded")
                    .description("Products actually written into L1 by preheat")
                    .tag("cache", CACHE_TAG).tag("trigger", trigger.key())
                    .register(meterRegistry));
            durations.put(trigger, Timer.builder("cache_preheat_duration")
                    .description("Wall-clock duration of one preheat run")
                    .tag("cache", CACHE_TAG).tag("trigger", trigger.key())
                    .register(meterRegistry));
        }
        failures.put(FAILURE_HOT_LIST, failureCounter(FAILURE_HOT_LIST, meterRegistry));
        failures.put(FAILURE_LOAD, failureCounter(FAILURE_LOAD, meterRegistry));
    }

    /**
     * Runs one preheat attempt and records it as the last run. A concurrent
     * attempt returns a {@code busy} result without touching metrics or state.
     */
    public PreheatRun run(PreheatTrigger trigger) {
        Objects.requireNonNull(trigger, "trigger");
        if (!running.compareAndSet(false, true)) {
            return new PreheatRun(trigger, clock.instant(), 0L, 0, 0, 0, 0, 0,
                    PreheatRun.Outcome.BUSY, null);
        }
        Timer.Sample sample = Timer.start(meterRegistry);
        runs.get(trigger).increment();
        long startNanos = System.nanoTime();
        Instant startedAt = clock.instant();
        RunCounters counters = new RunCounters();
        PreheatRun.Outcome outcome;
        try {
            outcome = execute(trigger, counters);
        } catch (RuntimeException ex) {
            counters.error = shortError(ex);
            outcome = PreheatRun.Outcome.FAILED;
            log.warn("Cache preheat ({}) failed unexpectedly: {}", trigger.key(), ex.toString());
        } finally {
            running.set(false);
        }
        long durationMs = Math.max(0L, (System.nanoTime() - startNanos) / 1_000_000L);
        sample.stop(durations.get(trigger));

        PreheatRun run = new PreheatRun(trigger, startedAt, durationMs, counters.requested,
                counters.loaded, counters.skippedFresh, counters.missing, counters.failed,
                outcome, counters.error);
        lastRun.set(run);
        log.info("Cache preheat ({}) {}: requested={}, loaded={}, fresh={}, missing={},"
                        + " failed={}, durationMs={}",
                trigger.key(), outcome.key(), counters.requested, counters.loaded,
                counters.skippedFresh, counters.missing, counters.failed, durationMs);
        return run;
    }

    /** The console snapshot for {@code GET /lab/cache/preheat}. */
    public CachePreheatStateResponse state() {
        org.springframework.cache.Cache cache =
                cacheManager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
        PreheatRun last = lastRun.get();
        return new CachePreheatStateResponse(
                properties.isEnabled(),
                cache instanceof TwoLevelCache,
                properties.getHotListKey(),
                properties.getTopN(),
                tracker.refreshHotListSize(),
                tracker.trackedKeys(),
                running.get(),
                last == null ? null : new CachePreheatStateResponse.LastRun(
                        last.trigger().key(),
                        last.startedAt().toString(),
                        last.outcome().key(),
                        last.durationMs(),
                        last.requested(),
                        last.loaded(),
                        last.skippedFresh(),
                        last.missing(),
                        last.failed(),
                        last.error()));
    }

    private PreheatRun.Outcome execute(PreheatTrigger trigger, RunCounters counters) {
        org.springframework.cache.Cache springCache =
                cacheManager.getCache(TwoLevelCacheManager.PRODUCT_CACHE);
        if (!(springCache instanceof TwoLevelCache)) {
            log.info("Cache preheat skipped: the cache lab toggle is off, so there is no L1");
            return PreheatRun.Outcome.CACHE_DISABLED;
        }
        Cache<Object, Object> l1 = l1Of(springCache);
        if (l1 == null) {
            return PreheatRun.Outcome.CACHE_DISABLED;
        }

        Set<String> members;
        try {
            members = redisTemplate.opsForZSet().reverseRange(
                    properties.getHotListKey(), 0L, properties.getTopN() - 1L);
        } catch (RuntimeException ex) {
            failures.get(FAILURE_HOT_LIST).increment();
            counters.error = shortError(ex);
            log.warn("Cache preheat could not read hot list '{}': {}",
                    properties.getHotListKey(), ex.toString());
            return PreheatRun.Outcome.FAILED;
        }
        counters.requested = members == null ? 0 : members.size();
        if (members == null || members.isEmpty()) {
            return PreheatRun.Outcome.SUCCESS;
        }

        long chunkSize = properties.getLoad().getChunkSize();
        long pauseMs = properties.getLoad().getChunkPauseMs();
        int processed = 0;
        for (String member : members) {
            Long productId = parseId(member);
            if (productId == null) {
                counters.failed++;
                continue;
            }
            if (isFreshInL1(l1, productId)) {
                counters.skippedFresh++;
                continue;
            }
            String json;
            try {
                json = redisTemplate.opsForValue().get(l2Key(productId));
            } catch (RuntimeException ex) {
                failures.get(FAILURE_LOAD).increment();
                counters.error = shortError(ex);
                log.warn("Cache preheat L2 read failed for product {}: {}",
                        productId, ex.toString());
                return PreheatRun.Outcome.FAILED;
            }
            if (json == null) {
                counters.missing++;
                continue;
            }
            ProductResponse product;
            try {
                product = objectMapper.readValue(json, ProductResponse.class);
            } catch (JsonProcessingException ex) {
                failures.get(FAILURE_LOAD).increment();
                counters.failed++;
                log.warn("Cache preheat skipped corrupt L2 JSON for product {}: {}",
                        productId, ex.toString());
                continue;
            }
            // L1 only: see the class javadoc, L2 TTLs must stay untouched.
            l1.put(productId, product);
            counters.loaded++;
            loaded.get(trigger).increment();
            processed++;
            if (processed % chunkSize == 0L && pauseMs > 0L) {
                try {
                    Thread.sleep(pauseMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    counters.error = "interrupted";
                    return PreheatRun.Outcome.FAILED;
                }
            }
        }
        return PreheatRun.Outcome.SUCCESS;
    }

    /**
     * "Already fresh" is a cheap age check against the L1's own write policy:
     * entries younger than refreshAfterWrite will not be refreshed by the next
     * read either, so re-reading them from Redis now would be wasted work.
     */
    private boolean isFreshInL1(Cache<Object, Object> l1, long productId) {
        Duration refreshAfter = Duration.ofSeconds(
                properties.getL1().getRefreshAfterWriteSeconds());
        return l1.policy().expireAfterWrite()
                .flatMap(policy -> policy.ageOf(productId))
                .map(age -> age.compareTo(refreshAfter) < 0)
                .orElse(false);
    }

    @SuppressWarnings("unchecked")
    private static Cache<Object, Object> l1Of(org.springframework.cache.Cache springCache) {
        Object nativeCache = springCache.getNativeCache();
        return nativeCache instanceof Cache<?, ?> caffeine
                ? (Cache<Object, Object>) caffeine
                : null;
    }

    private static Long parseId(String member) {
        try {
            return Long.valueOf(member);
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    private static String l2Key(long productId) {
        return TwoLevelCacheManager.productL2KeyPrefix() + productId;
    }

    private static Counter failureCounter(String operation, MeterRegistry meterRegistry) {
        return Counter.builder("cache_preheat_failures")
                .description("Preheat failures by operation")
                .tag("cache", CACHE_TAG).tag("operation", operation)
                .register(meterRegistry);
    }

    private static String shortError(RuntimeException ex) {
        String text = ex.getClass().getSimpleName()
                + (ex.getMessage() == null ? "" : ": " + ex.getMessage());
        return text.length() <= MAX_ERROR_LENGTH ? text : text.substring(0, MAX_ERROR_LENGTH);
    }

    /** Mutable counters of one run; only touched by the owning thread. */
    private static final class RunCounters {
        private int requested;
        private int loaded;
        private int skippedFresh;
        private int missing;
        private int failed;
        private String error;
    }
}
