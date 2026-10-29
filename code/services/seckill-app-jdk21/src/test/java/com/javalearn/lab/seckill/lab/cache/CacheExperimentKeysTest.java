package com.javalearn.lab.seckill.lab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.catalog.cache.NullValueMarkerStore;
import com.javalearn.lab.seckill.catalog.cache.ProductAvalancheDefense;
import com.javalearn.lab.seckill.catalog.cache.ProductBloomFilterService;
import com.javalearn.lab.seckill.catalog.cache.ProductBreakdownDefense;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheSettings;
import com.javalearn.lab.seckill.lab.api.CacheExperimentResetResponse;
import com.javalearn.lab.seckill.lab.api.CacheKeyDetailResponse;
import com.javalearn.lab.seckill.lab.api.CacheKeysPageResponse;
import com.javalearn.lab.seckill.lab.api.CacheSeedResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheAvalancheProperties;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * B3-02: SCAN paging/detail/reset contracts and the seed target count.
 *
 * <p>The SCAN source is scripted page by page, which is the only way to prove
 * the raw cursor is passed through unchanged and that reset stops at its
 * deletion bound. No {@code KEYS} command exists in the production path.</p>
 */
class CacheExperimentKeysTest {

    private static final String PREFIX = "cache:product:";
    private static final long PRODUCT_COUNT = TestLabCacheProperties.PRODUCT_COUNT;
    private static final long START_ID = TestLabCacheProperties.START_ID;

    private final LabCacheProperties properties = TestLabCacheProperties.defaults();
    private final Deque<CacheExperimentDataService.KeyScanPage> pages = new ArrayDeque<>();
    private final List<String> patterns = new ArrayList<>();
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final org.springframework.cache.Cache productCache =
            mock(org.springframework.cache.Cache.class);
    private final Cache<Object, Object> l1 = Caffeine.newBuilder().maximumSize(100).build();
    private final ProductBloomFilterService bloom = mock(ProductBloomFilterService.class);

    private JdbcTemplate jdbc;
    private CacheExperimentDataService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cache_keys_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE TABLE catalog_product (id BIGINT PRIMARY KEY, name VARCHAR(128),"
                + " price DECIMAL(10,2), status VARCHAR(16), description VARCHAR(512))");
        jdbc.execute("CREATE TABLE inventory_stock (product_id BIGINT PRIMARY KEY,"
                + " available INT NOT NULL, reserved INT NOT NULL DEFAULT 0)");
        jdbc.execute("CREATE TABLE order_order (id BIGINT PRIMARY KEY)");

        when(template.opsForValue()).thenReturn(values);
        when(cacheManager.getCache("product")).thenReturn(productCache);
        when(productCache.getNativeCache()).thenReturn(l1);
        when(bloom.rebuild(anyCollection()))
                .thenReturn(0L);
        when(bloom.stats()).thenReturn(
                new ProductBloomFilterService.Stats(true, 0L, 10_000L, 0.01D, 5));
        when(template.delete(anyCollection())).thenAnswer(invocation -> {
            java.util.Collection<?> keys = invocation.getArgument(0);
            return (long) keys.size();
        });

        CacheExperimentDataService.KeyScanSource source = (pattern, cursor, count) -> {
            patterns.add(pattern);
            CacheExperimentDataService.KeyScanPage page = pages.pollFirst();
            if (page == null) {
                throw new AssertionError("unexpected extra SCAN call");
            }
            return page;
        };
        service = new CacheExperimentDataService(properties, jdbc, template, new ObjectMapper(),
                bloom, mock(NullValueMarkerStore.class), new CacheDefenseModeService(properties),
                mock(ProductBreakdownDefense.class), mock(ProductAvalancheDefense.class),
                avalancheProperties(), mock(LabToggleService.class), cacheManager,
                TwoLevelCacheSettings.defaults(), source);
    }

    @Test
    void scanKeysReturnsTheRawRedisCursorAndPerKeyTtls() {
        pages.add(new CacheExperimentDataService.KeyScanPage("42",
                List.of(PREFIX + 1, PREFIX + 2)));
        when(template.getExpire(PREFIX + 1, TimeUnit.SECONDS)).thenReturn(120L);
        when(template.getExpire(PREFIX + 2, TimeUnit.SECONDS)).thenReturn(-1L);

        CacheKeysPageResponse page = service.scanKeys(null, null, null);

        assertThat(patterns).containsExactly(PREFIX + "*");
        assertThat(page.cursor()).isEqualTo("42");
        assertThat(page.limit()).isEqualTo(properties.getKeys().getDefaultLimit());
        assertThat(page.scanned()).isEqualTo(2);
        assertThat(page.items()).containsExactly(
                new CacheKeysPageResponse.Item(PREFIX + 1, 120L),
                new CacheKeysPageResponse.Item(PREFIX + 2, -1L));
    }

    @Test
    void scanKeysWalksBoundedBatchesWhenAScanComesBackEmpty() {
        properties.getKeys().setScanBatches(2);
        pages.add(new CacheExperimentDataService.KeyScanPage("7", List.of()));
        pages.add(new CacheExperimentDataService.KeyScanPage("8", List.of(PREFIX + 9)));
        when(template.getExpire(anyString(), any(TimeUnit.class))).thenReturn(9L);

        CacheKeysPageResponse page = service.scanKeys(PREFIX, "0", 10);

        assertThat(page.cursor()).isEqualTo("8");
        assertThat(page.items()).hasSize(1);
        assertThat(page.scanned()).isEqualTo(1);
    }

    @Test
    void scanKeysPassesAContinuationCursorBackToRedis() {
        pages.add(new CacheExperimentDataService.KeyScanPage("0", List.of(PREFIX + 7)));
        when(template.getExpire(anyString(), any(TimeUnit.class))).thenReturn(5L);

        CacheKeysPageResponse page = service.scanKeys(PREFIX, "42", 10);

        assertThat(page.cursor()).isEqualTo("0");
        assertThat(page.items()).hasSize(1);
    }

    @Test
    void scanKeysRejectsPrefixesOutsideTheAllowListAndBadBounds() {
        assertThatThrownBy(() -> service.scanKeys("session:", "0", 10))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.INVALID_ARGUMENT))
                .hasMessageContaining("Unsupported key prefix");
        assertThatThrownBy(() -> service.scanKeys(PREFIX, "0", 0))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("limit must be between");
        assertThatThrownBy(() -> service.scanKeys(PREFIX, "0", 1_001))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("limit must be between");
        assertThatThrownBy(() -> service.scanKeys(PREFIX, "not-a-cursor", 10))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("cursor must be a Redis SCAN cursor");
        assertThat(patterns).isEmpty();
    }

    @Test
    void keyDetailTruncatesAtAByteCapWithoutSplittingACharacter() {
        properties.getKeys().setValueMaxBytes(4);
        String value = "实验商品";
        when(values.get(PREFIX + 1)).thenReturn(value);
        when(template.getExpire(PREFIX + 1, TimeUnit.SECONDS)).thenReturn(300L);
        l1.put(1L, value);

        CacheKeyDetailResponse detail = service.keyDetail(null, 1L);

        assertThat(detail.key()).isEqualTo(PREFIX + 1);
        assertThat(detail.productId()).isEqualTo(1L);
        assertThat(detail.l2Present()).isTrue();
        assertThat(detail.l1Present()).isTrue();
        assertThat(detail.truncated()).isTrue();
        assertThat(detail.valueBytes()).isEqualTo(value.getBytes(StandardCharsets.UTF_8).length);
        assertThat(detail.valueMaxBytes()).isEqualTo(4);
        assertThat(detail.value()).isEqualTo("实");
        assertThat(detail.ttlSeconds()).isEqualTo(300L);
    }

    @Test
    void keyDetailIs404WhenTheKeyIsAbsentFromBothLayers() {
        when(values.get(anyString())).thenReturn(null);

        assertThatThrownBy(() -> service.keyDetail(null, 999L))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(CommonErrorCode.NOT_FOUND))
                .hasMessageContaining(PREFIX + 999);
    }

    @Test
    void keyDetailRejectsUnknownShapesAndForeignKeys() {
        assertThatThrownBy(() -> service.keyDetail(null, null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> service.keyDetail(PREFIX + 1, 1L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("exactly one");
        assertThatThrownBy(() -> service.keyDetail("session:abc", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("key must start with");
        assertThatThrownBy(() -> service.keyDetail(PREFIX + "abc", null))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("<productId>");
        assertThatThrownBy(() -> service.keyDetail(null, 0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("productId must be positive");
    }

    @Test
    void resetDeletesByScanAndOnlyTouchesBusinessDataWhenAsked() {
        jdbc.update("INSERT INTO inventory_stock (product_id, available, reserved) VALUES (1, 999, 0)");
        jdbc.update("INSERT INTO order_order (id) VALUES (1)");
        pages.add(new CacheExperimentDataService.KeyScanPage("0",
                List.of(PREFIX + 1, PREFIX + 2)));

        CacheExperimentResetResponse cacheOnly = service.reset(false);

        assertThat(cacheOnly.deletedKeys()).isEqualTo(2L);
        assertThat(cacheOnly.includeBusiness()).isFalse();
        assertThat(count("order_order")).isEqualTo(1L);
        assertThat(stock(1L)).isEqualTo(999);

        pages.add(new CacheExperimentDataService.KeyScanPage("0", List.of(PREFIX + 3)));
        CacheExperimentResetResponse destructive = service.reset(true);

        assertThat(destructive.deletedKeys()).isEqualTo(1L);
        assertThat(destructive.includeBusiness()).isTrue();
        assertThat(count("order_order")).isZero();
        assertThat(stock(1L)).isEqualTo(37);
    }

    @Test
    void resetStopsAtTheConfiguredDeletionBound() {
        properties.getKeys().setResetMaxKeys(2L);
        pages.add(new CacheExperimentDataService.KeyScanPage("7",
                List.of(PREFIX + 1, PREFIX + 2, PREFIX + 3)));

        CacheExperimentResetResponse response = service.reset(false);

        assertThat(response.deletedKeys()).isEqualTo(2L);
        assertThat(patterns).hasSize(1);
    }

    @Test
    void seedAcceptsASmallerTargetCountAndClipsTheWarmUp() {
        CacheSeedResponse response = service.seed(2L);

        assertThat(response.startId()).isEqualTo(START_ID);
        assertThat(response.endId()).isEqualTo(START_ID + 1);
        assertThat(response.requestedProducts()).isEqualTo(2L);
        assertThat(response.inserted()).isEqualTo(2L);
        assertThat(response.rowsInRange()).isEqualTo(2L);
        assertThat(response.avalancheKeysWritten()).isEqualTo(2L);
        assertThat(response.hotKeyProductId()).isEqualTo(-1L);
        assertThat(response.hotKeyWritten()).isFalse();
        assertThat(count("catalog_product")).isEqualTo(2L);
    }

    @Test
    void seedRejectsCountsOutsideOneToTheConfiguredMaximum() {
        long max = properties.getSeed().getMaxRequestCount();
        assertThatThrownBy(() -> service.seed(0L))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("between 1 and " + max);
        assertThatThrownBy(() -> service.seed(max + 1))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("between 1 and " + max);
        // A count above the default product-count but below the request cap is
        // accepted: POST /lab/cache/seed is how a 10k experiment is prepared.
        assertThat(service.seed(PRODUCT_COUNT + 1).inserted()).isEqualTo(PRODUCT_COUNT + 1);
        assertThat(count("catalog_product")).isEqualTo(PRODUCT_COUNT + 1);
    }

    private long count(String table) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
    }

    private int stock(long productId) {
        return jdbc.queryForObject(
                "SELECT available FROM inventory_stock WHERE product_id = ?", Integer.class, productId);
    }

    private static LabCacheAvalancheProperties avalancheProperties() {
        LabCacheAvalancheProperties config = new LabCacheAvalancheProperties();
        config.getTtlJitter().setPercent(20D);
        config.getL1Cache().setRefreshIntervalSeconds(10L);
        config.getL1Cache().setStaleWindowSeconds(60L);
        config.getL1Cache().setMaxEntries(10_000L);
        config.getL1Cache().setRefreshPoolSize(2);
        config.getL1Cache().setRefreshQueueCapacity(100);
        config.getL1Cache().setKeepAliveSeconds(60L);
        config.getL1Cache().setThreadNamePrefix("avalanche-l1-refresh");
        config.getDbFallback().setPermits(50L);
        config.getDbFallback().setPeriodMs(1_000L);
        return config;
    }
}
