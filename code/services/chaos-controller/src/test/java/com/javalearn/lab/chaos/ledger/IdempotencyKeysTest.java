package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The Idempotency-Key header contract: optional, 1-128 visible ASCII. */
class IdempotencyKeysTest {

    @Test
    void anAbsentOrBlankHeaderIsNotIdempotentInsteadOfAnError() {
        assertThat(IdempotencyKeys.normalize(null)).isNull();
        assertThat(IdempotencyKeys.normalize("")).isNull();
        assertThat(IdempotencyKeys.normalize("   ")).isNull();
    }

    @Test
    void acceptsOneAndOneHundredTwentyEightVisibleAsciiCharacters() {
        assertThat(IdempotencyKeys.normalize("k")).isEqualTo("k");
        String max = "a".repeat(128);
        assertThat(IdempotencyKeys.normalize(max)).isEqualTo(max);
        // Trimmed edges are accepted, the inner value is kept verbatim.
        assertThat(IdempotencyKeys.normalize("  start-run-1  ")).isEqualTo("start-run-1");
    }

    @Test
    void rejectsLongKeysSpacesAndControlCharactersWith400() {
        assertThatThrownBy(() -> IdempotencyKeys.normalize("a".repeat(129)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("Idempotency-Key")
                .hasMessageContaining("128");

        assertThatThrownBy(() -> IdempotencyKeys.normalize("start run 1"))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("non-visible-ASCII");

        assertThatThrownBy(() -> IdempotencyKeys.normalize("start\u0000run"))
                .isInstanceOf(InvalidLedgerRequestException.class);

        assertThatThrownBy(() -> IdempotencyKeys.normalize("启动"))
                .isInstanceOf(InvalidLedgerRequestException.class);
    }
}
