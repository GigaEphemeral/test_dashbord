package com.javalearn.lab.seckill.catalog.cache;

import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseMode;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * The decision table of the penetration stage, with the real marker store and
 * the real filter on top of mocked Redis/Redisson.
 *
 * <p>The invariant every case protects: the guard only ever changes whether the
 * database is asked. "Absent" is always reported as {@link Optional#empty()}, so
 * the caller's normal 404 stays the response and a false positive or a dead
 * filter can never become a 500.</p>
 */
class ProductPenetrationDefenseTest {

    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "极客机械键盘", new BigDecimal("299.00"), "ON_SALE", "87 键热插拔轴体");

    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBloomFilter<Long> filter = mock(RBloomFilter.class);
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final CacheDefenseModeService modeService;
    private final ProductPenetrationDefense defense;

    ProductPenetrationDefenseTest() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        this.modeService = new CacheDefenseModeService(properties);
        NullValueMarkerStore markers = new NullValueMarkerStore(redis,
                properties.getPenetration().getNullCache().getKeyPrefix(),
                Duration.ofSeconds(properties.getPenetration().getNullCache().getTtlSeconds()),
                Duration.ofSeconds(properties.getPenetration().getNullCache().getJitterSeconds()),
                () -> 0L, registry);
        ProductBloomFilterService bloom = new ProductBloomFilterService(redisson,
                properties.getPenetration().getBloom().getKey(),
                properties.getPenetration().getBloom().getExpectedInsertions(),
                properties.getPenetration().getBloom().getFalseProbability(), registry);
        this.defense = new ProductPenetrationDefense(modeService, markers, bloom, registry);
    }

    @BeforeEach
    void stubBackends() {
        when(redis.opsForValue()).thenReturn(values);
        when(redisson.<Long>getBloomFilter("bloom:product-ids")).thenReturn(filter);
    }

    @Test
    void offModeIsTheSymptomEveryMissReachesTheDatabase() {
        AtomicInteger reads = new AtomicInteger();

        assertThat(defense.load(404L, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();
        assertThat(defense.load(404L, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();

        assertThat(reads).hasValue(2);
        verifyNoInteractions(values);
        verify(filter, never()).contains(any(Long.class));
        assertThat(penetrationFallbacks()).isZero();
    }

    @Test
    void nullCacheRemembersTheMissAndSparesTheSecondRead() {
        modeService.update("null-cache");
        AtomicInteger reads = new AtomicInteger();
        ProductPenetrationDefense.ProductFinder finder = id -> {
            reads.incrementAndGet();
            return Optional.empty();
        };
        when(values.get("null:product:404")).thenReturn(null, "NULL");

        assertThat(defense.load(404L, finder)).isEmpty();
        assertThat(defense.load(404L, finder)).isEmpty();

        assertThat(reads).hasValue(1);
        verify(values).set("null:product:404", "NULL", Duration.ofSeconds(10));
        assertThat(penetrationFallbacks()).isEqualTo(1.0);
    }

    @Test
    void nullCacheNeverMarksAProductThatExists() {
        modeService.update("null-cache");
        when(values.get("null:product:1")).thenReturn(null);

        assertThat(defense.load(1L, id -> Optional.of(PRODUCT))).contains(PRODUCT);

        verify(values, never()).set(any(), any(), any(Duration.class));
        assertThat(penetrationFallbacks()).isEqualTo(1.0);
    }

    @Test
    void nullCacheStillAsksTheSourceWhenRedisIsDown() {
        modeService.update("null-cache");
        when(values.get(any())).thenThrow(new RedisConnectionFailureException("redis is down"));

        assertThat(defense.load(1L, id -> Optional.of(PRODUCT))).contains(PRODUCT);
        assertThat(registry.get("cache_null_cache_errors").tag("cache", "product")
                .counter().count()).isEqualTo(1.0);
    }

    @Test
    void aProvenNegativeFromTheBloomSkipsTheDatabaseEntirely() {
        modeService.update("bloom");
        AtomicInteger reads = new AtomicInteger();
        when(filter.contains(404L)).thenReturn(false);

        assertThat(defense.load(404L, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        })).isEmpty();

        assertThat(reads).hasValue(0);
        assertThat(penetrationFallbacks()).isZero();
        assertThat(bloomRequests("absent")).isEqualTo(1.0);
    }

    @Test
    void aBloomFalsePositiveFallsBackToTheDatabaseAndStaysANormalMiss() {
        modeService.update("bloom");
        AtomicInteger reads = new AtomicInteger();
        when(filter.contains(999_999L)).thenReturn(true);

        Optional<ProductResponse> result = defense.load(999_999L, id -> {
            reads.incrementAndGet();
            return Optional.empty();
        });

        // The guard hands the caller an ordinary "absent", which the read path
        // turns into the same 40401 as before; nothing here throws.
        assertThat(result).isEmpty();
        assertThat(reads).hasValue(1);
        assertThat(penetrationFallbacks()).isEqualTo(1.0);
        assertThat(bloomRequests("hit")).isEqualTo(1.0);
    }

    @Test
    void anUnusableFilterFallsBackToTheDatabaseInsteadOfRejectingReads() {
        modeService.update("bloom");
        when(redisson.<Long>getBloomFilter("bloom:product-ids"))
                .thenThrow(new IllegalStateException("filter not initialised"));
        AtomicInteger reads = new AtomicInteger();

        assertThat(defense.load(1L, id -> {
            reads.incrementAndGet();
            return Optional.of(PRODUCT);
        })).contains(PRODUCT);

        assertThat(reads).hasValue(1);
        assertThat(bloomRequests("error")).isEqualTo(1.0);
    }

    @Test
    void theModeIsReadPerCallSoASwitchTakesEffectWithoutARestart() {
        assertThat(defense.mode()).isEqualTo(CacheDefenseMode.OFF);
        modeService.update("null-cache");
        assertThat(defense.mode()).isEqualTo(CacheDefenseMode.NULL_CACHE);

        defense.load(404L, id -> Optional.empty());
        verify(values).get("null:product:404");

        modeService.update("bloom");
        assertThat(defense.mode()).isEqualTo(CacheDefenseMode.BLOOM);
    }

    private double penetrationFallbacks() {
        return registry.get("cache_db_fallbacks").tag("cache", "product").tag("reason", "penetration")
                .counter().count();
    }

    private double bloomRequests(String result) {
        return registry.get("cache_bloom_requests").tag("cache", "product").tag("result", result)
                .counter().count();
    }
}
