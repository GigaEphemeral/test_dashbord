package com.javalearn.lab.seckill.catalog.cache;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The {@code ttl-jitter} arithmetic: the spread must be exactly ±percent around
 * the base TTL, in whole seconds, never below 1s, and driven only by the
 * injected random source so the assertions are deterministic (no RNG, no sleep).
 */
class TtlJitterTest {

    private static final Duration BASE = Duration.ofSeconds(60);

    @Test
    void theLowerBoundIsBaseMinusPercent() {
        TtlJitter jitter = new TtlJitter(20D, () -> 0D);

        assertThat(jitter.jitter(BASE)).isEqualTo(Duration.ofSeconds(48));
    }

    @Test
    void theUpperBoundIsBasePlusPercent() {
        // [0,1) never reaches 1, but 0.999999 rounds to the +20% edge.
        TtlJitter jitter = new TtlJitter(20D, () -> 0.999999D);

        assertThat(jitter.jitter(BASE)).isEqualTo(Duration.ofSeconds(72));
    }

    @Test
    void theMidpointIsTheBaseTtl() {
        TtlJitter jitter = new TtlJitter(20D, () -> 0.5D);

        assertThat(jitter.jitter(BASE)).isEqualTo(BASE);
    }

    @Test
    void aSampleOfDrawsStaysInsideTheBandAndIsActuallySpread() {
        TtlJitter jitter = new TtlJitter(20D, new Sequence());
        Set<Long> distinct = new HashSet<>();

        for (int i = 0; i < 1_000; i++) {
            long seconds = jitter.jitter(BASE).toSeconds();
            assertThat(seconds).isBetween(48L, 72L);
            distinct.add(seconds);
        }

        // A "jitter" that always returns the base would be a silent no-op: the
        // design promise is a distribution, not a single value.
        assertThat(distinct).hasSizeGreaterThan(10);
    }

    @Test
    void zeroPercentIsAnIdentityButStillHonest() {
        TtlJitter jitter = new TtlJitter(0D, new Sequence());

        for (int i = 0; i < 100; i++) {
            assertThat(jitter.jitter(BASE)).isEqualTo(BASE);
        }
    }

    @Test
    void theResultIsNeverZeroEvenForATinyBaseTtl() {
        TtlJitter jitter = new TtlJitter(99D, () -> 0D);

        // 1s * 0.01 rounds to 0; a zero TTL would DELETE the L2 entry.
        assertThat(jitter.jitter(Duration.ofSeconds(1))).isEqualTo(Duration.ofSeconds(1));
    }

    @Test
    void aNegativeOrHundredPercentIsRejected() {
        assertThatThrownBy(() -> new TtlJitter(-1D, () -> 0D))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new TtlJitter(100D, () -> 0D))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aNonPositiveBaseTtlIsRejected() {
        TtlJitter jitter = new TtlJitter(20D, () -> 0.5D);

        assertThatThrownBy(() -> jitter.jitter(Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> jitter.jitter(Duration.ofSeconds(-1)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theConfiguredPercentIsExposed() {
        assertThat(new TtlJitter(20D, () -> 0.5D).percent()).isEqualTo(20D);
    }

    /** A deterministic sweep of the [0,1) range, independent of any RNG. */
    private static final class Sequence implements java.util.function.DoubleSupplier {

        private final AtomicInteger next = new AtomicInteger();

        @Override
        public double getAsDouble() {
            return (next.getAndIncrement() % 100) / 100D;
        }
    }
}
