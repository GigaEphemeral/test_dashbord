package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.time.Duration;
import java.util.Set;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.liveThreadIds;
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
}
