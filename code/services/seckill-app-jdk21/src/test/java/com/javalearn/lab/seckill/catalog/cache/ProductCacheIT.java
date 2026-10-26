package com.javalearn.lab.seckill.catalog.cache;

import com.github.benmanes.caffeine.cache.Cache;
import com.javalearn.lab.seckill.MySqlRedisIntegrationTestSupport;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.api.ProductViewService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.math.BigDecimal;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end cache semantics through Spring's {@code @Cacheable} proxy with
 * MySQL 8.4.4 (source of truth) and Redis 7.4.2 (L2).
 */
@AutoConfigureObservability
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "observability.env=test",
                "observability.instance=test-1"
        })
class ProductCacheIT extends MySqlRedisIntegrationTestSupport {

    private static final long PRODUCT_ID = 1L;
    private static final String REDIS_KEY = "cache:product:1";

    @Autowired
    private ProductViewService productViewService;

    @Autowired
    private CacheManager cacheManager;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void clearCache() {
        cache().clear();
        assertThat(nativeCache().estimatedSize()).isZero();
        assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();
    }

    @Test
    void coldReadFallsBackToDatabaseThenServesL1AndRedisL2() {
        double fallbackBefore = counter("cache_db_fallbacks", "cache", "product", "reason", "miss");
        double l1HitBefore = counter("cache_l1_requests", "cache", "product", "result", "hit");
        double l1MissBefore = counter("cache_l1_requests", "cache", "product", "result", "miss");
        double l2HitBefore = counter("cache_l2_requests", "cache", "product", "result", "hit");
        double l2MissBefore = counter("cache_l2_requests", "cache", "product", "result", "miss");

        ProductResponse first = productViewService.get(PRODUCT_ID);

        assertThat(first).isNotNull();
        assertThat(first.id()).isEqualTo(PRODUCT_ID);
        assertThat(counter("cache_db_fallbacks", "cache", "product", "reason", "miss"))
                .isEqualTo(fallbackBefore + 1);
        assertThat(counter("cache_l1_requests", "cache", "product", "result", "miss"))
                .isEqualTo(l1MissBefore + 1);
        assertThat(counter("cache_l2_requests", "cache", "product", "result", "miss"))
                .isEqualTo(l2MissBefore + 1);
        assertThat(counter("cache_l1_requests", "cache", "product", "result", "hit"))
                .isEqualTo(l1HitBefore);

        assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
        Long ttl = redisTemplate.getExpire(REDIS_KEY, TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(300L);

        // Second call: L1 hit, no new database fallback.
        ProductResponse second = productViewService.get(PRODUCT_ID);
        assertThat(second).isEqualTo(first);
        assertThat(counter("cache_l1_requests", "cache", "product", "result", "hit"))
                .isEqualTo(l1HitBefore + 1);
        assertThat(counter("cache_db_fallbacks", "cache", "product", "reason", "miss"))
                .isEqualTo(fallbackBefore + 1);

        // Drop only L1: the next read must be served by Redis and repopulate L1.
        nativeCache().invalidateAll();
        ProductResponse fromL2 = productViewService.get(PRODUCT_ID);
        assertThat(fromL2).isEqualTo(first);
        assertThat(counter("cache_l2_requests", "cache", "product", "result", "hit"))
                .isEqualTo(l2HitBefore + 1);
        assertThat(counter("cache_db_fallbacks", "cache", "product", "reason", "miss"))
                .isEqualTo(fallbackBefore + 1);
        assertThat(nativeCache().getIfPresent(PRODUCT_ID)).isEqualTo(first);
    }

    @Test
    void evictRemovesBothLayersAndNextReadReloadsFromDatabase() {
        productViewService.get(PRODUCT_ID);
        assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();

        cache().evict(PRODUCT_ID);

        assertThat(nativeCache().getIfPresent(PRODUCT_ID)).isNull();
        assertThat(redisTemplate.hasKey(REDIS_KEY)).isFalse();

        double fallbackBefore = counter("cache_db_fallbacks", "cache", "product", "reason", "miss");
        ProductResponse reloaded = productViewService.get(PRODUCT_ID);
        assertThat(reloaded).isNotNull();
        assertThat(counter("cache_db_fallbacks", "cache", "product", "reason", "miss"))
                .isEqualTo(fallbackBefore + 1);
        assertThat(redisTemplate.hasKey(REDIS_KEY)).isTrue();
    }

    @Test
    void cachedPayloadMatchesDatabaseRow() {
        ProductResponse cached = productViewService.get(PRODUCT_ID);

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT name, price, status, description FROM catalog_product WHERE id = ?",
                PRODUCT_ID);

        assertThat(cached.name()).isEqualTo(row.get("name"));
        assertThat(cached.price()).isEqualByComparingTo((BigDecimal) row.get("price"));
        assertThat(cached.status()).isEqualTo(row.get("status"));
        assertThat(cached.description()).isEqualTo(row.get("description"));
    }

    private TwoLevelCache cache() {
        return (TwoLevelCache) Objects.requireNonNull(
                cacheManager.getCache(TwoLevelCacheManager.PRODUCT_CACHE));
    }

    @SuppressWarnings("unchecked")
    private Cache<Object, Object> nativeCache() {
        return (Cache<Object, Object>) cache().getNativeCache();
    }

    private double counter(String name, String... tags) {
        Counter counter = meterRegistry.find(name).tags(tags).counter();
        return counter == null ? 0.0 : counter.count();
    }
}
