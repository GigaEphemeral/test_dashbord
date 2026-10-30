package com.javalearn.lab.seckill.jdk8.catalog.cache;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.jdk8.catalog.ProductResponse;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.function.LongSupplier;

/**
 * Cache-avalanche (雪崩) stage of the product read path — design 8.4 row three —
 * sitting in front of the M4-1 penetration guard and the M4-2 breakdown guard.
 *
 * <p>It runs on the {@code @Cacheable} method body, i.e. only after L1 AND L2
 * missed, and it owns exactly the three avalanche strategies:</p>
 *
 * <ul>
 *   <li>{@code ttl-jitter} — the miss is an honest source read here; the
 *       defense itself is the TTL spread applied to the L2 WRITE (see
 *       {@link AvalancheTtlCache}), so successive rebuilds of the experiment
 *       keys stop expiring in lockstep.</li>
 *   <li>{@code l1-cache} — an L1-first posture: the guard keeps a bounded,
 *       per-instance mirror of the last good values. A value younger than
 *       {@code refresh-interval-seconds} is served fresh; after that it is
 *       served stale while ONE bounded async refresh rebuilds it and publishes
 *       it back into the shared cache; past {@code stale-window-seconds} the
 *       value is honestly rebuilt. The stale window IS the documented
 *       multi-instance staleness cost (≈ refresh interval, capped by the L1 TTL
 *       per design 8.4), and the async path absorbs an L2 avalanche instead of
 *       the database.</li>
 *   <li>{@code db-fallback} — 熔断降级到 DB + 限流: database reads are admitted
 *       by a bounded {@link AvalancheLimiter}; beyond the budget the read fast
 *       fails with the standard {@code 42901} rate-limit error, so a stampede
 *       degrades instead of overwhelming MySQL.</li>
 * </ul>
 *
 * <p>Every other mode delegates unchanged to {@link ProductBreakdownDefense},
 * which in turn delegates the penetration modes to
 * {@link ProductPenetrationDefense}: {@code off} keeps the historical behaviour
 * byte for byte. Absent products stay {@link Optional#empty()} (the caller keeps
 * the {@code 40401} contract); the guard only decides whether the database is
 * asked.</p>
 */
public final class ProductAvalancheDefense {

    private static final Logger log = LoggerFactory.getLogger(ProductAvalancheDefense.class);

    private final CacheDefenseModeService modeService;
    private final ProductBreakdownDefense breakdownDefense;
    private final LogicalStaleStore l1Store;
    private final Executor refreshExecutor;
    private final ProductBreakdownDefense.CacheWriter cacheWriter;
    private final AvalancheLimiter limiter;
    private final LongSupplier nowMillis;

    /** One async L1 refresh per key: the herd control of the l1-cache path. */
    private final ConcurrentHashMap<Long, Boolean> refreshing = new ConcurrentHashMap<>();

    private final Counter dbFallbacks;
    private final Counter l1FreshServes;
    private final Counter l1StaleServes;
    private final Counter l1ColdRebuilds;
    private final Counter refreshSubmitted;
    private final Counter refreshDeduped;
    private final Counter refreshSucceeded;
    private final Counter refreshFailed;
    private final Counter refreshRejected;
    private final Counter limiterAcquired;
    private final Counter limiterRejected;

    public ProductAvalancheDefense(CacheDefenseModeService modeService,
                                   ProductBreakdownDefense breakdownDefense,
                                   LogicalStaleStore l1Store,
                                   Executor refreshExecutor,
                                   ProductBreakdownDefense.CacheWriter cacheWriter,
                                   AvalancheLimiter limiter,
                                   LongSupplier nowMillis,
                                   MeterRegistry meterRegistry) {
        this.modeService = Objects.requireNonNull(modeService, "modeService");
        this.breakdownDefense = Objects.requireNonNull(breakdownDefense, "breakdownDefense");
        this.l1Store = Objects.requireNonNull(l1Store, "l1Store");
        this.refreshExecutor = Objects.requireNonNull(refreshExecutor, "refreshExecutor");
        this.cacheWriter = Objects.requireNonNull(cacheWriter, "cacheWriter");
        this.limiter = Objects.requireNonNull(limiter, "limiter");
        this.nowMillis = Objects.requireNonNull(nowMillis, "nowMillis");

        String cache = TwoLevelCacheManager.PRODUCT_CACHE;
        // Fourth value of the existing family (miss / penetration / breakdown):
        // "the avalanche defenses still had to ask the database". The
        // db-fallback mode drives it towards the limiter budget, never zero.
        this.dbFallbacks = Counter.builder("cache_db_fallbacks")
                .tag("cache", cache)
                .tag("reason", "avalanche")
                .register(meterRegistry);
        this.l1FreshServes = l1Counter(cache, "fresh-serve", meterRegistry);
        this.l1StaleServes = l1Counter(cache, "stale-serve", meterRegistry);
        this.l1ColdRebuilds = l1Counter(cache, "cold-rebuild", meterRegistry);
        this.refreshSubmitted = refreshCounter(cache, "submitted", meterRegistry);
        this.refreshDeduped = refreshCounter(cache, "deduped", meterRegistry);
        this.refreshSucceeded = refreshCounter(cache, "success", meterRegistry);
        this.refreshFailed = refreshCounter(cache, "failure", meterRegistry);
        this.refreshRejected = refreshCounter(cache, "rejected", meterRegistry);
        this.limiterAcquired = limiterCounter(cache, "acquired", meterRegistry);
        this.limiterRejected = limiterCounter(cache, "rejected", meterRegistry);
    }

    public Optional<ProductResponse> load(long id, ProductPenetrationDefense.ProductFinder finder) {
        Objects.requireNonNull(finder, "finder");
        switch (modeService.current()) {
            case TTL_JITTER:
                return querySource(id, finder);
            case L1_CACHE:
                return withL1Cache(id, finder);
            case DB_FALLBACK:
                return withBoundedDbFallback(id, finder);
            // OFF / penetration / breakdown modes: the M4-1 and M4-2 stages,
            // untouched and in their original order.
            default:
                return breakdownDefense.load(id, finder);
        }
    }

    public CacheDefenseMode mode() {
        return modeService.current();
    }

    /** Live L1-mirror entry count, for {@code GET /lab/cache/state}. */
    public long l1StaleEntries() {
        return l1Store.size();
    }

    /** Keys with an async L1 refresh in flight, for {@code GET /lab/cache/state}. */
    public long l1RefreshInFlight() {
        return refreshing.size();
    }

    public long limiterAcquired() {
        return limiter.acquired();
    }

    public long limiterRejected() {
        return limiter.rejected();
    }

    private Optional<ProductResponse> withL1Cache(long id, ProductPenetrationDefense.ProductFinder finder) {
        Optional<LogicalStaleStore.StaleValue> entry = l1Store.find(id);
        if (!entry.isPresent()) {
            // Cold, or older than the stale window: an honest synchronous
            // rebuild. This is the only l1-cache case that blocks on MySQL.
            l1ColdRebuilds.increment();
            return querySource(id, finder);
        }
        LogicalStaleStore.StaleValue stale = entry.get();
        if (nowMillis.getAsLong() < stale.logicalExpireAtMillis()) {
            l1FreshServes.increment();
            return Optional.of(stale.value());
        }
        // Inside the stale window: answer immediately, rebuild in the
        // background at most once per key, so 200 simultaneous expiries turn
        // into bounded refresh work instead of 200 synchronous DB reads.
        l1StaleServes.increment();
        triggerRefresh(id, finder);
        return Optional.of(stale.value());
    }

    private Optional<ProductResponse> withBoundedDbFallback(long id,
                                                           ProductPenetrationDefense.ProductFinder finder) {
        if (!limiter.tryAcquire()) {
            limiterRejected.increment();
            log.debug("Avalanche database-fallback budget exhausted for product {}; fast failing", id);
            throw new BizException(CommonErrorCode.RATE_LIMITED,
                    "Cache avalanche: database fallback budget exhausted");
        }
        limiterAcquired.increment();
        return querySource(id, finder);
    }

    /**
     * At most ONE refresh per key: the in-flight set is claimed before
     * submitting and released when the task finishes, so the async path cannot
     * stampede the database. A saturated pool counts {@code rejected} and the
     * stale value keeps serving — the reader is never blocked and never loses
     * its answer.
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
            log.debug("L1 refresh rejected for product {} (pool saturated); stale value keeps serving", id);
        }
    }

    private void refreshOne(long id, ProductPenetrationDefense.ProductFinder finder) {
        try {
            // Through querySource so the background read is counted in
            // cache_db_fallbacks{reason="avalanche"} and stored as the new
            // mirror value.
            Optional<ProductResponse> fresh = querySource(id, finder);
            if (fresh.isPresent()) {
                // The stale serve passed the OLD value back through @Cacheable,
                // which re-cached it in L1/L2. Publishing the refreshed value is
                // what keeps the promised staleness at ONE refresh instead of a
                // whole cache generation.
                publishQuietly(id, fresh.get());
                refreshSucceeded.increment();
            } else {
                // The product vanished: keep serving stale until the window
                // closes instead of inventing a 404 mid-experiment.
                refreshFailed.increment();
            }
        } catch (RuntimeException ex) {
            refreshFailed.increment();
            log.warn("Async L1 refresh failed for product {}; stale value retained: {}", id, ex.toString());
        }
    }

    private void publishQuietly(long id, ProductResponse value) {
        try {
            cacheWriter.write(id, value);
        } catch (RuntimeException ex) {
            log.warn("L1 refresh could not publish product {} to the shared cache: {}",
                    id, ex.toString());
        }
    }

    /** Every real source read counts one avalanche fallback and becomes the L1 mirror value. */
    private Optional<ProductResponse> querySource(long id, ProductPenetrationDefense.ProductFinder finder) {
        dbFallbacks.increment();
        Optional<ProductResponse> found = finder.find(id);
        found.ifPresent(value -> l1Store.put(id, value, nowMillis.getAsLong()));
        return found;
    }

    private static Counter l1Counter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_avalanche_l1")
                .tag("cache", cache).tag("result", result).register(registry);
    }

    private static Counter refreshCounter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_avalanche_l1_refresh")
                .tag("cache", cache).tag("result", result).register(registry);
    }

    private static Counter limiterCounter(String cache, String result, MeterRegistry registry) {
        return Counter.builder("cache_avalanche_limiter")
                .tag("cache", cache).tag("result", result).register(registry);
    }
}
