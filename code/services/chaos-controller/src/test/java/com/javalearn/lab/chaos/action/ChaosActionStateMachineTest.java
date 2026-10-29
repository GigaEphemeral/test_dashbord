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
    void stopRequestWhileApplyingIsVisibleAtTheAppliedCheckpoint() {
        // stop-all may not revert mid-apply, but must not be a no-op either:
        // the request is recorded and consumed by the apply-completion
        // checkpoint so the revert runs immediately instead of waiting TTL.
        ChaosAction action = newKill();
        assertThat(action.claimRevert()).isFalse();              // APPLYING gate intact
        assertThat(action.requestStopWhileApplying()).isTrue();  // stop-all defers
        assertThat(action.isStopRequested()).isTrue();
        assertThat(action.markApplied()).isTrue();
        assertThat(action.isStopRequested()).isTrue();           // checkpoint sees the flag
        assertThat(action.claimRevert()).isTrue();               // deferred revert runs once
        assertThat(action.claimRevert()).isFalse();
    }

    @Test
    void stopRequestIsRefusedOnceTheApplyFinishedSoStopAllRevertsDirectly() {
        ChaosAction action = newKill();
        action.markApplied();
        assertThat(action.requestStopWhileApplying()).isFalse();
        assertThat(action.isStopRequested()).isFalse();
        assertThat(action.claimRevert()).isTrue(); // stop-all claims the revert itself
    }

    @Test
    void stopRequestOnFailedActionIsRefusedForWatchdogRetryPath() {
        // Indeterminate apply failure stays FAILED; stop-all must not pretend
        // to revert it mid-flight. The watchdog retry keeps owning the revert.
        ChaosAction action = newKill();
        action.markApplied();
        action.claimRevert();
        action.recordFailure(new IllegalStateException("docker start failed"));
        assertThat(action.requestStopWhileApplying()).isFalse();
        assertThat(action.claimRevert()).isTrue();
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

    @Test
    void viewRemainderIsNullOnceRevertedOrFailed() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        ChaosAction reverted = new ChaosAction("id-1", "jl-app-3", ChaosActionType.KILL, 1,
                now, now.plusSeconds(1));
        reverted.markApplied();
        assertThat(reverted.remainingViewMillis(now, false)).isEqualTo(1000L);
        reverted.markReverted();
        assertThat(reverted.remainingViewMillis(now, false)).isNull();
        assertThat(reverted.remainingViewMillis(now, true)).isNull();

        ChaosAction failed = new ChaosAction("id-1", "jl-app-3", ChaosActionType.KILL, 1,
                now, now.plusSeconds(1));
        failed.markApplied();
        failed.claimRevert();
        failed.recordFailure(new IllegalStateException("docker start failed"));
        assertThat(failed.remainingViewMillis(now, false)).isNull();
    }

    @Test
    void viewRemainderUsesTheFrozenValueWhilePaused() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        ChaosAction action = new ChaosAction("id-1", "jl-app-3", ChaosActionType.KILL, 1,
                now, now.plusSeconds(1));
        action.markApplied();

        assertThat(action.remainingViewMillis(now, false)).isEqualTo(1000L);
        assertThat(action.freeze(now.plusMillis(400))).isEqualTo(600L);
        // The live expiry would say 600ms only at the freeze instant; the frozen
        // value must win regardless of how far the clock has moved.
        assertThat(action.remainingViewMillis(now.plusSeconds(10), true)).isEqualTo(600L);
        // An action that was never frozen has no remainder to report.
        ChaosAction unfrozen = new ChaosAction("id-1", "jl-app-3", ChaosActionType.KILL, 1,
                now, now.plusSeconds(1));
        assertThat(unfrozen.remainingViewMillis(now, true)).isNull();
    }

    @Test
    void manualHoldHasNoExpirySoItNeverAutoReverts() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        ChaosAction action = new ChaosAction("id-m", "jl-app-3", ChaosActionType.KILL, null, now, null);

        assertThat(action.manualHold()).isTrue();
        assertThat(action.expiresAt()).isNull();
        // APPLYING and ACTIVE alike: a manual hold never counts as expired.
        assertThat(action.expiredAt(now.plusSeconds(86_400))).isFalse();
        assertThat(action.markApplied()).isTrue();
        assertThat(action.expiredAt(now.plusSeconds(86_400))).isFalse();
        assertThat(action.remainingViewMillis(now, false)).isNull();
        assertThat(action.toView().ttlSeconds()).isNull();
        assertThat(action.toView().expiresAt()).isNull();
    }

    @Test
    void manualHoldSurvivesFreezeAndResumeWithoutACountdown() {
        Instant now = Instant.parse("2026-10-26T10:00:00Z");
        ChaosAction action = new ChaosAction("id-m", "jl-app-3", ChaosActionType.FREEZE, null, now, null);
        action.markApplied();

        // pause() must not NPE on a null expiry and must not fabricate a TTL.
        assertThat(action.freeze(now.plusSeconds(10))).isEqualTo(-1L);
        assertThat(action.remainingMillis()).isEqualTo(-1L);
        assertThat(action.remainingViewMillis(now.plusSeconds(10), true)).isNull();

        // resume() must not move the expiry (there is none).
        assertThat(action.resumeAt(now.plusSeconds(20), 500)).isNull();
        assertThat(action.expiresAt()).isNull();
        assertThat(action.remainingViewMillis(now.plusSeconds(20), false)).isNull();
        assertThat(action.expiredAt(now.plusSeconds(3_600))).isFalse();

        // Stop-all / explicit revert still claims a manual hold.
        assertThat(action.claimRevert()).isTrue();
        assertThat(action.status()).isEqualTo(ChaosActionStatus.REVERTING);
    }
}
