package com.javalearn.lab.seckill.lab.thread;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup guard for {@link LabThreadProperties} — mirrors the
 * {@code LabCachePropertiesTest} contract: the defaults validate, and a missing or
 * nonsensical externalized value fails loudly instead of being hidden.
 */
class LabThreadPropertiesValidationTest {

    @Test
    void defaultsValidate() {
        assertThatCode(TestLabThreadProperties.defaults()::validate).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveTtlIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.setDefaultTtlSeconds(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("default-ttl-seconds");
    }

    @Test
    void heapCapAboveOneHundredIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getHeapLeak().setCapPercent(120L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("cap-percent");
    }

    @Test
    void zeroStarvationPoolSizeIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getStarvation().setPoolSize(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("pool-size");
    }

    @Test
    void negativeThreadLeakIntervalIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getThreadLeak().setSpawnIntervalMs(-1L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spawn-interval-ms");
    }
}
