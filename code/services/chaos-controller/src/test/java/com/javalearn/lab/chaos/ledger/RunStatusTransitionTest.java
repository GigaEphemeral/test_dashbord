package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.Test;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Locks the whole design §10.5 transition table, including the two rules that
 * are easy to break: terminal states never move again, and {@code UNKNOWN} is a
 * recovery-only state that only accepts an operator closure.
 */
class RunStatusTransitionTest {

    private static final Map<RunStatus, Set<RunStatus>> EXPECTED = expected();

    @Test
    void everyStatusHasExactlyTheDesignedTargets() {
        for (RunStatus status : RunStatus.values()) {
            assertThat(status.allowedTargets())
                    .as("allowed targets of %s", status)
                    .containsExactlyInAnyOrderElementsOf(EXPECTED.get(status));
        }
    }

    @Test
    void validTransitionsAreAccepted() {
        for (Map.Entry<RunStatus, Set<RunStatus>> entry : EXPECTED.entrySet()) {
            for (RunStatus target : entry.getValue()) {
                assertThat(entry.getKey().canTransitionTo(target))
                        .as("%s -> %s", entry.getKey(), target)
                        .isTrue();
            }
        }
    }

    @Test
    void invalidTransitionsAreRejected() {
        for (RunStatus from : RunStatus.values()) {
            for (RunStatus to : RunStatus.values()) {
                if (!EXPECTED.get(from).contains(to)) {
                    assertThat(from.canTransitionTo(to))
                            .as("%s -> %s must be rejected", from, to)
                            .isFalse();
                }
            }
        }
    }

    @Test
    void terminalStatesCannotTransitionAnywhere() {
        for (RunStatus terminal : EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.INVALID, RunStatus.ABORTED)) {
            assertThat(terminal.isTerminal()).as("%s is terminal", terminal).isTrue();
            for (RunStatus target : RunStatus.values()) {
                assertThat(terminal.canTransitionTo(target))
                        .as("%s -> %s", terminal, target)
                        .isFalse();
            }
        }
    }

    @Test
    void unknownIsNeverProducedByATransition() {
        for (RunStatus from : RunStatus.values()) {
            assertThat(from.canTransitionTo(RunStatus.UNKNOWN))
                    .as("%s must not transition into UNKNOWN; only restart recovery writes it", from)
                    .isFalse();
        }
    }

    @Test
    void unknownAcceptsOnlyOperatorClosure() {
        assertThat(RunStatus.UNKNOWN.canTransitionTo(RunStatus.FAILED)).isTrue();
        assertThat(RunStatus.UNKNOWN.canTransitionTo(RunStatus.ABORTED)).isTrue();
        assertThat(RunStatus.UNKNOWN.canTransitionTo(RunStatus.RUNNING)).isFalse();
        assertThat(RunStatus.UNKNOWN.canTransitionTo(RunStatus.COMPLETED)).isFalse();
        assertThat(RunStatus.UNKNOWN.isTerminal()).isFalse();
    }

    @Test
    void incompleteStatesMatchTheRestartRecoverySet() {
        for (RunStatus status : RunStatus.values()) {
            boolean expected = status == RunStatus.RUNNING
                    || status == RunStatus.STOPPING
                    || status == RunStatus.VERIFYING;
            assertThat(status.isUnfinished()).as("isUnfinished(%s)", status).isEqualTo(expected);
        }
    }

    private static Map<RunStatus, Set<RunStatus>> expected() {
        Map<RunStatus, Set<RunStatus>> map = new EnumMap<>(RunStatus.class);
        map.put(RunStatus.DRAFT, EnumSet.of(RunStatus.VALIDATING, RunStatus.ABORTED));
        map.put(RunStatus.VALIDATING, EnumSet.of(RunStatus.READY, RunStatus.FAILED, RunStatus.ABORTED));
        map.put(RunStatus.READY, EnumSet.of(RunStatus.RUNNING, RunStatus.FAILED, RunStatus.ABORTED));
        map.put(RunStatus.RUNNING, EnumSet.of(RunStatus.STOPPING, RunStatus.FAILED, RunStatus.ABORTED));
        map.put(RunStatus.STOPPING, EnumSet.of(RunStatus.VERIFYING, RunStatus.FAILED, RunStatus.ABORTED));
        map.put(RunStatus.VERIFYING, EnumSet.of(RunStatus.COMPLETED, RunStatus.FAILED,
                RunStatus.INVALID, RunStatus.ABORTED));
        map.put(RunStatus.COMPLETED, EnumSet.noneOf(RunStatus.class));
        map.put(RunStatus.FAILED, EnumSet.noneOf(RunStatus.class));
        map.put(RunStatus.INVALID, EnumSet.noneOf(RunStatus.class));
        map.put(RunStatus.ABORTED, EnumSet.noneOf(RunStatus.class));
        map.put(RunStatus.UNKNOWN, EnumSet.of(RunStatus.FAILED, RunStatus.ABORTED));
        return map;
    }
}
