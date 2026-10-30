package com.javalearn.lab.seckill.jdk8.admission.config;

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
 * B4-01 startup contract of {@link BusinessPoolProperties}: the shipped
 * {@code application.yml} carries the six values with {@code BUSINESS_POOL_*}
 * overrides, a coherent configuration validates, and every nonsensical value
 * fails startup with a message naming the environment variable an operator has
 * to change.
 */
class BusinessPoolPropertiesTest {

    @Test
    void applicationYmlRegistersEveryPoolValueWithAnEnvOverride() {
        Map<String, Object> pool = poolNode();

        assertThat(stringOf(pool, "core-pool-size"))
                .isEqualTo("${BUSINESS_POOL_CORE_SIZE:4}");
        assertThat(stringOf(pool, "max-pool-size"))
                .isEqualTo("${BUSINESS_POOL_MAX_SIZE:8}");
        assertThat(stringOf(pool, "queue-capacity"))
                .isEqualTo("${BUSINESS_POOL_QUEUE_CAPACITY:50}");
        assertThat(stringOf(pool, "keep-alive-seconds"))
                .isEqualTo("${BUSINESS_POOL_KEEP_ALIVE_SECONDS:60}");
        assertThat(stringOf(pool, "rejection-policy"))
                .isEqualTo("${BUSINESS_POOL_REJECTION_POLICY:AbortPolicy}");
        assertThat(stringOf(pool, "thread-name-prefix"))
                .isEqualTo("${BUSINESS_POOL_THREAD_NAME_PREFIX:business-pool}");
    }

    @Test
    void theDeclaredDefaultsBindAndValidate() {
        Map<String, Object> flat = new LinkedHashMap<>();
        flatten(yamlRoot(), "", flat);
        BusinessPoolProperties bound = new Binder(new MapConfigurationPropertySource(flat))
                .bind("app.business-pool", Bindable.of(BusinessPoolProperties.class))
                .orElseThrow(() -> new AssertionError("app.business-pool did not bind"));

        assertThat(bound.getCorePoolSize()).isEqualTo(4);
        assertThat(bound.getMaxPoolSize()).isEqualTo(8);
        assertThat(bound.getQueueCapacity()).isEqualTo(50);
        assertThat(bound.getKeepAliveSeconds()).isEqualTo(60L);
        assertThat(bound.getRejectionPolicy()).isEqualTo("AbortPolicy");
        assertThat(bound.getThreadNamePrefix()).isEqualTo("business-pool");
        assertThatCode(bound::validate).doesNotThrowAnyException();
    }

    @Test
    void aPositiveCoherentConfigurationValidates() {
        assertThatCode(BusinessPoolPropertiesTest::validProperties).doesNotThrowAnyException();
    }

    @Test
    void everyInvalidValueFailsStartupWithAnEnvNamedMessage() {
        BusinessPoolProperties zeroCore = validProperties();
        zeroCore.setCorePoolSize(0);
        assertThatThrownBy(zeroCore::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_CORE_SIZE");

        BusinessPoolProperties invertedMax = validProperties();
        invertedMax.setMaxPoolSize(invertedMax.getCorePoolSize() - 1);
        assertThatThrownBy(invertedMax::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_MAX_SIZE");

        BusinessPoolProperties zeroQueue = validProperties();
        zeroQueue.setQueueCapacity(0);
        assertThatThrownBy(zeroQueue::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_QUEUE_CAPACITY");

        BusinessPoolProperties negativeKeepAlive = validProperties();
        negativeKeepAlive.setKeepAliveSeconds(-1L);
        assertThatThrownBy(negativeKeepAlive::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_KEEP_ALIVE_SECONDS");

        BusinessPoolProperties unknownPolicy = validProperties();
        unknownPolicy.setRejectionPolicy("ExplodePolicy");
        assertThatThrownBy(unknownPolicy::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_REJECTION_POLICY");

        BusinessPoolProperties blankPrefix = validProperties();
        blankPrefix.setThreadNamePrefix(" ");
        assertThatThrownBy(blankPrefix::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSINESS_POOL_THREAD_NAME_PREFIX");
    }

    private static BusinessPoolProperties validProperties() {
        BusinessPoolProperties properties = new BusinessPoolProperties();
        properties.setCorePoolSize(1);
        properties.setMaxPoolSize(2);
        properties.setQueueCapacity(3);
        properties.setKeepAliveSeconds(4L);
        properties.setRejectionPolicy("AbortPolicy");
        properties.setThreadNamePrefix("business-pool-test");
        return properties;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> poolNode() {
        Map<String, Object> root = yamlRoot();
        Map<String, Object> app = (Map<String, Object>) root.get("app");
        Map<String, Object> pool = (Map<String, Object>) app.get("business-pool");
        assertThat(pool).isNotNull();
        return pool;
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
        for (Map.Entry<String, Object> entry : node.entrySet()) {
            String key = entry.getKey();
            Object value = entry.getValue();
            String full = path.isEmpty() ? key : path + "." + key;
            if (value instanceof Map) {
                flatten((Map<String, Object>) value, full, leaves);
            } else if (value instanceof String) {
                String text = (String) value;
                if (!text.startsWith("${")) {
                    leaves.put(full, text);
                } else if (text.indexOf(':') < 0) {
                    // Internal cross-reference such as ${spring.application.name}:
                    // it carries no env override to assert, and the referenced key
                    // is checked where it is defined.
                    leaves.put(full, text);
                } else {
                    leaves.put(full, defaultValueOf(text));
                }
            } else {
                leaves.put(full, value);
            }
        }
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
