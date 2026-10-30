package com.javalearn.lab.seckill.jdk8.lab.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the two contracts the reviewer cannot see in the code: every
 * {@code app.lab.cache.*} value is registered in {@code application.yml} with an
 * environment override, and startup fails on a nonsensical combination instead
 * of silently corrupting the demo baseline.
 */
class LabCachePropertiesTest {

    @Test
    void applicationYmlRegistersEveryCacheValueWithAnEnvOverride() {
        Map<String, Object> root = yamlRoot();
        @SuppressWarnings("unchecked")
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        @SuppressWarnings("unchecked")
        Map<String, Object> lab = (Map<String, Object>) app.get("lab");
        @SuppressWarnings("unchecked")
        Map<String, Object> cache = (Map<String, Object>) lab.get("cache");
        assertThat(cache).isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> seed = (Map<String, Object>) cache.get("seed");
        assertThat(stringOf(seed, "product-count")).isEqualTo("${LAB_CACHE_SEED_PRODUCT_COUNT:1000}");
        assertThat(stringOf(seed, "start-id")).isEqualTo("${LAB_CACHE_SEED_START_ID:90000}");
        assertThat(stringOf(seed, "protected-max-id"))
                .isEqualTo("${LAB_CACHE_SEED_PROTECTED_MAX_ID:10000}");
        assertThat(stringOf(seed, "avalanche-key-count"))
                .isEqualTo("${LAB_CACHE_SEED_AVALANCHE_KEY_COUNT:200}");
        assertThat(stringOf(seed, "avalanche-ttl-seconds"))
                .isEqualTo("${LAB_CACHE_SEED_AVALANCHE_TTL_SECONDS:60}");
        assertThat(stringOf(seed, "hot-key-index")).isEqualTo("${LAB_CACHE_SEED_HOT_KEY_INDEX:999}");
        assertThat(stringOf(seed, "hot-key-ttl-seconds"))
                .isEqualTo("${LAB_CACHE_SEED_HOT_KEY_TTL_SECONDS:300}");
        assertThat(stringOf(seed, "write-batch-size"))
                .isEqualTo("${LAB_CACHE_SEED_WRITE_BATCH_SIZE:200}");
        assertThat(defaultValueOf(stringOf(seed, "price"))).isEqualTo("99.00");
        assertThat(defaultValueOf(stringOf(seed, "name-prefix"))).isNotEmpty();
        assertThat(defaultValueOf(stringOf(seed, "status"))).isEqualTo("ON_SALE");

        @SuppressWarnings("unchecked")
        Map<String, Object> state = (Map<String, Object>) cache.get("state");
        assertThat(stringOf(state, "scan-count")).isEqualTo("${LAB_CACHE_STATE_SCAN_COUNT:500}");

        @SuppressWarnings("unchecked")
        Map<String, Object> keys = (Map<String, Object>) cache.get("keys");
        assertThat(stringOf(keys, "default-prefix"))
                .isEqualTo("${LAB_CACHE_KEYS_DEFAULT_PREFIX:cache:product:}");
        assertThat(stringOf(keys, "allowed-prefixes"))
                .isEqualTo("${LAB_CACHE_KEYS_ALLOWED_PREFIXES:cache:product:}");
        assertThat(stringOf(keys, "default-limit")).isEqualTo("${LAB_CACHE_KEYS_DEFAULT_LIMIT:100}");
        assertThat(stringOf(keys, "max-limit")).isEqualTo("${LAB_CACHE_KEYS_MAX_LIMIT:1000}");
        assertThat(stringOf(keys, "scan-count")).isEqualTo("${LAB_CACHE_KEYS_SCAN_COUNT:500}");
        assertThat(stringOf(keys, "value-max-bytes"))
                .isEqualTo("${LAB_CACHE_KEYS_VALUE_MAX_BYTES:2048}");
        assertThat(stringOf(keys, "reset-scan-count"))
                .isEqualTo("${LAB_CACHE_KEYS_RESET_SCAN_COUNT:1000}");
        assertThat(stringOf(keys, "reset-max-keys"))
                .isEqualTo("${LAB_CACHE_KEYS_RESET_MAX_KEYS:50000}");

        @SuppressWarnings("unchecked")
        Map<String, Object> penetration = (Map<String, Object>) cache.get("penetration");
        assertThat(stringOf(penetration, "mode")).isEqualTo("${LAB_CACHE_DEFENSE_MODE:off}");
        @SuppressWarnings("unchecked")
        Map<String, Object> nullCache = (Map<String, Object>) penetration.get("null-cache");
        assertThat(stringOf(nullCache, "key-prefix"))
                .isEqualTo("${LAB_CACHE_NULL_KEY_PREFIX:null:product:}");
        assertThat(stringOf(nullCache, "ttl-seconds")).isEqualTo("${LAB_CACHE_NULL_TTL_SECONDS:10}");
        assertThat(stringOf(nullCache, "jitter-seconds")).isEqualTo("${LAB_CACHE_NULL_JITTER_SECONDS:5}");
        @SuppressWarnings("unchecked")
        Map<String, Object> bloom = (Map<String, Object>) penetration.get("bloom");
        assertThat(stringOf(bloom, "key")).isEqualTo("${LAB_CACHE_BLOOM_KEY:bloom:product-ids}");
        assertThat(stringOf(bloom, "expected-insertions"))
                .isEqualTo("${LAB_CACHE_BLOOM_EXPECTED_INSERTIONS:10000}");
        assertThat(stringOf(bloom, "false-probability"))
                .isEqualTo("${LAB_CACHE_BLOOM_FALSE_PROBABILITY:0.01}");

        @SuppressWarnings("unchecked")
        Map<String, Object> breakdown = (Map<String, Object>) cache.get("breakdown");
        @SuppressWarnings("unchecked")
        Map<String, Object> mutex = (Map<String, Object>) breakdown.get("mutex");
        assertThat(stringOf(mutex, "key-prefix")).isEqualTo("${LAB_CACHE_MUTEX_KEY_PREFIX:lock:product:}");
        assertThat(stringOf(mutex, "wait-ms")).isEqualTo("${LAB_CACHE_MUTEX_WAIT_MS:200}");
        assertThat(stringOf(mutex, "lease-ms")).isEqualTo("${LAB_CACHE_MUTEX_LEASE_MS:2000}");
        assertThat(stringOf(mutex, "on-lock-timeout")).isEqualTo("${LAB_CACHE_MUTEX_ON_LOCK_TIMEOUT:db}");
        @SuppressWarnings("unchecked")
        Map<String, Object> logical = (Map<String, Object>) breakdown.get("logical-expire");
        assertThat(stringOf(logical, "logical-ttl-seconds"))
                .isEqualTo("${LAB_CACHE_LOGICAL_TTL_SECONDS:30}");
        assertThat(stringOf(logical, "retention-seconds"))
                .isEqualTo("${LAB_CACHE_LOGICAL_RETENTION_SECONDS:600}");
        assertThat(stringOf(logical, "max-entries")).isEqualTo("${LAB_CACHE_LOGICAL_MAX_ENTRIES:10000}");
        assertThat(stringOf(logical, "refresh-pool-size"))
                .isEqualTo("${LAB_CACHE_LOGICAL_REFRESH_POOL_SIZE:2}");
        assertThat(stringOf(logical, "refresh-queue-capacity"))
                .isEqualTo("${LAB_CACHE_LOGICAL_REFRESH_QUEUE_CAPACITY:100}");
        assertThat(stringOf(logical, "keep-alive-seconds"))
                .isEqualTo("${LAB_CACHE_LOGICAL_REFRESH_KEEP_ALIVE_SECONDS:60}");
        assertThat(stringOf(logical, "thread-name-prefix"))
                .isEqualTo("${LAB_CACHE_LOGICAL_REFRESH_THREAD_PREFIX:breakdown-refresh}");
    }

    @Test
    void yamlKeysBindToThePropertyClass() {
        LabCacheProperties bound = bind();

        assertThat(bound.getSeed().getProductCount()).isEqualTo(1000L);
        assertThat(bound.getSeed().getStartId()).isEqualTo(90_000L);
        assertThat(bound.getSeed().getPrice()).isEqualByComparingTo(new BigDecimal("99.00"));
        assertThat(bound.getSeed().getHotKeyIndex()).isEqualTo(999L);
        assertThat(bound.getPenetration().getMode()).isEqualTo("off");
        assertThat(bound.getPenetration().getNullCache().getKeyPrefix()).isEqualTo("null:product:");
        assertThat(bound.getPenetration().getNullCache().getJitterSeconds()).isEqualTo(5L);
        assertThat(bound.getPenetration().getBloom().getFalseProbability()).isEqualTo(0.01D);
        assertThat(bound.getState().getScanCount()).isEqualTo(500L);
        assertThat(bound.getBreakdown().getMutex().getKeyPrefix()).isEqualTo("lock:product:");
        assertThat(bound.getBreakdown().getMutex().getWaitMs()).isEqualTo(200L);
        assertThat(bound.getBreakdown().getMutex().getLeaseMs()).isEqualTo(2_000L);
        assertThat(bound.getBreakdown().getMutex().getOnLockTimeout()).isEqualTo("db");
        assertThat(bound.getBreakdown().getLogicalExpire().getLogicalTtlSeconds()).isEqualTo(30L);
        assertThat(bound.getBreakdown().getLogicalExpire().getRetentionSeconds()).isEqualTo(600L);
        assertThat(bound.getBreakdown().getLogicalExpire().getRefreshPoolSize()).isEqualTo(2);
        assertThat(bound.getBreakdown().getLogicalExpire().getRefreshQueueCapacity()).isEqualTo(100);
        assertThat(bound.getBreakdown().getLogicalExpire().getThreadNamePrefix())
                .isEqualTo("breakdown-refresh");
    }

    @Test
    void defaultsValidate() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();

        assertThatCode(properties::validate).doesNotThrowAnyException();
    }

    @Test
    void seedingInsideTheProtectedRangeIsRejected() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getSeed().setStartId(1_000L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("start-id must be greater than");
    }

    @Test
    void anAvalancheSetLargerThanTheDatasetIsRejected() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getSeed().setAvalancheKeyCount(6L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("avalanche-key-count must not exceed");
    }

    @Test
    void aHotKeyInsideTheAvalancheRangeIsRejected() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getSeed().setHotKeyIndex(1L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not address a key inside the avalanche range");
    }

    @Test
    void aHotKeyOutsideTheDatasetIsRejected() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        properties.getSeed().setHotKeyIndex(5L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must address a product inside the experiment range");
    }

    @Test
    void nonsenseDefenseParametersAreRejected() {
        LabCacheProperties jitterless = TestLabCacheProperties.defaults();
        jitterless.getPenetration().getNullCache().setJitterSeconds(-1L);
        assertThatThrownBy(jitterless::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("null-cache.jitter-seconds");

        LabCacheProperties missingMode = TestLabCacheProperties.defaults();
        missingMode.getPenetration().setMode(" ");
        assertThatThrownBy(missingMode::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("penetration.mode");

        LabCacheProperties badFpp = TestLabCacheProperties.defaults();
        badFpp.getPenetration().getBloom().setFalseProbability(1.5D);
        assertThatThrownBy(badFpp::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("false-probability");

        LabCacheProperties noBatching = TestLabCacheProperties.defaults();
        noBatching.getSeed().setWriteBatchSize(0);
        assertThatThrownBy(noBatching::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("write-batch-size");

        LabCacheProperties negativePrice = TestLabCacheProperties.defaults();
        negativePrice.getSeed().setPrice(new BigDecimal("-1"));
        assertThatThrownBy(negativePrice::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("price");
    }

    @Test
    void mutexTimesOutsideTheirContractAreRejected() {
        LabCacheProperties zeroWait = TestLabCacheProperties.defaults();
        zeroWait.getBreakdown().getMutex().setWaitMs(0L);
        assertThatThrownBy(zeroWait::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("mutex.wait-ms");

        // lease < wait would let a waiter acquire a lock whose holder is already
        // gone; the startup check is the only place that can catch it.
        LabCacheProperties shortLease = TestLabCacheProperties.defaults();
        shortLease.getBreakdown().getMutex().setWaitMs(3_000L);
        shortLease.getBreakdown().getMutex().setLeaseMs(1_000L);
        assertThatThrownBy(shortLease::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("lease-ms must be at least");

        LabCacheProperties badFallback = TestLabCacheProperties.defaults();
        badFallback.getBreakdown().getMutex().setOnLockTimeout("stampede");
        assertThatThrownBy(badFallback::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("on-lock-timeout must be db or stale");
    }

    @Test
    void logicalExpireWithoutAStaleWindowIsRejected() {
        LabCacheProperties inverted = TestLabCacheProperties.defaults();
        inverted.getBreakdown().getLogicalExpire().setRetentionSeconds(30L);
        assertThatThrownBy(inverted::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("retention-seconds must exceed logical-ttl-seconds");

        LabCacheProperties noPool = TestLabCacheProperties.defaults();
        noPool.getBreakdown().getLogicalExpire().setRefreshPoolSize(0);
        assertThatThrownBy(noPool::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-pool-size");

        LabCacheProperties unboundedQueue = TestLabCacheProperties.defaults();
        unboundedQueue.getBreakdown().getLogicalExpire().setRefreshQueueCapacity(0);
        assertThatThrownBy(unboundedQueue::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-queue-capacity");

        LabCacheProperties anonymousThreads = TestLabCacheProperties.defaults();
        anonymousThreads.getBreakdown().getLogicalExpire().setThreadNamePrefix(" ");
        assertThatThrownBy(anonymousThreads::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thread-name-prefix");
    }

    @Test
    void aStaleWindowShorterThanTheHotKeyLifetimeIsRejected() {
        // retention 300 == hot-key TTL 300: the stale entry is evicted exactly
        // when the L2 key expires, so every breakdown burst would find an empty
        // store and logical-expire would silently behave like `off`.
        LabCacheProperties tooShort = TestLabCacheProperties.defaults();
        tooShort.getBreakdown().getLogicalExpire().setRetentionSeconds(300L);
        assertThatThrownBy(tooShort::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed app.lab.cache.seed.hot-key-ttl-seconds");
    }

    /**
     * Binds the real {@code app.lab.cache} subtree out of {@code application.yml}.
     * Placeholders are reduced to their defaults because the test classpath has no
     * {@code Environment} to resolve them - which is exactly the documented default
     * an operator gets when no environment variable is set.
     */
    private static LabCacheProperties bind() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yamlRoot(), "", flat);
        return new Binder(new MapConfigurationPropertySource(flat))
                .bind("app.lab.cache", Bindable.of(LabCacheProperties.class))
                .orElseThrow(() -> new AssertionError("app.lab.cache did not bind"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlRoot() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        factory.afterPropertiesSet();
        return factory.getObject();
    }

    @SuppressWarnings("unchecked")
    private static void flatten(Map<String, Object> node, String path, Map<String, Object> leaves) {
        node.forEach((key, value) -> {
            String full = path.isEmpty() ? key : path + "." + key;
            if (value instanceof Map<?, ?>) {
                flatten((Map<String, Object>) value, full, leaves);
            } else if (value instanceof String) {
                String text = (String) value;
                leaves.put(full, text.startsWith("${") && text.indexOf(':') > 0
                    ? defaultValueOf(text) : text);
            } else {
                leaves.put(full, value);
            }
        });
    }

    private static String stringOf(Map<String, Object> node, String key) {
        Object value = node.get(key);
        assertThat(value).as("application.yml value of %s", key).isInstanceOf(String.class);
        return (String) value;
    }

    /** {@code ${ENV:default}} -&gt; {@code default}. */
    private static String defaultValueOf(String placeholder) {
        assertThat(placeholder).startsWith("${").endsWith("}");
        int separator = placeholder.indexOf(':');
        assertThat(separator).as("%s must carry a default", placeholder).isPositive();
        return placeholder.substring(separator + 1, placeholder.length() - 1);
    }
}
