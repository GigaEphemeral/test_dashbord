package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

/**
 * Cache-breakdown (击穿) stage of the product read path: it owns the two
 * runtime-switchable hot-key strategies of design 8.4 and delegates every other
 * mode to the unchanged M4-1 penetration guard.
 *
 * <p>Like {@link ProductPenetrationDefense} this only runs on an L1+L2 miss —
 * which is exactly the moment a hot key has just expired and a thousand
 * concurrent readers would otherwise stampede the database. The contract to the
 * caller never changes: "absent" is {@link Optional#empty()} and the caller
 * still raises the standard 404; the guard only decides who asks the database.</p>
 *
 * <ul>
 *   <li>{@code mutex} — one request takes a Redisson per-key lock and rebuilds;
 *       the others wait up to {@code wait-ms}, then double-check L2 and the
 *       stale store before touching the database. Wait timeout falls back to
 *       stale-serve or a direct DB read per {@code on-lock-timeout}. Cost the
 *       demo shows: waiting threads raise P99.</li>
 *   <li>{@code logical-expire} — the last good value is kept in
 *       {@link LogicalStaleStore} with a logical expiry inside a longer physical
 *       retention. A logically expired value is served immediately while ONE
 *       bounded-async refresh rebuilds it; an in-flight set deduplicates
 *       concurrent refreshes so the async path cannot stampede either. The
 *       refreshed value is ALSO published back into the shared Spring cache,
 *       because the stale serve itself re-caches the old value there.</li>
 * </ul>
 */
public final class ProductBreakdownDefense {

    private static final Logger log = LoggerFactory.getLogger(ProductBreakdownDefense.class);

    /**
     * Re-read of the shared cache for the mutex double-check; the production
     * wiring looks the key up in the Spring cache (L1+L2), tests supply a fake.
     */
    @FunctionalInterface
    public interface CacheProbe {
        Optional<ProductResponse> peek(long id);
    }

    /**
     * Publish path for the async refresh result. The refresh runs outside the
     * {@code @Cacheable} method, so WITHOUT this the freshly rebuilt value would
     * only live in the guard's stale store while the shared cache kept serving
     * the stale value the interceptor cached on the stale serve — "briefly
     * stale" would stretch into the full L2 entry lifetime.
     */
    @FunctionalInterface
    public interface CacheWriter {
        void write(long id, ProductResponse value);
    }

    private final CacheDefenseModeService modeService;
    private final ProductPenetrationDefense penetrationDefense;
    private final RedissonClient redissonClient;
    private final LogicalStaleStore staleStore;
    private final Executor refreshExecutor;
    private final CacheProbe cacheProbe;
    private final CacheWriter cacheWriter;
    private final LabCacheProperties properties;
    private final LongSupplier nowMillis;

    /** Key currently carrying one async refresh; the herd-control of the logical path. */
    private final ConcurrentHashMap<Long, Boolean> refreshing = new ConcurrentHashMap<>();

    private final Counter dbFallbacks;
    private final Counter mutexRebuilds;
    private final Counter mutexDoubleCheckHits;
    private final Counter mutexTimeouts;
    private final Counter mutexStaleServes;
    private final Counter mutexLockErrors;
    private final Counter logicalColds;
    private final Counter logicalFreshServes;
    private final Counter logicalStaleServes;
    private final Counter refreshSubmitted;
    private final Counter refreshDeduped;
    private final Counter refreshSucceeded;
    private final Counter refreshFailed;
    private final Counter refreshRejected;

    public ProductBreakdownDefense(CacheDefenseModeService modeService,
                                   ProductPenetrationDefense penetrationDefense,
                                   RedissonClient redissonClient,
                                   LogicalStaleStore staleStore,
                                   Executor refreshExecutor,
                                   CacheProbe cacheProbe,
                                   CacheWriter cacheWriter,
                                   LabCacheProperties properties,
                                   LongSupplier nowMillis,
                                   MeterRegistry meterRegistry) {
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.penetrationDefense = Objects.requireNonNull(penetrationDefense, "penetrationDefense");
        this.redissonClient = Objects.requireNonNull(redissonClient, "redissonClient");
        this.staleStore = Objects.requireNonNull(staleStore, "staleStore");
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor, "refreshExecutor");
        this.cacheProbe = Objects.requireNonNull(cacheProbe, "cacheProbe");
        this.cacheWriter = Objects.requireNonNull(cacheWriter, "cacheWriter");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");

        // Third value of the existing cache_db_fallbacks family: reason=miss is
        // "L1+L2 had nothing", reason=penetration is the M4-1 guard, reason=
        // breakdown is "the breakdown guard still queried the database" — the
        // series the M4-2 experiment drives towards (rebuild count == 1).
        this.dbFallbacks = Counter.builder("cache_db_fallbacks")
                .tag("cache", TwoLevelCacheManager.PRODUCT_CACHE)
                .tag("reason", "breakdown")
                .register(meterRegistry);
        String cache = TwoLevelCacheManager.PRODUCT_CACHE;
        this.mutexRebuilds = mutexCounter(cache, "rebuild", meterRegistry);
        this.mutexDoubleCheckHits = mutexCounter(cache, "double-check-hit", meterRegistry);
        this.mutexTimeouts = mutexCounter(cache, "timeout", meterRegistry);
        this.mutexStaleServes = mutexCounter(cache, "stale-serve", meterRegistry);
        this.mutexLockErrors = mutexCounter(cache, "error", meterRegistry);
        this.logicalColds = logicalCounter(cache, "cold-rebuild", meterRegistry);
        this.logicalFreshServes = logicalCounter(cache, "fresh-serve", meterRegistry);
        this.logicalStaleServes = logicalCounter(cache, "stale-serve", meterRegistry);
        this.refreshSubmitted = refreshCounter(cache, "submitted", meterRegistry);
        this.refreshDeduped = refreshCounter(cache, "deduped", meterRegistry);
        this.refreshSucceeded = refreshCounter(cache, "success", meterRegistry);
        this.refreshFailed = refreshCounter(cache, "failure", meterRegistry);
        this.refreshRejected = refreshCounter(cache, "rejected", meterRegistry);
    }

    public Optional<ProductResponse> load(long id, ProductPenetrationDefense.ProductFinder finder) {
        Objects.requireNonNull(finder, "finder");
        switch (modeService.current()) {
            case MUTEX:
                return withMutex(id, finder);
            case LOGICAL_EXPIRE:
                return withLogicalExpire(id, finder);
            // OFF / NULL_CACHE / BLOOM: the M4-1 penetration guard, untouched.
            default:
                return penetrationDefense.load(id, finder);
        }
    }

    public CacheDefenseMode mode() {
        return modeService.current();
    }

    /** Live stale-entry count for {@code GET /lab/cache/state}. */
    public long staleEntries() {
        return staleStore.size();
    }

    /** Keys with an async refresh in flight, for {@code GET /lab/cache/state}. */
    public long refreshInFlight() {
        return refreshing.size();
    }

    private Optional<ProductResponse> withMutex(long id, ProductPenetrationDefense.ProductFinder finder) {
        LabCacheProperties.Mutex config = properties.getBreakdown().getMutex();
        RLock lock;
        boolean acquired;
        try {
            lock = redissonClient.getLock(config.getKeyPrefix() + id);
            acquired = lock.tryLock(config.getWaitMs(), config.getLeaseMs(), TimeUnit.MILLISECONDS);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            mutexLockErrors.increment();
            log.warn("Mutex wait interrupted for product {}; falling through to source", id);
            return querySource(id, finder);
        } catch (RuntimeException ex) {
            // Redisson/Redis unavailable: best-effort like every other Redis use
            // in this lab — count it and let the request query the source itself.
            mutexLockErrors.increment();
            log.warn("Mutex lock unavailable for product {}; falling through to source: {}",
                    id, ex.toString());
            return querySource(id, finder);
        }
        if (!acquired) {
            mutexTimeouts.increment();
            if ("stale".equals(config.getOnLockTimeout())) {
                Optional<ProductResponse> stale = staleStore.find(id)
                        .map(LogicalStaleStore.StaleValue::value);
                if (stale.isPresent()) {
                    mutexStaleServes.increment();
                    return stale;
                }
            }
            return querySource(id, finder);
        }
        try {
            Optional<ProductResponse> cached = doubleCheck(id);
            if (cached.isPresent()) {
                mutexDoubleCheckHits.increment();
                return cached;
            }
            mutexRebuilds.increment();
            return querySource(id, finder);
        } finally {
            unlockQuietly(lock, id);
        }
    }

    /** Second look after acquiring the lock: shared cache first, then a fresh stale entry. */
    private Optional<ProductResponse> doubleCheck(long id) {
        Optional<ProductResponse> cached = cacheProbe.peek(id);
        if (cached.isPresent()) {
            return cached;
        }
        return staleStore.find(id)
                .filter(entry -> nowMillis.getAsLong() < entry.logicalExpireAtMillis())
                .map(LogicalStaleStore.StaleValue::value);
    }

    private void unlockQuietly(RLock lock, long id) {
        try {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        } catch (RuntimeException ex) {
            log.warn("Mutex unlock failed for product {} (lease will expire): {}", id, ex.toString());
        }
    }

    private Optional<ProductResponse> withLogicalExpire(long id, ProductPenetrationDefense.ProductFinder finder) {
        Optional<LogicalStaleStore.StaleValue> entry = staleStore.find(id);
        if (!entry.isPresent()) {
            // Cold start (or retention exceeded): honest synchronous rebuild.
            logicalColds.increment();
            return querySource(id, finder);
        }
        LogicalStaleStore.StaleValue stale = entry.get();
        if (nowMillis.getAsLong() < stale.logicalExpireAtMillis()) {
            logicalFreshServes.increment();
            return Optional.of(stale.value());
        }
        logicalStaleServes.increment();
        triggerRefresh(id, finder);
        return Optional.of(stale.value());
    }

    /**
     * At most ONE refresh per key: the in-flight set is claimed before submitting
     * and released when the task finishes, so the logical path cannot stampede
     * the database any more than the mutex path can.
     */
    private void triggerRefresh(long id, ProductPenetrationDefense.ProductFinder finder) {
        if (refreshing.putIfAbsent(id, Boolean.TRUE) != null) {
            refreshDeduped.increment();
            return;
        }
        try {
            refreshExecutor.execute(() -> {
                try {
                    refreshOne(id, finder);
                } finally {
                    refreshing.remove(id);
                }
            });
            refreshSubmitted.increment();
        } catch (RejectedExecutionException ex) {
            refreshing.remove(id);
            refreshRejected.increment();
            log.debug("Refresh rejected for product {} (pool saturated); stale value keeps serving", id);
        }
    }

    private void refreshOne(long id, ProductPenetrationDefense.ProductFinder finder) {
        try {
            // Through querySource so the background DB read is counted in
            // cache_db_fallbacks{reason="breakdown"} and its result refreshes
            // the stale entry's logical deadline.
            Optional<ProductResponse> fresh = querySource(id, finder);
            if (fresh.isPresent()) {
                // The stale serve passed the OLD value back through @Cacheable,
                // so the shared cache now holds stale data until its physical
                // TTL ends. Publishing the refreshed value is what keeps the
                // promised "briefly stale" window at ONE refresh instead of a
                // full physical TTL. A rare interleaving (this write landing
                // before that serve's own cache write) is self-healing on the
                // next cycle and disclosed in the step handover.
                publishQuietly(id, fresh.get());
                refreshSucceeded.increment();
            } else {
                // The product vanished: keep serving stale until the retention
                // window closes instead of inventing a 404 mid-experiment.
                refreshFailed.increment();
            }
        } catch (RuntimeException ex) {
            refreshFailed.increment();
            log.warn("Async breakdown refresh failed for product {}; stale value retained: {}",
                    id, ex.toString());
        }
    }

    /** Publishing to the shared cache is best-effort: the stale store already carries the value. */
    private void publishQuietly(long id, ProductResponse value) {
        try {
            cacheWriter.write(id, value);
        } catch (RuntimeException ex) {
            log.warn("Breakdown refresh could not publish product {} to the shared cache: {}",
                    id, ex.toString());
        }
    }

    /** Every real source read counts one breakdown fallback and is retained as the stale value. */
    private Optional<ProductResponse> querySource(long id, ProductPenetrationDefense.ProductFinder finder) {
        dbFallbacks.increment();
        Optional<ProductResponse> found = finder.find(id);
        found.ifPresent(value -> staleStore.put(id, value, nowMillis.getAsLong()));
        return found;
    }

    private static Counter mutexCounter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_breakdown_mutex")
                .tag("cache", cache).tag("result", result).register(registry);
    }

    private static Counter logicalCounter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_breakdown_logical")
                .tag("cache", cache).tag("result", result).register(registry);
    }

    private static Counter refreshCounter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_breakdown_refresh")
                .tag("cache", cache).tag("result", result).register(registry);
    }
}
