package com.javalearn.lab.chaos.action;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Directly pins the {@link ChaosAction} state-machine guarantees that keep the
 * TTL task, the watchdog and {@code stop-all} from corrupting a running apply:
 * no revert may be claimed while the fault is still being applied, at most one
 * revert may be claimed at a time, FAILED stays claimable for retries and a
 * superseded (replaced) action can never be reverted again.
 */
class ChaosActionStateMachineTest {

    private ChaosAction newKill() {
        Instant now = Instant.now();
        return new ChaosAction("id-1", "jl-app-3", ChaosActionType.KILL, 1, now, now.plusSeconds(1));
    }

    @Test
    void freshActionIsApplyingAndNotClaimable() {
        ChaosAction action = newKill();
        assertThat(action.status()).isEqualTo(ChaosActionStatus.APPLYING);
        // TTL/watchdog must not revert while the Docker stop is still running.
        assertThat(action.claimRevert()).isFalse();
        assertThat(action.status()).isEqualTo(ChaosActionStatus.APPLYING);
    }

    @Test
    void appliedActionCanOnlyBeClaimedOnce() {
        ChaosAction action = newKill();
        assertThat(action.markApplied()).isTrue();
        assertThat(action.status()).isEqualTo(ChaosActionStatus.ACTIVE);

        assertThat(action.claimRevert()).isTrue();
        assertThat(action.claimRevert()).isFalse(); // TTL vs watchdog vs stop-all: one wins
        assertThat(action.status()).isEqualTo(ChaosActionStatus.REVERTING);
    }

    @Test
    void failedActionRemainsClaimableForWatchdogRetry() {
        ChaosAction action = newKill();
        action.markApplied();
        action.claimRevert();
        action.recordFailure(new IllegalStateException("docker start failed"));

        assertThat(action.status()).isEqualTo(ChaosActionStatus.FAILED);
        assertThat(action.lastError()).isEqualTo("IllegalStateException: docker start failed");
        assertThat(action.claimRevert()).isTrue(); // watchdog retry path
    }

    @Test
    void supersededFailedActionIsNeverRevertedAgain() {
        // Mirrors the register() replacement protocol: the FAILED check and the
        // map replacement happen under the action monitor, so a watchdog claim
        // racing in between can no longer revert the (now replaced) action.
        ChaosAction oldAction = newKill();
        oldAction.markApplied();
        oldAction.claimRevert();
        oldAction.recordFailure(new IllegalStateException("docker start failed"));

        boolean superseded;
        synchronized (oldAction) {
            superseded = oldAction.status() == ChaosActionStatus.FAILED;
            if (superseded) {
                oldAction.markSuperseded();
            }
        }
        assertThat(superseded).isTrue();
        assertThat(oldAction.status()).isEqualTo(ChaosActionStatus.REVERTED);
        // The watchdog holds a stale reference; claiming must now fail so the
        // old kill's revert can never abort a freshly applied replacement.
        assertThat(oldAction.claimRevert()).isFalse();
    }
}
