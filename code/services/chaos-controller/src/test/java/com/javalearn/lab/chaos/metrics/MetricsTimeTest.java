package com.javalearn.lab.chaos.metrics;

import com.javalearn.lab.chaos.error.InvalidMetricsRequestException;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ISO-8601, epoch seconds and epoch millis are accepted; anything else is a 400. */
class MetricsTimeTest {

    @Test
    void parsesIso8601WithZuluAndOffset() {
        Instant expected = Instant.parse("2026-10-28T10:15:30Z");

        assertThat(MetricsTime.parse("2026-10-28T10:15:30Z", "from")).isEqualTo(expected);
        assertThat(MetricsTime.parse("2026-10-28T12:15:30+02:00", "from")).isEqualTo(expected);
        assertThat(MetricsTime.parse(" 2026-10-28T10:15:30.123Z ", "to"))
                .isEqualTo(Instant.parse("2026-10-28T10:15:30.123Z"));
    }

    @Test
    void numbersBelowTheThresholdAreSecondsAndAboveAreMillis() {
        assertThat(MetricsTime.parse("1793140800", "from"))
                .isEqualTo(Instant.ofEpochSecond(1793140800L));
        assertThat(MetricsTime.parse("1793140800.5", "from"))
                .isEqualTo(Instant.ofEpochSecond(1793140800L, 500_000_000L));
        assertThat(MetricsTime.parse("1793140800123", "from"))
                .isEqualTo(Instant.ofEpochMilli(1793140800123L));
    }

    @Test
    void blankAndGarbageAreRejectedWithTheParameterName() {
        assertThatThrownBy(() -> MetricsTime.parse(null, "from"))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("'from' is required");
        assertThatThrownBy(() -> MetricsTime.parse(" ", "to"))
                .hasMessageContaining("'to' is required");
        assertThatThrownBy(() -> MetricsTime.parse("yesterday", "from"))
                .isInstanceOf(InvalidMetricsRequestException.class)
                .hasMessageContaining("'from'")
                .hasMessageContaining("ISO-8601");
    }
}
