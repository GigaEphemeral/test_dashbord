package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.liveThreadIds;
import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.waitFor;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * The heart of the acceptance criteria: every fault mode spawns daemon participant
 * threads, and {@code stop()} leaves nothing behind — zero live participants and
 * none of the recorded thread ids present in the JVM afterwards. The two
 * memory-bearing modes (heap-leak / oom) are covered by {@link HeapLeakAllocatorTest}.
 */
class FaultActionTest {

    /**
     * Thread-generating modes only; heap-leak and oom allocate on the real runtime
     * heap and are verified separately, so they are excluded here.
     */
    @ParameterizedTest
    @EnumSource(value = FaultMode.class, names = {"DEADLOCK", "STUCK", "CPU_BURN", "THREAD_LEAK", "STARVATION"})
    void stopLeavesNoThreadBehind(FaultMode mode) {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        MutableClock fixedClock = clock(0L);
        FaultAction action = new FaultAction(mode, HoldMode.AUTO, Duration.ofSeconds(120),
                properties, fixedClock, new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);

        action.startThreads();
        Set<Long> startedIds = action.threadIds();
        assertThat(startedIds).as("%s must spawn participant threads", mode).isNotEmpty();
        assertThat(action.getState()).isEqualTo(LifecycleState.ACTIVE);

        Set<Long> liveWhileActive = liveThreadIds();
        assertThat(liveWhileActive).as("%s threads really run", mode)
                .containsAnyElementsOf(startedIds);

        action.stop();

        assertThat(action.getState()).isEqualTo(LifecycleState.STOPPED);
        assertThat(action.liveThreadCount()).as("%s live participants after stop", mode).isZero();
        Set<Long> liveAfterStop = liveThreadIds();
        for (long id : startedIds) {
            assertThat(liveAfterStop).as("thread %d of %s must die on stop", id, mode)
                    .doesNotContain(id);
        }
    }

    @org.junit.jupiter.api.Test
    void participantsAreDaemonAndNamedForTheLab() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        FaultAction action = new FaultAction(FaultMode.STUCK, HoldMode.AUTO, Duration.ofSeconds(120),
                properties, clock(0L), new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);
        action.startThreads();
        try {
            Thread worker = Thread.getAllStackTraces().keySet().stream()
                    .filter(t -> t.getName().startsWith("thread-lab-stuck"))
                    .findFirst().orElseThrow();
            assertThat(worker.isDaemon()).isTrue();
        } finally {
            action.stop();
        }
    }

    /**
     * Review finding #1 (deadlock fidelity): the gated {@code true-deadlock}
     * variant must form the genuine monitor cycle design §9.1 promises — one the
     * JVM's own detector reports, which is exactly what makes {@code jstack} print
     * {@code Found one Java-level deadlock} — and must document honestly that stop
     * only unregisters (threads await the restart of the disposable instance).
     */
    @Test
    void trueDeadlockFormsVmDetectableMonitorCycleAndIsReclaimedOnlyByRestart() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getDeadlock().setTrueDeadlock(true);
        properties.getDeadlock().setHoldMs(30L);
        FaultAction action = newDeadlockAction(properties);
        action.startThreads();
        Set<Long> ids = action.threadIds();
        assertThat(ids).hasSize(2);

        // The cycle is permanent once formed, so the poll terminates deterministically.
        Set<Long> detected = waitFor("VM-detected deadlock cycle", Duration.ofSeconds(5), () -> {
            ThreadMXBean bean = ManagementFactory.getThreadMXBean();
            long[] deadlocked = bean.findDeadlockedThreads();
            Set<Long> found = deadlocked == null ? Set.of()
                    : new HashSet<>(Arrays.stream(deadlocked).boxed().toList());
            return found.containsAll(ids) ? found : null;
        });
        assertThat(detected).as("hold-and-wait inversion must be VM-detectable").containsAll(ids);

        Map<Long, Thread.State> states = Thread.getAllStackTraces().entrySet().stream()
                .filter(entry -> ids.contains(entry.getKey().threadId()))
                .collect(java.util.stream.Collectors.toMap(
                        entry -> entry.getKey().threadId(), entry -> entry.getKey().getState()));
        assertThat(states.values()).as("blocked monitor entry is the deadlock fingerprint")
                .containsOnly(Thread.State.BLOCKED);

        // stop() can only tear down what a permanent cycle allows: the action is
        // STOPPED and re-injectable, the deadlocked daemon threads await restart.
        action.stop();
        assertThat(action.getState()).isEqualTo(LifecycleState.STOPPED);
        assertThat(action.liveThreadCount())
                .as("a real deadlock is not interruptible — reclaimed by JVM restart")
                .isEqualTo(2);

        // Re-injection starts clean: monitors are per action, so the old cycle
        // cannot wedge the new fault (it forms its own, independent cycle).
        FaultAction restart = newDeadlockAction(properties);
        restart.startThreads();
        try {
            Set<Long> restartIds = restart.threadIds();
            assertThat(waitFor("second independent cycle", Duration.ofSeconds(5), () -> {
                long[] deadlocked = ManagementFactory.getThreadMXBean().findDeadlockedThreads();
                Set<Long> found = deadlocked == null ? Set.of()
                        : new HashSet<>(Arrays.stream(deadlocked).boxed().toList());
                return found.containsAll(restartIds) ? found : null;
            })).containsAll(restartIds);
        } finally {
            restart.stop(); // unregisters; the 2 extra daemon threads die with the JVM
        }
    }

    /**
     * Review finding #2 (oom fidelity): the gate-passed oom sink must allocate
     * WITHOUT a ceiling — the old 100%-of-max pre-check pinned the heap just under
     * the cliff and could never produce the 断崖/进程退出 fingerprint — and stop()
     * must drain everything it retained. A 1KB chunk on the shared test JVM keeps
     * this safe.
     */
    @Test
    void oomSinkAllocatesUnboundedAndDrainsToZeroOnStop() throws Exception {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        properties.getOom().setThrowawayInstance(true); // honest marking; gate is service-level
        FaultAction action = new FaultAction(FaultMode.OOM, HoldMode.MANUAL, Duration.ofSeconds(120),
                properties, clock(0L), new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);
        action.startThreads();
        try {
            assertThat(waitFor("oom sink allocating", Duration.ofSeconds(5),
                    () -> action.heapLeakedBytes() > 0 ? action.heapLeakedBytes() : null))
                    .isGreaterThan(0L);
        } finally {
            action.stop();
        }
        assertThat(action.liveThreadCount()).isZero();
        assertThat(action.heapLeakedBytes()).as("final drain releases every chunk").isZero();
    }

    /**
     * The capped heap-leak wiring on the REAL runtime (previously only the cap math
     * was tested): bytes grow under the stop flag, stay within 80% of max, and
     * drain to zero on stop.
     */
    @Test
    void heapLeakGrowsWithinCapOnRealRuntimeAndDrainsToZero() {
        LabThreadProperties properties = TestLabThreadProperties.defaults();
        FaultAction action = new FaultAction(FaultMode.HEAP_LEAK, HoldMode.MANUAL, Duration.ofSeconds(120),
                properties, clock(0L), new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);
        action.startThreads();
        try {
            long leaked = waitFor("heap-leak growth", Duration.ofSeconds(5),
                    () -> action.heapLeakedBytes() > 0 ? action.heapLeakedBytes() : null);
            assertThat(leaked).isGreaterThan(0L);
            assertThat(leaked)
                    .as("bounded growth never exceeds the configured cap of max heap")
                    .isLessThanOrEqualTo(Runtime.getRuntime().maxMemory() * 80L / 100L);
        } finally {
            action.stop();
        }
        assertThat(action.heapLeakedBytes()).isZero();
        assertThat(action.liveThreadCount()).isZero();
    }

    private static FaultAction newDeadlockAction(LabThreadProperties properties) {
        return new FaultAction(FaultMode.DEADLOCK, HoldMode.MANUAL, Duration.ofSeconds(120),
                properties, clock(0L), new ManualTtlScheduler(),
                new ThreadLabMetrics(new SimpleMeterRegistry()), null, null);
    }
}
