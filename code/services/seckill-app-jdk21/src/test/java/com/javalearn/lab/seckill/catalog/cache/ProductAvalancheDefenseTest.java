package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheAvalancheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The decision tables of the 雪崩 (avalanche) stage — {@code ttl-jitter},
 * {@code l1-cache} and {@code db-fallback} — on a real bounded L1 mirror with
 * injected clocks and an injected executor, plus the M4-1/M4-2 delegation.
 *
 * <p>Invariants proven here: {@code off} and every older mode delegate to the
 * breakdown/penetration stages untouched; {@code l1-cache} serves fresh, then
 * stale for at most the configured window while ONE refresh per key rebuilds
 * and publishes; {@code db-fallback} never exceeds its permits-per-period
 * budget and fast fails with {@code 42901}; every real source read counts
 * {@code cache_db_fallbacks{reason="avalanche"}}.</p>
 */
class ProductAvalancheDefenseTest {

    private static final ProductResponse OLD = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");
    private static final ProductResponse NEW = new ProductResponse(
            1L, "极客机械键盘 v2", new BigDecimal("309.00"), "ON_SALE", "改版");

    private final CacheDefenseModeService modeService =
            new CacheDefenseModeService(TestLabCacheProperties.defaults());
    private final ProductBreakdownDefense breakdownDefense = mock(ProductBreakdownDefense.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();

    private final AtomicLong nowMillis = new AtomicLong(1_000_000L);
    private final AtomicLong tickerNanos = new AtomicLong(0L);
    private final LabCacheAvalancheProperties properties = avalancheProperties();
    /** Refresh interval 10s, stale window 60s — the defaults asserted in yml. */
    private final LogicalStaleStore l1Store =
            new LogicalStaleStore(properties.getL1Cache().getMaxEntries(),
                    Duration.ofSeconds(properties.getL1Cache().getStaleWindowSeconds()),
                    Duration.ofSeconds(properties.getL1Cache().getRefreshIntervalSeconds()),
                    tickerNanos::get);
    private final Map<Long, ProductResponse> published = new ConcurrentHashMap<>();
    private final ProductBreakdownDefense.CacheWriter cacheWriter = published::put;
    /** Inline by default: refreshes complete before load() returns. */
    private final Executor executor = Runnable::run;
    private final List<Runnable> deferredRefreshes = new ArrayList<>();
    private final AvalancheLimiter limiter =
            new AvalancheLimiter(3L, 1_000L, nowMillis::get);
    private final AtomicInteger finderCalls = new AtomicInteger();

    private ProductAvalancheDefense defense;

    @BeforeEach
    void setUp() {
        defense = defenseWith(executor, cacheWriter);
    }

    // ------------------------------------------------------------ delegation

    @Test
    void offDelegatesToTheBreakdownStageByteForByte() {
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        Optional<ProductResponse> fromBreakdown = Optional.of(OLD);
        when(breakdownDefense.load(1L, finder)).thenReturn(fromBreakdown);

        // The guard must return the breakdown stage's answer untouched: the very
        // same Optional instance, not a re-query or a re-wrapped copy.
        assertThat(defense.load(1L, finder)).isSameAs(fromBreakdown);

        verify(breakdownDefense).load(1L, finder);
        assertThat(defense.mode()).isSameAs(CacheDefenseMode.OFF);
        assertThat(avalancheFallbacks()).isZero();
        assertThat(l1Store.size()).isZero();
        assertThat(finderCalls.get()).isZero();
    }

    @Test
    void penetrationAndBreakdownModesStillDelegate() {
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        for (String mode : List.of("null-cache", "bloom", "mutex", "logical-expire")) {
            modeService.update(mode);
            defense.load(7L, finder);
        }

        verify(breakdownDefense, times(4)).load(7L, finder);
        assertThat(avalancheFallbacks()).isZero();
    }

    // ------------------------------------------------------------ ttl-jitter

    @Test
    void ttlJitterModeReadsTheSourceHonestlyAndCountsTheAvalancheFallback() {
        modeService.update("ttl-jitter");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);

        // The write-side spread lives in AvalancheTtlCache; on the read side the
        // mode is an honest miss, and it is counted as an avalanche fallback.
        verifyNoInteractions(breakdownDefense);
        assertThat(avalancheFallbacks()).isEqualTo(1.0);
        assertThat(defense.mode()).isSameAs(CacheDefenseMode.TTL_JITTER);
    }

    @Test
    void ttlJitterModeKeepsTheAbsentContract() {
        modeService.update("ttl-jitter");

        assertThat(defense.load(404L, id -> Optional.empty())).isEmpty();
    }

    // -------------------------------------------------------------- l1-cache

    @Test
    void l1CacheColdStartsWithOneRebuildThenServesFreshWithoutTouchingTheSource() {
        modeService.update("l1-cache");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        assertThat(defense.load(1L, finder)).contains(OLD);   // cold rebuild
        assertThat(defense.load(1L, finder)).contains(OLD);   // fresh serve

        assertThat(l1("cold-rebuild")).isEqualTo(1.0);
        assertThat(l1("fresh-serve")).isEqualTo(1.0);
        assertThat(avalancheFallbacks()).isEqualTo(1.0);
        assertThat(finderCalls.get()).isEqualTo(1);
        assertThat(l1Store.size()).isEqualTo(1L);
    }

    @Test
    void l1CacheServesStaleInsideTheWindowWhileOneRefreshPublishesTheNewValue() {
        modeService.update("l1-cache");
        AtomicReference<ProductResponse> latest = new AtomicReference<>(OLD);
        ProductPenetrationDefense.ProductFinder finder = id -> {
            finderCalls.incrementAndGet();
            return Optional.of(latest.get());
        };
        defense.load(1L, finder);                             // cold rebuild of OLD

        advance(11L);                                         // past 10s refresh interval
        latest.set(NEW);
        assertThat(defense.load(1L, finder)).contains(OLD);   // stale served, inline refresh

        assertThat(l1("stale-serve")).isEqualTo(1.0);
        assertThat(refresh("submitted")).isEqualTo(1.0);
        assertThat(refresh("success")).isEqualTo(1.0);
        assertThat(avalancheFallbacks()).isEqualTo(2.0);
        assertThat(published).containsEntry(1L, NEW);

        // The refreshed value carries a new logical deadline: fresh from here.
        assertThat(defense.load(1L, finder)).contains(NEW);
        assertThat(l1("fresh-serve")).isEqualTo(1.0);
        assertThat(finderCalls.get()).isEqualTo(2);
    }

    @Test
    void pastTheStaleWindowTheValueIsHonestlyRebuilt() {
        modeService.update("l1-cache");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        defense.load(1L, finder);                             // cold rebuild

        advance(61L);                                         // past the 60s stale window

        assertThat(defense.load(1L, finder)).contains(OLD);   // cold rebuild again

        assertThat(l1("cold-rebuild")).isEqualTo(2.0);
        assertThat(l1("stale-serve")).isZero();
        assertThat(avalancheFallbacks()).isEqualTo(2.0);
        assertThat(finderCalls.get()).isEqualTo(2);
    }

    @Test
    void onlyOneRefreshPerKeyCanBeInFlight() {
        modeService.update("l1-cache");
        defense = defenseWith(deferredRefreshes::add, cacheWriter);
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        defense.load(1L, finder);                             // cold rebuild
        advance(11L);

        assertThat(defense.load(1L, finder)).contains(OLD);   // submits refresh #1
        assertThat(defense.load(1L, finder)).contains(OLD);   // deduped

        assertThat(refresh("submitted")).isEqualTo(1.0);
        assertThat(refresh("deduped")).isEqualTo(1.0);
        assertThat(defense.l1RefreshInFlight()).isEqualTo(1L);
        assertThat(avalancheFallbacks()).isEqualTo(1.0);       // only the cold read so far

        deferredRefreshes.forEach(Runnable::run);

        assertThat(avalancheFallbacks()).isEqualTo(2.0);
        assertThat(defense.l1RefreshInFlight()).isZero();
    }

    @Test
    void aSaturatedRefreshPoolKeepsServingStaleInsteadOfBlocking() {
        modeService.update("l1-cache");
        defense = defenseWith(task -> {
            throw new RejectedExecutionException("saturated");
        }, cacheWriter);
        defense.load(1L, finder(OLD));                        // cold rebuild
        advance(11L);

        assertThat(defense.load(1L, finder(NEW))).contains(OLD);

        assertThat(refresh("rejected")).isEqualTo(1.0);
        assertThat(defense.l1RefreshInFlight()).isZero();      // claim released, retriable
        assertThat(avalancheFallbacks()).isEqualTo(1.0);
    }

    @Test
    void aFailingRefreshKeepsTheStaleValueServing() {
        modeService.update("l1-cache");
        defense.load(1L, finder(OLD));                        // cold rebuild
        advance(11L);

        ProductPenetrationDefense.ProductFinder boom = id -> {
            throw new IllegalStateException("db is down");
        };
        assertThat(defense.load(1L, boom)).contains(OLD);

        assertThat(refresh("failure")).isEqualTo(1.0);
        assertThat(l1("stale-serve")).isEqualTo(1.0);
        assertThat(published).isEmpty();
    }

    @Test
    void aVanishedProductIsNotTurnedIntoAFourOhFourMidRefresh() {
        modeService.update("l1-cache");
        defense.load(1L, finder(OLD));                        // cold rebuild
        advance(11L);

        assertThat(defense.load(1L, id -> Optional.empty())).contains(OLD);

        assertThat(refresh("failure")).isEqualTo(1.0);
        assertThat(l1Store.size()).isEqualTo(1L);
    }

    @Test
    void aPublishFailureNeverEscapesTheStaleServe() {
        modeService.update("l1-cache");
        defense = defenseWith(executor, (id, value) -> {
            throw new IllegalStateException("cache manager exploded");
        });
        defense.load(1L, finder(OLD));                        // cold rebuild
        advance(11L);

        assertThatCode(() -> defense.load(1L, finder(NEW))).doesNotThrowAnyException();

        assertThat(refresh("success")).isEqualTo(1.0);
        assertThat(l1("stale-serve")).isEqualTo(1.0);
    }

    @Test
    void anAbsentColdReadStaysEmptyAndIsNotMirrored() {
        modeService.update("l1-cache");

        assertThat(defense.load(404L, id -> Optional.empty())).isEmpty();

        assertThat(l1Store.size()).isZero();
        assertThat(avalancheFallbacks()).isEqualTo(1.0);
    }

    // ------------------------------------------------------------ db-fallback

    @Test
    void dbFallbackAdmitsThePermitsThenFastFailsWith42901() {
        modeService.update("db-fallback");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);

        for (int i = 0; i < 3; i++) {
            assertThat(defense.load(1L, finder)).contains(OLD);
        }

        assertThatThrownBy(() -> defense.load(1L, finder))
                .isInstanceOfSatisfying(BizException.class, exception ->
                        assertThat(exception.getErrorCode()).isSameAs(CommonErrorCode.RATE_LIMITED));

        assertThat(limiterAcquired()).isEqualTo(3.0);
        assertThat(limiterRejectedMetric()).isEqualTo(1.0);
        assertThat(defense.limiterAcquired()).isEqualTo(3L);
        assertThat(defense.limiterRejected()).isEqualTo(1L);
        assertThat(avalancheFallbacks()).isEqualTo(3.0);
        assertThat(finderCalls.get()).isEqualTo(3);            // the 4th never reached the DB
    }

    @Test
    void theDbFallbackBudgetRefillsAfterThePeriod() {
        modeService.update("db-fallback");
        ProductPenetrationDefense.ProductFinder finder = finder(OLD);
        for (int i = 0; i < 3; i++) {
            defense.load(1L, finder);
        }
        assertThatThrownBy(() -> defense.load(1L, finder)).isInstanceOf(BizException.class);

        nowMillis.addAndGet(1_000L);

        assertThat(defense.load(1L, finder)).contains(OLD);
        assertThat(limiterAcquired()).isEqualTo(4.0);
    }

    @Test
    void dbFallbackKeepsTheAbsentContractInsideTheBudget() {
        modeService.update("db-fallback");

        assertThat(defense.load(404L, id -> Optional.empty())).isEmpty();
    }

    // ---------------------------------------------------------------- helpers

    private ProductAvalancheDefense defenseWith(Executor refreshExecutor,
                                                ProductBreakdownDefense.CacheWriter writer) {
        return new ProductAvalancheDefense(modeService, breakdownDefense, l1Store,
                refreshExecutor, writer, limiter, properties, nowMillis::get, registry);
    }

    private ProductPenetrationDefense.ProductFinder finder(ProductResponse value) {
        return id -> {
            finderCalls.incrementAndGet();
            return Optional.of(value);
        };
    }

    /** Moves both injected clocks (logical decisions and Caffeine retention). */
    private void advance(long seconds) {
        nowMillis.addAndGet(Duration.ofSeconds(seconds).toMillis());
        tickerNanos.addAndGet(TimeUnit.SECONDS.toNanos(seconds));
    }

    private double avalancheFallbacks() {
        return registry.get("cache_db_fallbacks").tag("cache", "product")
                .tag("reason", "avalanche").counter().count();
    }

    private double l1(String result) {
        return registry.get("cache_avalanche_l1").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    private double refresh(String result) {
        return registry.get("cache_avalanche_l1_refresh").tag("cache", "product")
                .tag("result", result).counter().count();
    }

    private double limiterAcquired() {
        return registry.get("cache_avalanche_limiter").tag("cache", "product")
                .tag("result", "acquired").counter().count();
    }

    private double limiterRejectedMetric() {
        return registry.get("cache_avalanche_limiter").tag("cache", "product")
                .tag("result", "rejected").counter().count();
    }

    private static LabCacheAvalancheProperties avalancheProperties() {
        LabCacheAvalancheProperties config = new LabCacheAvalancheProperties();
        config.getTtlJitter().setPercent(20D);
        config.getL1Cache().setRefreshIntervalSeconds(10L);
        config.getL1Cache().setStaleWindowSeconds(60L);
        config.getL1Cache().setMaxEntries(1_000L);
        config.getL1Cache().setRefreshPoolSize(2);
        config.getL1Cache().setRefreshQueueCapacity(100);
        config.getL1Cache().setKeepAliveSeconds(60L);
        config.getL1Cache().setThreadNamePrefix("avalanche-l1-refresh");
        config.getDbFallback().setPermits(3L);
        config.getDbFallback().setPeriodMs(1_000L);
        return config;
    }
}
