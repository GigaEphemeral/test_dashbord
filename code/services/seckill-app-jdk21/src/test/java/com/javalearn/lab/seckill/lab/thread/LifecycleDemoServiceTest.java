package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.lab.api.LifecycleDemoView;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.MutableClock;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.liveThreadIds;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The design §9.2 contract of {@link LifecycleDemoService}: a dedicated thread
 * really passes through NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING →
 * TERMINATED, every assertion reads the real {@link Thread#getState()}, freeze
 * really stops advancement and captures through the sink, resume continues from
 * the frozen step, reset leaves no thread behind.
 *
 * <p>Deterministic by construction: auto-advance runs on a
 * {@link ManualTtlScheduler} (tests fire it), timestamps come from a
 * {@link MutableClock}, and the demo thread states are sampled with a generous
 * budget rather than slept for. BLOCKED / WAITING / TIMED_WAITING are real
 * windows (monitor hold / untimed wait / sleep), so they are observable.
 */
class LifecycleDemoServiceTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ManualTtlScheduler scheduler = new ManualTtlScheduler();
    private final MutableClock clock = clock(1_000L);
    private final LabThreadProperties properties = TestLabThreadProperties.defaults();
    private final CapturingSink sink = new CapturingSink();

    private LifecycleDemoMetrics metrics;
    private LifecycleDemoService service;

    @BeforeEach
    void setUp() {
        properties.getLifecycle().setEnabled(true);
        metrics = new LifecycleDemoMetrics(registry);
        service = new LifecycleDemoService(properties, clock, scheduler, metrics, sink);
        metrics.bind(service);
    }

    @AfterEach
    void tearDown() {
        try {
            service.reset();
        } catch (BizException notStarted) {
            // already NEW — nothing to tear down
        }
    }

    @Test
    void startsInNewAndBecomesReallyRunnable() {
        LifecycleDemoView idle = service.view();
        assertThat(idle.state()).isEqualTo("NEW");
        assertThat(idle.stateCode()).isZero();
        assertThat(idle.started()).isFalse();
        assertThat(idle.alive()).isFalse();
        assertThat(idle.maxSteps()).isEqualTo(6);

        LifecycleDemoView started = service.start();
        assertThat(started.started()).isTrue();
        assertThat(started.frozen()).isFalse();
        assertThat(started.threadName()).isEqualTo("thread-lab-demo-test");
        assertThat(started.threadId()).isPositive();
        assertThat(started.machineStage()).isEqualTo("RUNNABLE");
        assertThat(scheduler.pendingCount()).isEqualTo(1);
        assertThat(scheduler.latest().delay()).isEqualTo(Duration.ofMillis(100));

        awaitState(DemoState.RUNNABLE);
        assertThat(service.view().state()).isEqualTo("RUNNABLE");
        assertThat(service.view().stateCode()).isEqualTo(1);
    }

    @Test
    void stepWalksTheSixRealJvmStatesAndTerminates() {
        service.start();
        awaitState(DemoState.RUNNABLE);

        service.step(); // RUNNABLE -> BLOCKED (hashCode contention on a held monitor)
        awaitState(DemoState.BLOCKED);
        assertThat(service.view().stateCode()).isEqualTo(2);

        service.step(); // BLOCKED -> WAITING (Object.wait with no timeout)
        awaitState(DemoState.WAITING);
        assertThat(service.view().stateCode()).isEqualTo(3);

        service.step(); // WAITING -> TIMED_WAITING (Thread.sleep)
        awaitState(DemoState.TIMED_WAITING);
        assertThat(service.view().stateCode()).isEqualTo(4);

        service.step(); // TIMED_WAITING -> TERMINATED
        awaitState(DemoState.TERMINATED);
        LifecycleDemoView terminated = service.view();
        assertThat(terminated.stateCode()).isEqualTo(5);
        assertThat(terminated.alive()).isFalse();
        assertThat(terminated.step()).isEqualTo(4);
        assertThat(anyLiveThreadNamed()).as("no demo thread survives TERMINATED").isFalse();
    }

    @Test
    void freezeStopsAdvancementAndCapturesTheRealStateOnce() {
        service.start();
        awaitState(DemoState.RUNNABLE);
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        LifecycleDemoView frozen = service.freeze();
        assertThat(frozen.frozen()).isTrue();
        assertThat(frozen.state()).isEqualTo("RUNNABLE");
        assertThat(frozen.step()).isZero();
        assertThat(scheduler.pendingCount()).as("freeze cancels auto-advance").isZero();

        assertThat(sink.captures).hasSize(1);
        LifecycleSnapshot snapshot = sink.captures.get(0);
        assertThat(snapshot.state()).isEqualTo("RUNNABLE");
        assertThat(snapshot.stateCode()).isEqualTo(1);
        assertThat(snapshot.step()).isZero();
        assertThat(snapshot.reason()).isEqualTo("freeze");
        assertThat(snapshot.threadId()).isEqualTo(frozen.threadId());
        assertThat(snapshot.threadName()).isEqualTo("thread-lab-demo-test");
        assertThat(snapshot.capturedAtEpochMs()).isEqualTo(1_000L);

        // Frozen really means no transition, even if a timer were left behind.
        sleep(250L);
        assertThat(service.liveState()).isEqualTo(DemoState.RUNNABLE);
        assertThat(service.view().step()).isZero();
        assertConflict(() -> service.step(), "frozen");

        LifecycleDemoView resumed = service.resume();
        assertThat(resumed.frozen()).isFalse();
        assertThat(scheduler.pendingCount()).as("resume re-arms auto-advance").isEqualTo(1);

        // Auto-advance continues from the frozen step: 0 -> 1, into BLOCKED.
        scheduler.fireAllPending();
        awaitState(DemoState.BLOCKED);
        assertThat(service.view().step()).isEqualTo(1);
    }

    @Test
    void resetTearsDownAThreadBlockedOnAMonitorAndReturnsToNew() {
        service.start();
        service.step();
        awaitState(DemoState.BLOCKED);
        long threadId = service.view().threadId();
        assertThat(threadId).isPositive();

        LifecycleDemoView reset = service.reset();

        assertThat(reset.state()).isEqualTo("NEW");
        assertThat(reset.stateCode()).isZero();
        assertThat(reset.started()).isFalse();
        assertThat(reset.frozen()).isFalse();
        assertThat(reset.step()).isZero();
        assertThat(reset.threadId()).isZero();
        assertThat(reset.threadName()).isEmpty();
        assertThat(liveThreadIds()).as("demo thread must be gone").doesNotContain(threadId);
        assertThat(anyLiveThreadNamed()).as("neither demo nor monitor helper may survive").isFalse();
        assertThat(scheduler.pendingCount()).isZero();
        assertThat(sink.captures).isEmpty();
    }

    @Test
    void resetTearsDownAWaitingThreadAndReturnsToNew() {
        service.start();
        service.step();
        awaitState(DemoState.BLOCKED);
        service.step();
        awaitState(DemoState.WAITING);
        long threadId = service.view().threadId();

        LifecycleDemoView reset = service.reset();

        assertThat(reset.state()).isEqualTo("NEW");
        assertThat(liveThreadIds()).doesNotContain(threadId);
        assertThat(anyLiveThreadNamed()).isFalse();
    }

    @Test
    void autoAdvanceWalksTheWholeMachineThroughTheScheduler() {
        service.start();
        assertThat(scheduler.pendingCount()).isEqualTo(1);

        scheduler.fireAllPending();
        awaitState(DemoState.BLOCKED);
        scheduler.fireAllPending();
        awaitState(DemoState.WAITING);
        scheduler.fireAllPending();
        awaitState(DemoState.TIMED_WAITING);
        scheduler.fireAllPending();
        awaitState(DemoState.TERMINATED);

        assertThat(service.view().step()).isEqualTo(4);
        assertThat(registry.get("thread_lab_demo_steps_total").tag("source", "auto")
                .counter().count()).isEqualTo(4D);
    }

    @Test
    void illegalTransitionsAreRejectedWithConflict() {
        assertConflict(() -> service.step(), "NEW");
        assertConflict(() -> service.freeze(), "NEW");
        assertConflict(() -> service.resume(), "NEW");
        assertConflict(() -> service.reset(), "already NEW");

        service.start();
        assertConflict(service::start, "already started");
        assertConflict(() -> service.resume(), "not frozen");

        service.freeze();
        assertConflict(() -> service.freeze(), "already frozen");
        assertConflict(() -> service.step(), "frozen");
        service.resume();

        walkToTerminated();
        assertConflict(() -> service.step(), "terminated");
        assertConflict(() -> service.freeze(), "terminated");
        assertConflict(() -> service.resume(), "not frozen");

        // Reset is the one operation that is legal from TERMINATED.
        assertThat(service.reset().state()).isEqualTo("NEW");
    }

    @Test
    void disabledDemoRefusesEveryCommandAndReportsNew() {
        properties.getLifecycle().setEnabled(false);
        assertThat(service.view().enabled()).isFalse();
        assertThat(service.view().state()).isEqualTo("NEW");

        assertThatThrownBy(service::start)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().code())
                .isEqualTo("40001");
        assertThatThrownBy(service::step)
                .isInstanceOf(BizException.class)
                .extracting(e -> ((BizException) e).getErrorCode().code())
                .isEqualTo("40001");
        assertThat(service.view().state()).isEqualTo("NEW");
    }

    @Test
    void gaugesTrackTheRealStateAndStayOffTheFaultLifecycleKey() {
        assertThat(registry.find("thread_lab_lifecycle_state_code").gauges())
                .as("the fault lifecycle gauge is owned by ThreadLabMetrics only")
                .isEmpty();
        assertThat(registry.find("thread_lab_demo_state_code").gauges()).hasSize(1);
        assertThat(stateGauge()).isZero();

        service.start();
        awaitState(DemoState.RUNNABLE);
        assertThat(stateGauge()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_demo_starts_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_demo_transitions_total").tag("to", "RUNNABLE")
                .counter().count()).isEqualTo(1D);

        service.freeze();
        assertThat(registry.get("thread_lab_demo_frozen").gauge().value()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_demo_freezes_total").counter().count()).isEqualTo(1D);
        assertThat(registry.get("thread_lab_demo_snapshot_requests_total").counter().count())
                .isEqualTo(1D);

        service.reset();
        assertThat(stateGauge()).isZero();
        assertThat(registry.get("thread_lab_demo_frozen").gauge().value()).isZero();
        assertThat(registry.get("thread_lab_demo_resets_total").counter().count()).isEqualTo(1D);
    }

    // ------------------------------------------------------------------ helpers

    private void walkToTerminated() {
        service.step();
        awaitState(DemoState.BLOCKED);
        service.step();
        awaitState(DemoState.WAITING);
        service.step();
        awaitState(DemoState.TIMED_WAITING);
        service.step();
        awaitState(DemoState.TERMINATED);
    }

    private void awaitState(DemoState wanted) {
        ThreadLabTestSupport.waitFor("demo state " + wanted, Duration.ofSeconds(5),
                () -> service.liveState() == wanted ? Boolean.TRUE : null);
    }

    private double stateGauge() {
        return registry.get("thread_lab_demo_state_code").gauge().value();
    }

    private boolean anyLiveThreadNamed() {
        Set<String> names = Set.of(properties.getLifecycle().getThreadName(),
                properties.getLifecycle().getMonitorThreadName());
        return Thread.getAllStackTraces().keySet().stream()
                .anyMatch(thread -> names.contains(thread.getName()));
    }

    private static void assertConflict(Runnable command, String messageFragment) {
        assertThatThrownBy(command::run)
                .isInstanceOf(BizException.class)
                .hasMessageContaining(messageFragment)
                .extracting(e -> ((BizException) e).getErrorCode().code())
                .isEqualTo("40901");
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    /** Records sink calls so "freeze captures immediately" is asserted, not assumed. */
    private static final class CapturingSink implements LifecycleSnapshotSink {
        private final List<LifecycleSnapshot> captures = new ArrayList<>();

        @Override
        public void capture(LifecycleSnapshot snapshot) {
            captures.add(snapshot);
        }
    }
}
