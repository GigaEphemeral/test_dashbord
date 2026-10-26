package com.javalearn.lab.seckill.lab.cache;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.cache.NullValueMarkerStore;
import com.javalearn.lab.seckill.catalog.cache.ProductBloomFilterService;
import com.javalearn.lab.seckill.lab.api.CacheSeedResponse;
import com.javalearn.lab.seckill.lab.api.CacheStateResponse;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.redisson.api.RBloomFilter;
import org.redisson.api.RedissonClient;
import org.springframework.cache.CacheManager;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Collection;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * The experiment dataset generator against an H2 {@code catalog_product} and a
 * scripted in-memory Redis.
 *
 * <p>The contracts proven here are the ones the later milestones depend on:
 * seeding is idempotent, the demo baseline (ids 1 and 20) is never written, the
 * avalanche keys really do share one TTL while the hot key keeps its own, and a
 * Redis outage is reported instead of failing the request. Redis and Redisson are
 * scripted rather than real precisely because the outage behaviour cannot be
 * forced out of a live container.</p>
 */
class CacheExperimentDataServiceTest {

    private static final long START_ID = TestLabCacheProperties.START_ID;
    private static final long PRODUCT_COUNT = TestLabCacheProperties.PRODUCT_COUNT;
    private static final long END_ID = START_ID + PRODUCT_COUNT - 1;
    private static final long HOT_KEY_ID = START_ID + TestLabCacheProperties.HOT_KEY_INDEX;

    private final Map<String, String> redisValues = new LinkedHashMap<>();
    private final Map<String, Duration> redisTtls = new LinkedHashMap<>();
    private final StringRedisTemplate template = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final RedissonClient redisson = mock(RedissonClient.class);
    @SuppressWarnings("unchecked")
    private final RBloomFilter<Long> filter = mock(RBloomFilter.class);
    private final CacheManager cacheManager = mock(CacheManager.class);
    private final org.springframework.cache.Cache productCache =
            mock(org.springframework.cache.Cache.class);
    private final Cache<Object, Object> l1 = Caffeine.newBuilder().maximumSize(100).build();
    private final LabCacheProperties properties = TestLabCacheProperties.defaults();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final NullValueMarkerStore markerStore = new NullValueMarkerStore(template,
            "null:product:", Duration.ofSeconds(10), Duration.ofSeconds(5), () -> 0L, registry);
    private final ProductBloomFilterService bloomFilter = new ProductBloomFilterService(redisson,
            "bloom:product-ids", 10_000L, 0.01D, registry);
    private final CacheDefenseModeService modeService = new CacheDefenseModeService(properties);
    private final com.javalearn.lab.seckill.catalog.cache.ProductBreakdownDefense breakdownDefense =
            mock(com.javalearn.lab.seckill.catalog.cache.ProductBreakdownDefense.class);

    /** The id set the filter was last rebuilt from, to prove its coverage. */
    private Collection<Long> bloomIdsSeen = List.of();

    private JdbcTemplate jdbcTemplate;
    private CacheExperimentDataService service;

    @BeforeEach
    void setUp() {
        DriverManagerDataSource dataSource = new DriverManagerDataSource(
                "jdbc:h2:mem:cache_lab_" + System.nanoTime() + ";DB_CLOSE_DELAY=-1", "sa", "");
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("""
                CREATE TABLE catalog_product (
                    id BIGINT NOT NULL,
                    name VARCHAR(128) NOT NULL,
                    price DECIMAL(10, 2) NOT NULL,
                    status VARCHAR(16) NOT NULL,
                    description VARCHAR(512),
                    PRIMARY KEY (id)
                )""");
        // The Flyway demo baseline that verify.sh, smoke.sh and reset-lab.sh assert on.
        jdbcTemplate.update("INSERT INTO catalog_product (id, name, price, status, description)"
                + " VALUES (1, '极客机械键盘', 299.00, 'ON_SALE', '87 键热插拔轴体')");
        jdbcTemplate.update("INSERT INTO catalog_product (id, name, price, status, description)"
                + " VALUES (20, '智能插座', 79.00, 'ON_SALE', '远程控制')");

        scriptRedis();
        scriptRedisson();
        when(cacheManager.getCache("product")).thenReturn(productCache);
        when(productCache.getNativeCache()).thenReturn(l1);

        service = new CacheExperimentDataService(properties, jdbcTemplate, template, objectMapper,
                bloomFilter, markerStore, modeService, breakdownDefense,
                new LabToggleService(toggles()), cacheManager);
    }

    @Test
    void seedFillsTheReservedRangeAndNeverTouchesTheBaseline() {
        CacheSeedResponse response = service.seed();

        assertThat(response.startId()).isEqualTo(START_ID);
        assertThat(response.endId()).isEqualTo(END_ID);
        assertThat(response.requestedProducts()).isEqualTo(PRODUCT_COUNT);
        assertThat(response.inserted()).isEqualTo(5L);
        assertThat(response.alreadyPresent()).isZero();
        assertThat(response.rowsInRange()).isEqualTo(5L);
        assertThat(response.avalancheKeysWritten()).isEqualTo(2L);
        assertThat(response.avalancheTtlSeconds()).isEqualTo(60L);
        assertThat(response.hotKeyProductId()).isEqualTo(HOT_KEY_ID);
        assertThat(response.hotKeyWritten()).isTrue();
        assertThat(response.bloomIdsAdded()).isEqualTo(7L);
        assertThat(response.bloomInitialised()).isTrue();
        assertThat(response.mode()).isEqualTo("off");

        assertThat(totalRows()).isEqualTo(7L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT description FROM catalog_product WHERE id = 1", String.class))
                .isEqualTo("87 键热插拔轴体");
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM catalog_product WHERE id BETWEEN 1 AND ?", Long.class,
                properties.getSeed().getProtectedMaxId())).isEqualTo(2L);
        assertThat(jdbcTemplate.queryForList(
                "SELECT description FROM catalog_product WHERE id BETWEEN ? AND ?", String.class,
                START_ID, END_ID))
                .containsOnly(CacheExperimentDataService.EXPERIMENT_DESCRIPTION);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM catalog_product WHERE id = ?", String.class, START_ID))
                .isEqualTo("实验商品-1");
    }

    @Test
    void aSecondSeedIsANoOpBecauseOnlyMissingRowsAreInserted() {
        service.seed();
        CacheSeedResponse second = service.seed();

        assertThat(second.inserted()).isZero();
        assertThat(second.alreadyPresent()).isEqualTo(5L);
        assertThat(second.rowsInRange()).isEqualTo(5L);
        assertThat(totalRows()).isEqualTo(7L);
        assertThat(second.avalancheKeysWritten()).isEqualTo(2L);
    }

    @Test
    void anAlreadyPresentExperimentRowIsNeverOverwritten() {
        jdbcTemplate.update("INSERT INTO catalog_product (id, name, price, status, description)"
                + " VALUES (?, '人工改过的名字', 1.00, 'ON_SALE', 'not generated')", START_ID);

        CacheSeedResponse response = service.seed();

        assertThat(response.inserted()).isEqualTo(4L);
        assertThat(response.alreadyPresent()).isEqualTo(1L);
        assertThat(response.rowsInRange()).isEqualTo(5L);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT name FROM catalog_product WHERE id = ?", String.class, START_ID))
                .isEqualTo("人工改过的名字");
    }

    @Test
    void theAvalancheKeysShareOneTtlAndTheHotKeyKeepsItsOwn() throws Exception {
        service.seed();

        assertThat(redisTtls.get("cache:product:" + START_ID)).isEqualTo(Duration.ofSeconds(60));
        assertThat(redisTtls.get("cache:product:" + (START_ID + 1))).isEqualTo(Duration.ofSeconds(60));
        assertThat(redisTtls.get("cache:product:" + HOT_KEY_ID)).isEqualTo(Duration.ofSeconds(300));

        // A seeded entry must deserialize exactly like one the L2 cache wrote,
        // otherwise the avalanche experiment would not be measuring the real path.
        ProductResponse warmed = objectMapper.readValue(
                redisValues.get("cache:product:" + START_ID), ProductResponse.class);
        assertThat(warmed.id()).isEqualTo(START_ID);
        assertThat(warmed.name()).isEqualTo("实验商品-1");
        assertThat(warmed.price()).isEqualByComparingTo(new BigDecimal("99.00"));
    }

    @Test
    void theBloomIsRebuiltFromEveryExistingIdNotOnlyTheExperimentRange() {
        service.seed();

        assertThat(bloomIdsSeen).containsExactly(1L, 20L, START_ID, START_ID + 1, START_ID + 2,
                START_ID + 3, START_ID + 4);
    }

    @Test
    void aRedisOutageLeavesTheSeedVisibleInsteadOfFailing() {
        doThrow(new RedisConnectionFailureException("redis is down"))
                .when(values).set(anyString(), anyString(), any(Duration.class));
        when(redisson.<Long>getBloomFilter("bloom:product-ids"))
                .thenThrow(new IllegalStateException("redisson is down"));

        CacheSeedResponse response = service.seed();

        assertThat(response.inserted()).isEqualTo(5L);
        assertThat(response.rowsInRange()).isEqualTo(5L);
        assertThat(response.avalancheKeysWritten()).isZero();
        assertThat(response.hotKeyWritten()).isFalse();
        assertThat(response.bloomIdsAdded()).isZero();
        assertThat(response.bloomInitialised()).isFalse();
    }

    @Test
    void stateReportsConfigurationAgainstReality() {
        service.seed();
        l1.put(START_ID, "cached");
        l1.put(START_ID + 1, "cached");
        l1.put(1L, "cached");
        markerStore.markAbsent(404L);

        CacheStateResponse state = service.state();

        assertThat(state.defense().mode()).isEqualTo("off");
        assertThat(state.defense().cacheToggleEnabled()).isTrue();
        assertThat(state.dataset()).isEqualTo(new CacheStateResponse.Dataset(
                START_ID, END_ID, PRODUCT_COUNT, 5L, 0L, 2L, 60L, HOT_KEY_ID));
        assertThat(state.keys().redisReachable()).isTrue();
        assertThat(state.keys().l1ExperimentKeys()).isEqualTo(2L);
        assertThat(state.keys().l2ExperimentKeys()).isEqualTo(3L);
        assertThat(state.keys().l2AvalancheKeysAlive()).isEqualTo(2L);
        assertThat(state.keys().hotKeyCached()).isTrue();
        assertThat(state.bloom()).isEqualTo(new CacheStateResponse.Bloom(
                "bloom:product-ids", true, 7L, 10_000L, 0.01D));
        assertThat(state.nullCache()).isEqualTo(
                new CacheStateResponse.NullCache("null:product:", 10L, 5L, 1L));
    }

    @Test
    void stateReportsTheMissingRowsOfAnUnseededDataset() {
        CacheStateResponse state = service.state();

        assertThat(state.dataset().rowsInRange()).isZero();
        assertThat(state.dataset().missingRows()).isEqualTo(PRODUCT_COUNT);
        assertThat(state.keys().l2ExperimentKeys()).isZero();
        assertThat(state.keys().hotKeyCached()).isFalse();
        assertThat(state.bloom().initialised()).isTrue();
        assertThat(state.bloom().size()).isZero();
    }

    @Test
    void stateDegradesToDatabaseOnlyWhenRedisIsUnreachable() {
        service.seed();
        when(template.scan(any(ScanOptions.class)))
                .thenThrow(new RedisConnectionFailureException("redis is down"));

        CacheStateResponse state = service.state();

        assertThat(state.keys().redisReachable()).isFalse();
        assertThat(state.keys().l2ExperimentKeys()).isZero();
        assertThat(state.keys().l2AvalancheKeysAlive()).isZero();
        assertThat(state.keys().hotKeyCached()).isFalse();
        assertThat(state.nullCache().liveMarkerKeys()).isZero();
        assertThat(state.dataset().rowsInRange()).isEqualTo(5L);
    }

    @Test
    void stateCountsNoL1KeysWhileTheCacheToggleHandsOutANoOpCache() {
        service.seed();
        l1.put(START_ID, "cached");
        when(productCache.getNativeCache()).thenReturn(new Object());

        assertThat(service.state().keys().l1ExperimentKeys()).isZero();
    }

    @Test
    void aRuntimeModeSwitchIsObservableThroughStateWithoutARestart() {
        // POST /lab/cache/mode mutates the very CacheDefenseModeService the state
        // view reads, so an operator can confirm the switch from GET /lab/cache/state.
        assertThat(service.state().defense().mode()).isEqualTo("off");

        modeService.update("null-cache");
        assertThat(service.state().defense().mode()).isEqualTo("null-cache");

        modeService.update("bloom");
        CacheStateResponse state = service.state();
        assertThat(state.defense().mode()).isEqualTo("bloom");
        // The bloom geometry in the same payload is what tells the operator the
        // protection is backed by a usable filter, not just by the switch.
        assertThat(state.bloom().key()).isEqualTo("bloom:product-ids");
        assertThat(state.bloom().initialised()).isTrue();
    }

    private long totalRows() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM catalog_product", Long.class);
    }

    private static LabToggleProperties toggles() {
        LabToggleProperties toggles = new LabToggleProperties();
        toggles.setLock(true);
        toggles.setIdempotency(true);
        toggles.setMq(true);
        toggles.setRatelimit(true);
        toggles.setCircuitbreaker(true);
        toggles.setCache(true);
        return toggles;
    }

    /** A scripted Redis: reads, TTL-carrying writes and SCAN over one map. */
    private void scriptRedis() {
        when(template.opsForValue()).thenReturn(values);
        when(values.get(any())).thenAnswer(invocation -> redisValues.get(invocation.getArgument(0)));
        doAnswer(invocation -> {
            redisValues.put(invocation.getArgument(0), invocation.getArgument(1));
            redisTtls.put(invocation.getArgument(0), invocation.getArgument(2));
            return null;
        }).when(values).set(anyString(), anyString(), any(Duration.class));
        when(template.scan(any(ScanOptions.class))).thenAnswer(invocation -> {
            String pattern = invocation.<ScanOptions>getArgument(0).getPattern();
            String prefix = pattern.substring(0, pattern.length() - 1);
            List<String> keys = redisValues.keySet().stream()
                    .filter(key -> key.startsWith(prefix))
                    .toList();
            Iterator<String> iterator = keys.iterator();
            @SuppressWarnings("unchecked")
            Cursor<String> cursor = mock(Cursor.class);
            when(cursor.hasNext()).thenAnswer(hasNext -> iterator.hasNext());
            when(cursor.next()).thenAnswer(next -> iterator.next());
            return cursor;
        });
    }

    private void scriptRedisson() {
        when(redisson.<Long>getBloomFilter("bloom:product-ids")).thenReturn(filter);
        when(filter.tryInit(anyLong(), anyDouble())).thenReturn(true);
        when(filter.add(anyCollection())).thenAnswer(invocation -> {
            Collection<Long> ids = invocation.getArgument(0);
            bloomIdsSeen = ids;
            return (long) ids.size();
        });
        when(filter.count()).thenAnswer(invocation -> (long) bloomIdsSeen.size());
        when(filter.getExpectedInsertions()).thenReturn(10_000L);
        when(filter.getFalseProbability()).thenReturn(0.01D);
        when(filter.getHashIterations()).thenReturn(5);
    }
}
