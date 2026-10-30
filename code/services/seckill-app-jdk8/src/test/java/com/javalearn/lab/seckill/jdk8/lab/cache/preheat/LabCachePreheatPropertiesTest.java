package com.javalearn.lab.seckill.jdk8.lab.cache.preheat;

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
 * Guards the M4-3 contracts a reviewer cannot see in the code: every
 * {@code app.lab.cache.preheat.*} value is registered in {@code application.yml}
 * with an environment override, the nested {@code l1} subtree binds, and
 * startup refuses nonsensical preheat geometry.
 */
class LabCachePreheatPropertiesTest {

    @Test
    void applicationYmlRegistersEveryPreheatValueWithAnEnvOverride() {
        Map<String, Object> preheat = preheatNode();

        assertThat(stringOf(preheat, "enabled")).isEqualTo("${LAB_CACHE_PREHEAT_ENABLED:true}");
        assertThat(stringOf(preheat, "top-n")).isEqualTo("${LAB_CACHE_PREHEAT_TOP_N:200}");
        assertThat(stringOf(preheat, "hot-list-key"))
                .isEqualTo("${LAB_CACHE_PREHEAT_HOT_LIST_KEY:hot:products}");

        @SuppressWarnings("unchecked")
        Map<String, Object> tracker = (Map<String, Object>) preheat.get("tracker");
        assertThat(stringOf(tracker, "capacity"))
                .isEqualTo("${LAB_CACHE_PREHEAT_TRACKER_CAPACITY:100000}");
        assertThat(stringOf(tracker, "decay-interval-seconds"))
                .isEqualTo("${LAB_CACHE_PREHEAT_TRACKER_DECAY_INTERVAL_SECONDS:30}");
        assertThat(stringOf(tracker, "flush-interval-seconds"))
                .isEqualTo("${LAB_CACHE_PREHEAT_TRACKER_FLUSH_INTERVAL_SECONDS:10}");

        @SuppressWarnings("unchecked")
        Map<String, Object> load = (Map<String, Object>) preheat.get("load");
        assertThat(stringOf(load, "chunk-size"))
                .isEqualTo("${LAB_CACHE_PREHEAT_LOAD_CHUNK_SIZE:50}");
        assertThat(stringOf(load, "chunk-pause-ms"))
                .isEqualTo("${LAB_CACHE_PREHEAT_LOAD_CHUNK_PAUSE_MS:50}");
        assertThat(stringOf(load, "startup-delay-ms"))
                .isEqualTo("${LAB_CACHE_PREHEAT_STARTUP_DELAY_MS:1000}");
        assertThat(stringOf(load, "periodic-interval-seconds"))
                .isEqualTo("${LAB_CACHE_PREHEAT_PERIODIC_INTERVAL_SECONDS:300}");
        assertThat(stringOf(load, "scheduler-pool-size"))
                .isEqualTo("${LAB_CACHE_PREHEAT_SCHEDULER_POOL_SIZE:1}");
        assertThat(stringOf(load, "thread-name-prefix"))
                .isEqualTo("${LAB_CACHE_PREHEAT_THREAD_PREFIX:cache-preheat}");

        @SuppressWarnings("unchecked")
        Map<String, Object> l1 = (Map<String, Object>) preheat.get("l1");
        assertThat(stringOf(l1, "maximum-size")).isEqualTo("${LAB_CACHE_L1_MAXIMUM_SIZE:10000}");
        assertThat(stringOf(l1, "refresh-after-write-seconds"))
                .isEqualTo("${LAB_CACHE_L1_REFRESH_AFTER_WRITE_SECONDS:10}");
        assertThat(stringOf(l1, "expire-after-write-seconds"))
                .isEqualTo("${LAB_CACHE_L1_EXPIRE_AFTER_WRITE_SECONDS:60}");
        assertThat(stringOf(l1, "enabled")).isEqualTo("${LAB_CACHE_L1_ENABLED:true}");
        assertThat(stringOf(l1, "expire-after-access-seconds"))
                .isEqualTo("${LAB_CACHE_L1_EXPIRE_AFTER_ACCESS_SECONDS:0}");
    }

    @Test
    void yamlKeysBindToThePropertyClassIncludingTheL1Subtree() {
        LabCachePreheatProperties bound = bind();

        assertThat(bound.isEnabled()).isTrue();
        assertThat(bound.getTopN()).isEqualTo(200L);
        assertThat(bound.getHotListKey()).isEqualTo("hot:products");
        assertThat(bound.getTracker().getCapacity()).isEqualTo(100_000L);
        assertThat(bound.getTracker().getDecayIntervalSeconds()).isEqualTo(30L);
        assertThat(bound.getTracker().getFlushIntervalSeconds()).isEqualTo(10L);
        assertThat(bound.getLoad().getChunkSize()).isEqualTo(50L);
        assertThat(bound.getLoad().getChunkPauseMs()).isEqualTo(50L);
        assertThat(bound.getLoad().getStartupDelayMs()).isEqualTo(1_000L);
        assertThat(bound.getLoad().getPeriodicIntervalSeconds()).isEqualTo(300L);
        assertThat(bound.getLoad().getSchedulerPoolSize()).isEqualTo(1L);
        assertThat(bound.getLoad().getThreadNamePrefix()).isEqualTo("cache-preheat");
        assertThat(bound.getL1().getMaximumSize()).isEqualTo(10_000L);
        assertThat(bound.getL1().getRefreshAfterWriteSeconds()).isEqualTo(10L);
        assertThat(bound.getL1().getExpireAfterWriteSeconds()).isEqualTo(60L);
        assertThat(bound.getL1().isEnabled()).isTrue();
        assertThat(bound.getL1().getExpireAfterAccessSeconds()).isZero();
    }

    @Test
    void defaultsValidate() {
        assertThatCode(TestLabCachePreheatProperties.defaults()::validate)
                .doesNotThrowAnyException();
    }

    @Test
    void aTopNWiderThanTheTrackedCapacityIsRejected() {
        LabCachePreheatProperties properties = TestLabCachePreheatProperties.defaults();
        properties.setTopN(properties.getTracker().getCapacity() + 1L);

        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("top-n must not exceed");
    }

    @Test
    void zeroOrNegativeGeometryIsRejected() {
        LabCachePreheatProperties zeroTopN = TestLabCachePreheatProperties.defaults();
        zeroTopN.setTopN(0L);
        assertThatThrownBy(zeroTopN::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("preheat.top-n");

        LabCachePreheatProperties zeroCapacity = TestLabCachePreheatProperties.defaults();
        zeroCapacity.getTracker().setCapacity(0L);
        assertThatThrownBy(zeroCapacity::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("tracker.capacity");

        LabCachePreheatProperties zeroDecay = TestLabCachePreheatProperties.defaults();
        zeroDecay.getTracker().setDecayIntervalSeconds(0L);
        assertThatThrownBy(zeroDecay::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("decay-interval-seconds");

        LabCachePreheatProperties zeroFlush = TestLabCachePreheatProperties.defaults();
        zeroFlush.getTracker().setFlushIntervalSeconds(0L);
        assertThatThrownBy(zeroFlush::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("flush-interval-seconds");

        LabCachePreheatProperties zeroChunk = TestLabCachePreheatProperties.defaults();
        zeroChunk.getLoad().setChunkSize(0L);
        assertThatThrownBy(zeroChunk::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-size");

        LabCachePreheatProperties negativePause = TestLabCachePreheatProperties.defaults();
        negativePause.getLoad().setChunkPauseMs(-1L);
        assertThatThrownBy(negativePause::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("chunk-pause-ms");

        LabCachePreheatProperties zeroPeriod = TestLabCachePreheatProperties.defaults();
        zeroPeriod.getLoad().setPeriodicIntervalSeconds(0L);
        assertThatThrownBy(zeroPeriod::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("periodic-interval-seconds");

        LabCachePreheatProperties zeroPool = TestLabCachePreheatProperties.defaults();
        zeroPool.getLoad().setSchedulerPoolSize(0L);
        assertThatThrownBy(zeroPool::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("scheduler-pool-size");
    }

    @Test
    void missingNamesAndAnInvalidL1WindowAreRejected() {
        LabCachePreheatProperties blankKey = TestLabCachePreheatProperties.defaults();
        blankKey.setHotListKey(" ");
        assertThatThrownBy(blankKey::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("hot-list-key");

        LabCachePreheatProperties blankPrefix = TestLabCachePreheatProperties.defaults();
        blankPrefix.getLoad().setThreadNamePrefix("");
        assertThatThrownBy(blankPrefix::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("thread-name-prefix");

        LabCachePreheatProperties zeroSize = TestLabCachePreheatProperties.defaults();
        zeroSize.getL1().setMaximumSize(0L);
        assertThatThrownBy(zeroSize::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("l1.maximum-size");

        LabCachePreheatProperties inverted = TestLabCachePreheatProperties.defaults();
        inverted.getL1().setExpireAfterWriteSeconds(
                inverted.getL1().getRefreshAfterWriteSeconds());
        assertThatThrownBy(inverted::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must exceed refresh-after-write-seconds");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> preheatNode() {
        Map<String, Object> root = yamlRoot();
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> lab = (Map<String, Object>) app.get("lab");
        Map<String, Object> cache = (Map<String, Object>) lab.get("cache");
        assertThat(cache).isNotNull();
        Map<String, Object> preheat = (Map<String, Object>) cache.get("preheat");
        assertThat(preheat).isNotNull();
        return preheat;
    }

    private static LabCachePreheatProperties bind() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yamlRoot(), "", flat);
        return new Binder(new MapConfigurationPropertySource(flat))
                .bind("app.lab.cache.preheat", Bindable.of(LabCachePreheatProperties.class))
                .orElseThrow(() -> new AssertionError("app.lab.cache.preheat did not bind"));
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
