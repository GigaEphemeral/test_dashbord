package com.javalearn.lab.seckill.lab.thread;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Startup guard for {@code app.lab.thread.lifecycle.*} (design §9.2, Task #33).
 *
 * <p>The state machine has hard minimums and a cross-field invariant, so a
 * nonsensical externalized value must fail startup instead of producing a demo
 * that can never reach TERMINATED or a reset that always times out.</p>
 */
class LifecycleDemoPropertiesValidationTest {

    @Test
    void defaultsValidate() {
        assertThatCode(TestLabThreadProperties.defaults()::validate).doesNotThrowAnyException();
    }

    @Test
    void nonPositiveAutoAdvanceIntervalIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getLifecycle().setAutoAdvanceIntervalMs(0L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("auto-advance-interval-ms");
    }

    @Test
    void maxStepsBelowFourIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getLifecycle().setMaxSteps(3L);
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("at least 4")
                .hasMessageContaining("max-steps");
    }

    @Test
    void resetJoinBudgetMustExceedTheMonitorHold() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getLifecycle().setShutdownJoinTimeoutMs(
                properties.getLifecycle().getMonitorHoldMs());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("shutdown-join-timeout-ms")
                .hasMessageContaining("monitor-hold-ms");
    }

    @Test
    void collidingThreadNamesAreRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getLifecycle().setMonitorThreadName(
                properties.getLifecycle().getThreadName());
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must differ");
    }

    @Test
    void blankThreadNameIsRejected() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getLifecycle().setThreadName("  ");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must not be blank");
    }
}
