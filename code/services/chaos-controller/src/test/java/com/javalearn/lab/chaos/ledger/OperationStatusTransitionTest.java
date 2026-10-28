package com.javalearn.lab.chaos.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Operation contract transition table: a request may be settled directly or via
 * PENDING_CONFIRMATION, recovery is the only writer of UNKNOWN, and terminal
 * verdicts are never rewritten.
 */
class OperationStatusTransitionTest {

    @Test
    void requestedMaySettleDirectlyOrWaitForConfirmation() {
        assertThat(OperationStatus.REQUESTED.canTransitionTo(OperationStatus.APPLIED)).isTrue();
        assertThat(OperationStatus.REQUESTED.canTransitionTo(OperationStatus.RESTORED)).isTrue();
        assertThat(OperationStatus.REQUESTED.canTransitionTo(OperationStatus.FAILED)).isTrue();
        assertThat(OperationStatus.REQUESTED.canTransitionTo(OperationStatus.PENDING_CONFIRMATION)).isTrue();
        assertThat(OperationStatus.REQUESTED.canTransitionTo(OperationStatus.UNKNOWN)).isTrue();
    }

    @Test
    void pendingConfirmationSettlesOrBecomesUnknownAfterARestart() {
        assertThat(OperationStatus.PENDING_CONFIRMATION.canTransitionTo(OperationStatus.APPLIED)).isTrue();
        assertThat(OperationStatus.PENDING_CONFIRMATION.canTransitionTo(OperationStatus.RESTORED)).isTrue();
        assertThat(OperationStatus.PENDING_CONFIRMATION.canTransitionTo(OperationStatus.FAILED)).isTrue();
        assertThat(OperationStatus.PENDING_CONFIRMATION.canTransitionTo(OperationStatus.UNKNOWN)).isTrue();
        assertThat(OperationStatus.PENDING_CONFIRMATION.canTransitionTo(OperationStatus.REQUESTED)).isFalse();
    }

    @Test
    void terminalAndUnknownStatesAreNotTransitionTargetsOfEachOther() {
        for (OperationStatus terminal : new OperationStatus[]{OperationStatus.APPLIED,
                OperationStatus.RESTORED, OperationStatus.FAILED, OperationStatus.UNKNOWN}) {
            assertThat(terminal.isTerminal()).isTrue();
            assertThat(terminal.isUnconfirmed()).isFalse();
            assertThat(terminal.canTransitionTo(OperationStatus.APPLIED)).isFalse();
            assertThat(terminal.canTransitionTo(OperationStatus.PENDING_CONFIRMATION)).isFalse();
        }
        assertThat(OperationStatus.REQUESTED.isUnconfirmed()).isTrue();
        assertThat(OperationStatus.PENDING_CONFIRMATION.isUnconfirmed()).isTrue();
        assertThat(OperationStatus.REQUESTED.isTerminal()).isFalse();
    }
}
