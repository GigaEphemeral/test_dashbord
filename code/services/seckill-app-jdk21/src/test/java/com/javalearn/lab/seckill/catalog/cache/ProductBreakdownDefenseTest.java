package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The decision tables of the M4-2 breakdown stage — mutex and logical-expire —
 * with a real stale store on a fake ticker/clock and mocked Redisson, so every
 * case is about WHO rebuilds and WHO serves stale, never about timing races.
 *
 * <p>Invariants: a response is never invented (absent stays
 * {@link Optional#empty()}), a Redisson failure never blocks a read, the async
 * path can never run two refreshes for one key, and every real source read is
 * counted in {@code cache_db_fallbacks{reason="breakdown"}}.</p>
 */
class ProductBreakdownDefenseTest {

    private static final ProductResponse OLD = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");
    private static final ProductResponse NEW = new ProductResponse(
            1L, "极客机械键盘 v2", new BigDecimal("309.00"), "ON_SALE", "改版");

    private final LabCacheProperties properties = TestLabCacheProperties.defaults();
    private final CacheDefenseModeService modeService = new CacheDefenseModeService(properties);
    private final ProductPenetrationDefense penetration = mock(ProductPenetrationDefense.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    private final RLock lock = mock(RLock.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AtomicLong nowMillis = new AtomicLong(1_000_000L);
    private final AtomicLong tickerNanos = new AtomicLong(0L);
    /** Logical ttl 30s / retention 120s, matching TestLabCacheProperties. */
    private final LogicalStaleStore staleStore =
            new LogicalStaleStore(1_000L, Duration.ofSeconds(120), Duration.ofSeconds(30),
                    tickerNanos::get);
    private final AtomicReference<Optional<ProductResponse>> probeResult =
            new AtomicReference<>(Optional.empty());
    /** Values the async refresh published back into the shared cache. */
    private final Map<Long, ProductResponse> published = new ConcurrentHashMap<>();
    private final ProductBreakdownDefense.CacheWriter cacheWriter = published::put;
    /** Inline by default: refreshes complete before load() returns, so assertions are race-free. */
    private final Executor executor = Runnable::run;
    private final List<Runnable> deferredRefreshes = new ArrayList<>();

    private ProductBreakdownDefense defense;

    @BeforeEach
    void setUp() throws Exception {
        when(redisson.getLock("lock:product:1")).thenReturn(lock);
        when(lock.isHeldByCurrentThread()).thenReturn(true);
        defense = new ProductBreakdownDefense(modeService, penetration, redisson, staleStore,
                executor, id -> probeResult.get(), cacheWriter, properties, nowMillis::get, registry);
    }

    // ---------------------------------------------------------------- mutex

    @Test
    void mutexHolderRebuildsOnceAndReleasesTheLock() throws Exception {
        modeService.update("mutex");
        acquire(true);
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);

        assertThat(mutex("rebuild")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
        verify(lock).unlock();
    }

    @Test
    void mutexWaiterDoubleChecksTheRebuiltValueAndSkipsTheDatabase() throws Exception {
        modeService.update("mutex");
        acquire(true);
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);   // holder: rebuild
        assertThat(defense.load(1L, finder)).contains(OLD);   // waiter: double-check hit

        // One DB read for two requests is the whole point of the mutex.
        assertThat(mutex("rebuild")).isEqualTo(1.0);
        assertThat(mutex("double-check-hit")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
    }

    @Test
    void mutexDoubleCheckSeesAValueTheSharedCacheJustGained() throws Exception {
        modeService.update("mutex");
        acquire(true);
        probeResult.set(Optional.of(NEW));
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(NEW);

        assertThat(mutex("double-check-hit")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isZero();
        verify(lock).unlock();
    }

    @Test
    void mutexWaitTimeoutHonestlyQueriesTheDatabaseByDefault() throws Exception {
        modeService.update("mutex");
        acquire(false);
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);

        assertThat(mutex("timeout")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
        verify(lock, never()).unlock();
    }

    @Test
    void mutexWaitTimeoutCanServeTheRetainedValueWhenConfigured() throws Exception {
        modeService.update("mutex");
        properties.getBreakdown().getMutex().setOnLockTimeout("stale");
        acquire(true);
        defense.load(1L, finder(OLD));                       // rebuild warms the stale store

        acquire(false);
        ProductPenetrationDefense.ProductFinder second = finder(NEW);
        assertThat(defense.load(1L, second)).contains(OLD);  // stale, no second DB read

        assertThat(mutex("timeout")).isEqualTo(1.0);
        assertThat(mutex("stale-serve")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
    }

    @Test
    void aDeadRedissonNeverBlocksTheRead() {
        modeService.update("mutex");
        when(redisson.getLock("lock:product:1"))
                .thenThrow(new IllegalStateException("redisson is down"));
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);

        assertThat(mutex("error")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
    }

    @Test
    void mutexNeverInventsAProductThatDoesNotExist() throws Exception {
        modeService.update("mutex");
        acquire(true);

        assertThat(defense.load(1L, id -> Optional.empty())).isEmpty();

        assertThat(mutex("rebuild")).isEqualTo(1.0);
        assertThat(staleStore.size()).isZero();
    }

    // ------------------------------------------------------- logical expire

    @Test
    void logicalColdStartRebuildsSynchronouslyThenServesFresh() {
        modeService.update("logical-expire");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);   // cold
        assertThat(defense.load(1L, finder)).contains(OLD);   // inside the logical TTL

        assertThat(logical("cold-rebuild")).isEqualTo(1.0);
        assertThat(logical("fresh-serve")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
        assertThat(refresh("submitted")).isZero();
    }

    @Test
    void logicallyExpiredValueIsServedImmediatelyWhileTheRefreshRebuilds() {
        modeService.update("logical-expire");
        AtomicReference<ProductResponse> latest = new AtomicReference<>(OLD);
        ProductPenetrationDefense.ProductFinder finder = id -> Optional.of(latest.get());
        defense.load(1L, finder);                             // cold rebuild of OLD

        advanceLogical();                                     // 31s: stale but retained
        latest.set(NEW);
        assertThat(defense.load(1L, finder)).contains(OLD);   // stale served, inline refresh

        assertThat(logical("stale-serve")).isEqualTo(1.0);
        assertThat(refresh("submitted")).isEqualTo(1.0);
        assertThat(refresh("success")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(2.0);

        advanceLogical();
        assertThat(defense.load(1L, finder)).contains(NEW);   // the store now carries NEW
    }

    @Test
    void onlyOneRefreshPerKeyCanEverBeInFlight() {
        modeService.update("logical-expire");
        defense = new ProductBreakdownDefense(modeService, penetration, redisson, staleStore,
                deferredRefreshes::add, id -> probeResult.get(), cacheWriter, properties,
                nowMillis::get, registry);
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        defense.load(1L, finder);                             // cold rebuild
        advanceLogical();

        assertThat(defense.load(1L, finder)).contains(OLD);   // submits refresh #1
        assertThat(defense.load(1L, finder)).contains(OLD);   // deduped, no second refresh
        assertThat(defense.refreshInFlight()).isEqualTo(1L);

        assertThat(refresh("submitted")).isEqualTo(1.0);
        assertThat(refresh("deduped")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);       // only the cold read so far

        deferredRefreshes.forEach(Runnable::run);             // the one refresh finally runs
        assertThat(breakdownFallbacks()).isEqualTo(2.0);
        assertThat(defense.refreshInFlight()).isZero();
    }

    @Test
    void aFailingRefreshKeepsTheStaleValueServing() {
        modeService.update("logical-expire");
        defense.load(1L, finder(OLD));                        // cold rebuild
        advanceLogical();

        ProductPenetrationDefense.ProductFinder boom = id -> {
            throw new IllegalStateException("db is down");
        };
        assertThat(defense.load(1L, boom)).contains(OLD);     // stale served despite failure

        assertThat(refresh("failure")).isEqualTo(1.0);
        assertThat(defense.load(1L, boom)).contains(OLD);     // still stale, retried once more
        assertThat(refresh("failure")).isEqualTo(2.0);
    }

    @Test
    void aSaturatedRefreshPoolStillServesStaleInsteadOfBlocking() {
        modeService.update("logical-expire");
        defense = new ProductBreakdownDefense(modeService, penetration, redisson, staleStore,
                task -> {
                    throw new RejectedExecutionException("saturated");
                }, id -> probeResult.get(), cacheWriter, properties, nowMillis::get, registry);
        defense.load(1L, finder(OLD));                        // cold rebuild
        advanceLogical();

        assertThat(defense.load(1L, finder(NEW))).contains(OLD);

        assertThat(refresh("rejected")).isEqualTo(1.0);
        assertThat(defense.refreshInFlight()).isZero();        // claim released, retriable later
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
    }

    @Test
    void aProductThatVanishedIsNotTurnedIntoAFourOhFourMidRefresh() {
        modeService.update("logical-expire");
        defense.load(1L, finder(OLD));                        // cold rebuild
        advanceLogical();

        assertThat(defense.load(1L, id -> Optional.empty())).contains(OLD);

        assertThat(refresh("failure")).isEqualTo(1.0);
        assertThat(defense.staleEntries()).isEqualTo(1L);     // stale kept until retention, no fake 404
    }

    @Test
    void retentionExpiryTurnsTheStaleValueBackIntoAColdRebuild() {
        modeService.update("logical-expire");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        defense.load(1L, finder);
        tickerNanos.set(TimeUnit.SECONDS.toNanos(121));       // past the physical retention

        assertThat(defense.load(1L, finder)).contains(OLD);

        assertThat(logical("cold-rebuild")).isEqualTo(2.0);
        assertThat(breakdownFallbacks()).isEqualTo(2.0);
    }

    // ------------------------------------------------- refresh publish (M4-2 fix)

    @Test
    void anAsyncRefreshPublishesTheFreshValueToTheSharedCache() {
        modeService.update("logical-expire");
        AtomicReference<ProductResponse> latest = new AtomicReference<>(OLD);
        ProductPenetrationDefense.ProductFinder finder = id -> Optional.of(latest.get());
        defense.load(1L, finder);                             // cold rebuild: @Cacheable caches it, no publish
        assertThat(published).isEmpty();

        advanceLogical();
        latest.set(NEW);
        assertThat(defense.load(1L, finder)).contains(OLD);   // stale serve + inline refresh

        // Without this publish the stale value the serve re-cached would stay
        // in L2 for a full physical TTL - the wrong demo symptom.
        assertThat(published).containsEntry(1L, NEW);
        assertThat(refresh("success")).isEqualTo(1.0);
    }

    @Test
    void aFailedOrVanishedRefreshPublishesNothingToTheCache() {
        modeService.update("logical-expire");
        defense.load(1L, finder(OLD));                        // cold rebuild
        advanceLogical();

        assertThat(defense.load(1L, id -> {
            throw new IllegalStateException("db down");
        })).contains(OLD);
        assertThat(defense.load(1L, id -> Optional.empty())).contains(OLD);

        assertThat(published).isEmpty();                      // never invent data into the cache
        assertThat(refresh("failure")).isEqualTo(2.0);
    }

    @Test
    void aCachePublishFailureNeverEscapesTheStaleServe() {
        modeService.update("logical-expire");
        defense.load(1L, finder(OLD));                        // cold rebuild warms the store
        advanceLogical();
        defense = new ProductBreakdownDefense(modeService, penetration, redisson, staleStore,
                executor, id -> probeResult.get(),
                (id, value) -> {
                    throw new IllegalStateException("cache manager exploded");
                }, properties, nowMillis::get, registry);

        assertThatCode(() -> defense.load(1L, finder(NEW))).doesNotThrowAnyException();

        // The refresh still succeeded (stale store carries NEW); the publish
        // failure is swallowed so a reader never sees a 500 from the guard.
        assertThat(refresh("success")).isEqualTo(1.0);
        assertThat(logical("stale-serve")).isEqualTo(1.0);
    }

    // ------------------------------------------------------- mutex robustness

    @Test
    void aThrowingRebuildStillReleasesTheMutexLock() throws Exception {
        modeService.update("mutex");
        acquire(true);

        assertThatThrownBy(() -> defense.load(1L, id -> {
            throw new IllegalStateException("db down mid-rebuild");
        })).isInstanceOf(IllegalStateException.class);

        // Lock leak would deadlock every later reader: release is mandatory.
        verify(lock).unlock();
        assertThat(mutex("rebuild")).isEqualTo(1.0);
        assertThat(breakdownFallbacks()).isEqualTo(1.0);
        assertThat(staleStore.size()).isZero();
    }

    @Test
    void anInterruptedWaiterFallsBackToTheDatabaseAndKeepsTheInterruptFlag() throws Exception {
        modeService.update("mutex");
        when(lock.tryLock(anyLong(), anyLong(), any(TimeUnit.class)))
                .thenThrow(new InterruptedException("simulated shutdown signal"));
        try {
            ProductPenetrationDefense.ProductFinder finder = finder(OLD);

            assertThat(defense.load(1L, finder)).contains(OLD);

            assertThat(Thread.currentThread().isInterrupted()).isTrue();
            assertThat(mutex("error")).isEqualTo(1.0);
            assertThat(breakdownFallbacks()).isEqualTo(1.0);
            verify(lock, never()).unlock();                   // never held, never falsely released
        } finally {
            Thread.interrupted();                             // do not leak the flag into other tests
        }
    }

    // ---------------------------------------------------------- delegation

    @Test
    void everyPenetrationModeStillRunsTheM41GuardUntouched() {
        modeService.update("null-cache");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        defense.load(7L, finder);

        verify(penetration).load(7L, finder);
        assertThat(defense.mode()).isEqualTo(CacheDefenseMode.NULL_CACHE);
    }

    // ------------------------------------------------------------- helpers

    private void acquire(boolean granted) throws Exception {
        when(lock.tryLock(properties.getBreakdown().getMutex().getWaitMs(),
                properties.getBreakdown().getMutex().getLeaseMs(), TimeUnit.MILLISECONDS))
                .thenReturn(granted);
    }

    private static ProductPenetrationDefense.ProductFinder finder(ProductResponse value) {
        return id -> Optional.of(value);
    }

    /** Moves the injected clock past the 30s logical TTL, inside the 120s retention. */
    private void advanceLogical() {
        nowMillis.set(nowMillis.get() + Duration.ofSeconds(31).toMillis());
    }

    private double mutex(String result) {
        return registry.get("cache_breakdown_mutex").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    private double logical(String result) {
        return registry.get("cache_breakdown_logical").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    private double refresh(String result) {
        return registry.get("cache_breakdown_refresh").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    private double breakdownFallbacks() {
        return registry.get("cache_db_fallbacks").tag("cache", "product")
                .tag("reason", "breakdown").counter().count();
    }
}
