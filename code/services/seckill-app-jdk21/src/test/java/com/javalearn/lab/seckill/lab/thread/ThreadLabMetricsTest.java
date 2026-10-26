package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.ManualTtlScheduler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static com.javalearn.lab.seckill.lab.thread.ThreadLabTestSupport.clock;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * Review finding #3 (revert/stop vs restart race, the shape ChaosActionService
 * already had to be fixed for twice): a same-mode restart can publish the fresh
 * action under the same registry key BEFORE the old action's late revert reaches
 * the metrics. Un-publishing must therefore be identity-based — reverting the old
 * action must never blank the live new fault's gauges.
 */
class ThreadLabMetricsTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final ThreadLabMetrics metrics = new ThreadLabMetrics(registry);

    @Test
    void revertingAReplacedActionKeepsTheNewFaultPublished() {
        FaultAction first = stuckAction();
        first.startThreads();
        metrics.publish(first);

        // Simulate the interleaving: service.stop removed `first` from its own map,
        // a new start(mode) published `replacement`, and only now does first's
        // revert reach the metrics.
        FaultAction replacement = stuckAction();
        replacement.startThreads();
        metrics.publish(replacement);

        try {
            first.stop();

            assertThat(gauge("thread_lab_fault_active")).as("new fault still active")
                    .isEqualTo(1D);
            assertThat(gauge("thread_lab_lifecycle_state_code")).as("new fault ACTIVE")
                    .isEqualTo(1D);
            assertThat(gauge("thread_lab_participant_threads")).as("new fault's gauges survive")
                    .isGreaterThan(0D);

            replacement.stop();

            assertThat(gauge("thread_lab_participant_threads")).isZero();
            assertThat(gauge("thread_lab_fault_active")).isZero();
            assertThat(registry.get("thread_lab_reverted_total")
                    .tags("mode", "stuck", "reason", "manual").counter().count())
                    .as("each revert counted exactly once")
                    .isEqualTo(2D);
        } finally {
            replacement.stop(); // idempotent; first.stop() is terminal too
        }
    }

    @Test
    void allModeLabelSeriesExistAtZeroBeforeTheFirstInjection() {
        // D6 (#34) binds to the label set, not observed traffic: every mode must
        // already export zeroed series (mirrors the SeckillMetrics eager style).
        for (FaultMode mode : FaultMode.values()) {
            assertThat(registry.get("thread_lab_fault_active").tag("mode", mode.key())
                    .gauge().value()).as("pre-registered zero for %s", mode).isZero();
            assertThat(registry.get("thread_lab_injected_total").tag("mode", mode.key())
                    .counter().count()).isZero();
        }
    }

    private FaultAction stuckAction() {
        return new FaultAction(FaultMode.STUCK, HoldMode.MANUAL, Duration.ofSeconds(120),
                TestLabThreadProperties.defaults(), clock(0L), new ManualTtlScheduler(),
                metrics, null, null);
    }

    private double gauge(String name) {
        return registry.get(name).tag("mode", "stuck").gauge().value();
    }
}
