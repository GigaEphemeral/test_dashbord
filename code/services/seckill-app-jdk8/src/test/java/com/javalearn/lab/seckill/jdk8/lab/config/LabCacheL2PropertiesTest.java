package com.javalearn.lab.seckill.jdk8.lab.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlMapFactoryBean;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.ConfigurationPropertySource;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;
import org.springframework.core.io.ClassPathResource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The three values B0-04 lifted out of {@code TwoLevelCache} (the L2 write TTL,
 * the {@code cache:} key prefix and the {@code clear()} SCAN hint) are now
 * configuration. These tests guard the two halves of that promise: the shipped
 * {@code application.yml} declares each one with a {@code LAB_CACHE_*} override,
 * and an override actually replaces the default at bind time.
 *
 * <p>Like every other lab property class, {@link LabCacheL2Properties} carries no
 * Java-side defaults — {@code application.yml} is the single source of defaults
 * and {@code validate()} fails startup rather than hiding a nonsensical value.
 */
class LabCacheL2PropertiesTest {

    @Test
    void applicationYmlRegistersEveryL2ValueWithAnEnvOverride() {
        Map<String, Object> l2 = l2Node(yamlRoot());

        assertThat(l2).isNotNull();
        // Each entry is exactly `${LAB_CACHE_*:default}`: the env name is the one
        // compose.base.yml and .env.example wire, the default is the historical
        // constant so an absent .env entry keeps the old behaviour.
        assertThat(stringOf(l2, "ttl-seconds")).isEqualTo("${LAB_CACHE_L2_TTL_SECONDS:300}");
        assertThat(stringOf(l2, "key-prefix")).isEqualTo("${LAB_CACHE_L2_KEY_PREFIX:cache:}");
        assertThat(stringOf(l2, "clear-scan-count"))
                .isEqualTo("${LAB_CACHE_L2_CLEAR_SCAN_COUNT:500}");
    }

    @Test
    void theDeclaredDefaultsBindAndValidate() {
        LabCacheL2Properties bound = bind(Arrays.asList(yamlDefaultsSource()));

        assertThat(bound.getTtlSeconds()).isEqualTo(300L);
        assertThat(bound.getKeyPrefix()).isEqualTo("cache:");
        assertThat(bound.getClearScanCount()).isEqualTo(500L);
        assertThatCode(bound::validate).doesNotThrowAnyException();
    }

    @Test
    void anEnvironmentOverrideReplacesTheDeclaredDefault() {
        // What Boot does for LAB_CACHE_L2_* : the resolved environment value
        // outranks the yml text, so the run gets a different L2 lifetime without
        // a rebuild. Ordering is the point - the first source wins.
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("app.lab.cache.l2.ttl-seconds", "45");
        overrides.put("app.lab.cache.l2.key-prefix", "lab-l2:");
        overrides.put("app.lab.cache.l2.clear-scan-count", "1000");

        LabCacheL2Properties bound = bind(Arrays.asList(
                new MapConfigurationPropertySource(overrides), yamlDefaultsSource()));

        assertThat(bound.getTtlSeconds()).isEqualTo(45L);
        assertThat(bound.getKeyPrefix()).isEqualTo("lab-l2:");
        assertThat(bound.getClearScanCount()).isEqualTo(1_000L);
    }

    @Test
    void nonsenseL2ValuesAreRejectedAtStartup() {
        LabCacheL2Properties expired = new LabCacheL2Properties();
        expired.setTtlSeconds(0L);
        expired.setKeyPrefix("cache:");
        expired.setClearScanCount(500L);
        assertThatThrownBy(expired::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.lab.cache.l2.ttl-seconds");

        // A blank prefix would make every L2 key start at the cache name, which
        // the seeder, the state reader and the preheat service all share; the
        // layout contract has to be explicit.
        LabCacheL2Properties prefixless = new LabCacheL2Properties();
        prefixless.setTtlSeconds(300L);
        prefixless.setKeyPrefix("  ");
        prefixless.setClearScanCount(500L);
        assertThatThrownBy(prefixless::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.lab.cache.l2.key-prefix");

        LabCacheL2Properties missingPrefix = new LabCacheL2Properties();
        missingPrefix.setTtlSeconds(300L);
        missingPrefix.setClearScanCount(500L);
        assertThatThrownBy(missingPrefix::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.lab.cache.l2.key-prefix");

        LabCacheL2Properties unboundedScan = new LabCacheL2Properties();
        unboundedScan.setTtlSeconds(300L);
        unboundedScan.setKeyPrefix("cache:");
        unboundedScan.setClearScanCount(0L);
        assertThatThrownBy(unboundedScan::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.lab.cache.l2.clear-scan-count");
    }

    private static LabCacheL2Properties bind(List<ConfigurationPropertySource> sources) {
        return new Binder(sources)
                .bind("app.lab.cache.l2", Bindable.of(LabCacheL2Properties.class))
                .orElseThrow(() -> new AssertionError("app.lab.cache.l2 did not bind"));
    }

    /**
     * The real {@code app.lab.cache.l2} subtree with placeholders reduced to their
     * declared defaults - the value an operator gets when no environment variable
     * is set, which is what the test classpath has.
     */
    private static MapConfigurationPropertySource yamlDefaultsSource() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yamlRoot(), "", flat);
        return new MapConfigurationPropertySource(flat);
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

    @SuppressWarnings("unchecked")
    private static Map<String, Object> l2Node(Map<String, Object> root) {
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> lab = (Map<String, Object>) app.get("lab");
        return (Map<String, Object>) ((Map<String, Object>) lab.get("cache")).get("l2");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yamlRoot() {
        YamlMapFactoryBean factory = new YamlMapFactoryBean();
        factory.setResources(new ClassPathResource("application.yml"));
        factory.afterPropertiesSet();
        return factory.getObject();
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
