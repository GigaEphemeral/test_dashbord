package com.javalearn.lab.seckill.catalog.cache;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 熔断降级 budget: a fixed permits-per-period window, fully driven by an
 * injected clock, so "beyond the budget" is asserted without sleeping.
 */
class AvalancheLimiterTest {

    private final AtomicLong now = new AtomicLong(1_000_000L);
    private final AvalancheLimiter limiter = new AvalancheLimiter(2L, 1_000L, now::get);

    @Test
    void admitsExactlyThePermitsAndRejectsTheRest() {
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();
        assertThat(limiter.tryAcquire()).isFalse();

        assertThat(limiter.acquired()).isEqualTo(2L);
        assertThat(limiter.rejected()).isEqualTo(2L);
    }

    @Test
    void theBudgetRefillsOnlyAfterThePeriod() {
        limiter.tryAcquire();
        limiter.tryAcquire();
        assertThat(limiter.tryAcquire()).isFalse();

        now.addAndGet(999L);                       // still inside the window
        assertThat(limiter.tryAcquire()).isFalse();

        now.addAndGet(1L);                         // exactly one period later
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isTrue();
        assertThat(limiter.tryAcquire()).isFalse();

        // Lifetime counters, not per-window ones: the console shows pressure.
        assertThat(limiter.acquired()).isEqualTo(4L);
        assertThat(limiter.rejected()).isEqualTo(3L);
    }

    @Test
    void theConfiguredBudgetIsExposed() {
        assertThat(limiter.permits()).isEqualTo(2L);
        assertThat(limiter.periodMillis()).isEqualTo(1_000L);
    }

    @Test
    void zeroOrNegativePermitsAreRejected() {
        assertThatThrownBy(() -> new AvalancheLimiter(0L, 1_000L, now::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Permits");
        assertThatThrownBy(() -> new AvalancheLimiter(-1L, 1_000L, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aZeroOrNegativePeriodIsRejected() {
        assertThatThrownBy(() -> new AvalancheLimiter(1L, 0L, now::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Period");
        assertThatThrownBy(() -> new AvalancheLimiter(1L, -1L, now::get))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
