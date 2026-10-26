package com.javalearn.lab.seckill.lab.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Guards the avalanche configuration contract: every knob is registered in
 * {@code application.yml} with an environment override (engineering rule 1),
 * the defaults bind to the property class, and startup refuses nonsensical
 * values instead of silently disabling a defense.
 */
class LabCacheAvalanchePropertiesTest {

    @Test
    void applicationYmlRegistersEveryAvalancheValueWithAnEnvOverride() {
        Map<String, Object> cache = cacheSection();
        @SuppressWarnings("unchecked")
        Map<String, Object> avalanche = (Map<String, Object>) cache.get("avalanche");
        assertThat(avalanche).as("app.lab.cache.avalanche").isNotNull();

        @SuppressWarnings("unchecked")
        Map<String, Object> jitter = (Map<String, Object>) avalanche.get("ttl-jitter");
        assertThat(stringOf(jitter, "percent")).isEqualTo("${LAB_CACHE_TTL_JITTER_PERCENT:20}");

        @SuppressWarnings("unchecked")
        Map<String, Object> l1 = (Map<String, Object>) avalanche.get("l1-cache");
        assertThat(stringOf(l1, "refresh-interval-seconds"))
                .isEqualTo("${LAB_CACHE_L1_REFRESH_INTERVAL_SECONDS:10}");
        assertThat(stringOf(l1, "stale-window-seconds"))
                .isEqualTo("${LAB_CACHE_L1_STALE_WINDOW_SECONDS:60}");
        assertThat(stringOf(l1, "max-entries")).isEqualTo("${LAB_CACHE_L1_MAX_ENTRIES:10000}");
        assertThat(stringOf(l1, "refresh-pool-size"))
                .isEqualTo("${LAB_CACHE_L1_REFRESH_POOL_SIZE:2}");
        assertThat(stringOf(l1, "refresh-queue-capacity"))
                .isEqualTo("${LAB_CACHE_L1_REFRESH_QUEUE_CAPACITY:100}");
        assertThat(stringOf(l1, "keep-alive-seconds"))
                .isEqualTo("${LAB_CACHE_L1_KEEP_ALIVE_SECONDS:60}");
        assertThat(stringOf(l1, "thread-name-prefix"))
                .isEqualTo("${LAB_CACHE_L1_REFRESH_THREAD_PREFIX:avalanche-l1-refresh}");

        @SuppressWarnings("unchecked")
        Map<String, Object> fallback = (Map<String, Object>) avalanche.get("db-fallback");
        assertThat(stringOf(fallback, "permits")).isEqualTo("${LAB_CACHE_DB_FALLBACK_PERMITS:50}");
        assertThat(stringOf(fallback, "period-ms")).isEqualTo("${LAB_CACHE_DB_FALLBACK_PERIOD_MS:1000}");
    }

    @Test
    void yamlKeysBindToThePropertyClassAndTheDefaultsValidate() {
        LabCacheAvalancheProperties bound = bind();

        assertThat(bound.getTtlJitter().getPercent()).isEqualTo(20D);
        assertThat(bound.getL1Cache().getRefreshIntervalSeconds()).isEqualTo(10L);
        assertThat(bound.getL1Cache().getStaleWindowSeconds()).isEqualTo(60L);
        assertThat(bound.getL1Cache().getMaxEntries()).isEqualTo(10_000L);
        assertThat(bound.getL1Cache().getRefreshPoolSize()).isEqualTo(2);
        assertThat(bound.getL1Cache().getRefreshQueueCapacity()).isEqualTo(100);
        assertThat(bound.getL1Cache().getKeepAliveSeconds()).isEqualTo(60L);
        assertThat(bound.getL1Cache().getThreadNamePrefix()).isEqualTo("avalanche-l1-refresh");
        assertThat(bound.getDbFallback().getPermits()).isEqualTo(50L);
        assertThat(bound.getDbFallback().getPeriodMs()).isEqualTo(1_000L);

        assertThatCode(bound::validate).doesNotThrowAnyException();
    }

    @Test
    void aNegativeOrHundredPercentJitterIsRejectedAtStartup() {
        LabCacheAvalancheProperties properties = defaults();
        properties.getTtlJitter().setPercent(-1D);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ttl-jitter.percent");

        properties.getTtlJitter().setPercent(100D);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ttl-jitter.percent");
    }

    @Test
    void aZeroDbFallbackPermitsOrPeriodIsRejected() {
        LabCacheAvalancheProperties properties = defaults();
        properties.getDbFallback().setPermits(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db-fallback.permits");

        properties = defaults();
        properties.getDbFallback().setPeriodMs(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("db-fallback.period-ms");
    }

    @Test
    void theStaleWindowMustOutliveTheRefreshInterval() {
        LabCacheAvalancheProperties properties = defaults();
        properties.getL1Cache().setStaleWindowSeconds(10L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-window-seconds")
                .hasMessageContaining("refresh-interval-seconds");
    }

    @Test
    void aZeroRefreshIntervalOrStaleWindowIsRejected() {
        LabCacheAvalancheProperties properties = defaults();
        properties.getL1Cache().setRefreshIntervalSeconds(0L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-interval-seconds");

        properties = defaults();
        properties.getL1Cache().setStaleWindowSeconds(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("stale-window-seconds");
    }

    @Test
    void zeroBoundsAndABlankThreadNamePrefixAreRejected() {
        LabCacheAvalancheProperties properties = defaults();
        properties.getL1Cache().setMaxEntries(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("max-entries");

        properties = defaults();
        properties.getL1Cache().setRefreshPoolSize(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-pool-size");

        properties = defaults();
        properties.getL1Cache().setRefreshQueueCapacity(0);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("refresh-queue-capacity");

        properties = defaults();
        properties.getL1Cache().setKeepAliveSeconds(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("keep-alive-seconds");

        properties = defaults();
        properties.getL1Cache().setThreadNamePrefix("  ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thread-name-prefix");
    }

    // ---------------------------------------------------------------- helpers

    static LabCacheAvalancheProperties defaults() {
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

    private static LabCacheAvalancheProperties bind() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yamlRoot(), "", flat);
        return new Binder(new MapConfigurationPropertySource(flat))
                .bind("app.lab.cache.avalanche", Bindable.of(LabCacheAvalancheProperties.class))
                .orElseThrow(() -> new AssertionError("app.lab.cache.avalanche did not bind"));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cacheSection() {
        Map<String, Object> root = yamlRoot();
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> lab = (Map<String, Object>) app.get("lab");
        return (Map<String, Object>) lab.get("cache");
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
            if (value instanceof Map<?, ?> children) {
                flatten((Map<String, Object>) children, full, leaves);
            } else if (value instanceof String text && text.startsWith("${")) {
                leaves.put(full, defaultValueOf(text));
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
