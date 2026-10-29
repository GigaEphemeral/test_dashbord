package com.javalearn.lab.chaos.schedule;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B3-00b create-time matrix: schema, bounds, normalisation and the item cap.
 * Every rejection is asserted to be an {@link InvalidLedgerRequestException}
 * naming the offending field, so the console can show a useful 400.
 */
class FaultScheduleValidatorTest {

    private LedgerProperties ledger;
    private FaultScheduleProperties schedule;
    private FaultScheduleValidator validator;

    @BeforeEach
    void setUp() {
        ledger = new LedgerProperties();
        schedule = new FaultScheduleProperties();
        validator = new FaultScheduleValidator(ledger, new ChaosProperties(), schedule);
    }

    @Test
    void acceptsAndNormalisesAWellFormedEntry() {
        Map<String, Object> raw = entry();
        raw.put("action", "FREEZE");
        raw.put("recovery", "AUTO");
        raw.put("ttlSeconds", 30);

        List<FaultScheduleEntry> entries = validator.validate(List.of(raw));

        assertThat(entries).containsExactly(
                new FaultScheduleEntry(10, "jl-app-1", "freeze", "auto", 30));
    }

    @Test
    void nullScheduleMeansNoFaults() {
        assertThat(validator.validate(null)).isEmpty();
    }

    @Test
    void ttlMinusOneBecomesAManualHoldEvenWhenRecoveryWasAuto() {
        Map<String, Object> raw = entry();
        raw.put("recovery", "auto");
        raw.put("ttlSeconds", -1);

        assertThat(validator.validate(List.of(raw))).containsExactly(
                new FaultScheduleEntry(10, "jl-app-1", "kill", "manual", null));
    }

    @Test
    void manualRecoveryDropsAPositiveTtl() {
        Map<String, Object> raw = entry();
        raw.put("recovery", "manual");
        raw.put("ttlSeconds", 30);

        assertThat(validator.validate(List.of(raw))).containsExactly(
                new FaultScheduleEntry(10, "jl-app-1", "kill", "manual", null));
    }

    @Test
    void duplicateOffsetsAndTargetsAreAllowed() {
        assertThat(validator.validate(List.of(entry(), entry()))).hasSize(2);
    }

    @Test
    void deniesMoreItemsThanTheLedgerCap() {
        ledger.setMaxFaultItems(2);
        assertThatThrownBy(() -> validator.validate(List.of(entry(), entry(), entry())))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("max-fault-items");
    }

    @Test
    void deniesANonObjectEntry() {
        List<Map<String, Object>> raw = Arrays.asList(entry(), null);
        assertThatThrownBy(() -> validator.validate(raw))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("faultSchedule[1]")
                .hasMessageContaining("object");
    }

    @Test
    void deniesUnknownKeys() {
        Map<String, Object> raw = entry();
        raw.put("delay", 5);
        assertThatThrownBy(() -> validator.validate(List.of(raw)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("unknown key 'delay'");
    }

    @Test
    void deniesMissingAtSecondsAndNegativeAndTooLargeOffsets() {
        Map<String, Object> missing = entry();
        missing.remove("atSeconds");
        assertThatThrownBy(() -> validator.validate(List.of(missing)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("atSeconds is required");

        Map<String, Object> negative = entry();
        negative.put("atSeconds", -1);
        assertThatThrownBy(() -> validator.validate(List.of(negative)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("at least 0");

        schedule.setMaxOffsetSeconds(60);
        Map<String, Object> tooLate = entry();
        tooLate.put("atSeconds", 61);
        assertThatThrownBy(() -> validator.validate(List.of(tooLate)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("max-offset-seconds");
    }

    @Test
    void deniesNonIntegerOffsetsAndTtls() {
        Map<String, Object> fractional = entry();
        fractional.put("atSeconds", 1.5);
        assertThatThrownBy(() -> validator.validate(List.of(fractional)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("atSeconds must be an integer");

        Map<String, Object> fractionalTtl = entry();
        fractionalTtl.put("ttlSeconds", 2.5);
        assertThatThrownBy(() -> validator.validate(List.of(fractionalTtl)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("ttlSeconds must be an integer");
    }

    @Test
    void deniesBadTargets() {
        for (String target : List.of("", " ", "http://app-1:8080", "jl app", "-jl-app-1")) {
            Map<String, Object> raw = entry();
            raw.put("target", target);
            assertThatThrownBy(() -> validator.validate(List.of(raw)))
                    .isInstanceOf(InvalidLedgerRequestException.class)
                    .hasMessageContaining("target");
        }
    }

    @Test
    void deniesUnknownActions() {
        Map<String, Object> raw = entry();
        raw.put("action", "explode");
        assertThatThrownBy(() -> validator.validate(List.of(raw)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("action");
    }

    @Test
    void deniesMissingOrUnknownRecovery() {
        Map<String, Object> missing = entry();
        missing.remove("recovery");
        assertThatThrownBy(() -> validator.validate(List.of(missing)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("recovery is required");

        Map<String, Object> unknown = entry();
        unknown.put("recovery", "eventually");
        assertThatThrownBy(() -> validator.validate(List.of(unknown)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("'auto' or 'manual'");
    }

    @Test
    void autoRequiresATtlInsideTheChaosBound() {
        Map<String, Object> missing = entry();
        missing.remove("ttlSeconds");
        assertThatThrownBy(() -> validator.validate(List.of(missing)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("ttlSeconds is required");

        Map<String, Object> zero = entry();
        zero.put("ttlSeconds", 0);
        assertThatThrownBy(() -> validator.validate(List.of(zero)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("between 1 and 600");

        Map<String, Object> tooLong = entry();
        tooLong.put("ttlSeconds", 601);
        assertThatThrownBy(() -> validator.validate(List.of(tooLong)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("between 1 and 600");

        Map<String, Object> tooNegative = entry();
        tooNegative.put("ttlSeconds", -2);
        assertThatThrownBy(() -> validator.validate(List.of(tooNegative)))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("between 1 and 600");
    }

    private static Map<String, Object> entry() {
        Map<String, Object> raw = new HashMap<>();
        raw.put("atSeconds", 10);
        raw.put("target", "jl-app-1");
        raw.put("action", "kill");
        raw.put("recovery", "auto");
        raw.put("ttlSeconds", 30);
        return raw;
    }
}
