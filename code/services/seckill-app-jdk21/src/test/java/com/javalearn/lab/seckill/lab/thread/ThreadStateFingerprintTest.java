package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Design §9.1 promises a per-mode THREAD-STATE fingerprint (deadlock → blocked,
 * stuck → waiting, cpu-burn → runnable). FaultActionTest proves cleanup; the
 * lifecycle tests prove TTL; nothing proved the symptom side — a mode degrading
 * to a no-op (or to the wrong state) would have passed every existing test and
 * silently taught the wrong fingerprint in the demo. These three tests observe
 * the real {@link Thread.State} of the participants while each fault runs.
 *
 * <p>Sampling a transitory state is made robust rather than racy by a generous
 * observation budget (seconds) against a symptom whose window is milliseconds
 * per cycle: the assertion only fails if the state NEVER occurs.</p>
 */
class ThreadStateFingerprintTest {

    @Test
    void defaultDeadlockContentionReallyBlocksParticipants() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getDeadlock().setHoldMs(40L); // widen the contention window per cycle
        FaultAction action = newAction(FaultMode.DEADLOCK, properties);
        action.startThreads();
        try {
            Set<Long> ids = action.threadIds();
            assertThat(observedWithin(ids, Thread.State.BLOCKED, Duration.ofSeconds(4)))
                    .as("the drainable inversion must still show real BLOCKED windows")
                    .isTrue();
        } finally {
            action.stop();
        }
    }

    @Test
    void stuckParticipantsParkInWaitingStates() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        FaultAction action = newAction(FaultMode.STUCK, properties);
        action.startThreads();
        try {
            // await(timeout) parks in TIMED_WAITING (WAITING accepted too); the
            // stuck fingerprint is "not blocked, parked on a never-opened latch".
            boolean waiting = observedWithin(action.threadIds(), Thread.State.WAITING,
                    Duration.ofSeconds(2))
                    || observedWithin(action.threadIds(), Thread.State.TIMED_WAITING,
                            Duration.ofSeconds(2));
            assertThat(waiting).isTrue();
        } finally {
            action.stop();
        }
    }

    @Test
    void cpuBurnParticipantsSpinAsRunnable() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        FaultAction action = newAction(FaultMode.CPU_BURN, properties);
        action.startThreads();
        try {
            assertThat(observedWithin(action.threadIds(), Thread.State.RUNNABLE,
                    Duration.ofSeconds(2))).isTrue();
        } finally {
            action.stop();
        }
    }

    private static FaultAction newAction(FaultMode mode, LabThreadProperties properties) {
        return new FaultAction(mode, HoldMode.MANUAL, Duration.ofSeconds(120),
                properties, clock(0L), new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);
    }

    /** {@code true} when any recorded thread is seen in {@code wanted} within the budget. */
    private static boolean observedWithin(Set<Long> ids, Thread.State wanted, Duration budget) {
        long deadline = System.nanoTime() + budget.toNanos();
        try {
            while (System.nanoTime() < deadline) {
                for (Thread thread : Thread.getAllStackTraces().keySet()) {
                    if (ids.contains(thread.threadId()) && thread.getState() == wanted) {
                        return true;
                    }
                }
                TimeUnit.MICROSECONDS.sleep(2_000L);
            }
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return false;
        }
        return false;
    }
}
