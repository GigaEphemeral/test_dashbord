package com.javalearn.lab.seckill.lab.thread;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * The {@code thread_lab_demo_*} meter family of the single-thread lifecycle demo
 * (design §9.2, Task #33).
 *
 * <p>Kept OUT of {@link ThreadLabMetrics} on purpose: that class owns
 * {@code thread_lab_lifecycle_state_code}, which means the fault action's
 * ACTIVE/PAUSED/STOPPED and is part of the console key contract. The demo's real
 * six-state code is a new gauge, {@code thread_lab_demo_state_code} (0 NEW … 5
 * TERMINATED), so both timelines can live in D6 without a key collision.</p>
 *
 * <p>Every series is registered eagerly (all six target states, both step
 * sources) so the dashboard binds to the label set at zero, following
 * {@link ThreadLabMetrics}. All gauges are read from the live demo thread by the
 * bound {@link LifecycleDemoService}; no polling here.</p>
 */
public final class LifecycleDemoMetrics {

    private final Map<DemoState, Counter> transitions = new EnumMap<>(DemoState.class);
    private final Counter manualSteps;
    private final Counter autoSteps;
    private final Counter starts;
    private final Counter freezes;
    private final Counter resumes;
    private final Counter resets;
    private final Counter snapshots;

    private volatile LifecycleDemoService service;

    public LifecycleDemoMetrics(MeterRegistry registry) {
        Objects.requireNonNull(registry, "registry");
        for (DemoState state : DemoState.values()) {
            transitions.put(state, Counter.builder("thread_lab_demo_transitions_total")
                    .description("Commanded demo-thread stage entries, by target state")
                    .tag("to", state.name())
                    .register(registry));
        }
        manualSteps = Counter.builder("thread_lab_demo_steps_total")
                .description("Successful demo advances, by command source")
                .tag("source", "manual")
                .register(registry);
        autoSteps = Counter.builder("thread_lab_demo_steps_total")
                .description("Successful demo advances, by command source")
                .tag("source", "auto")
                .register(registry);
        starts = Counter.builder("thread_lab_demo_starts_total")
                .description("Demo threads started")
                .register(registry);
        freezes = Counter.builder("thread_lab_demo_freezes_total")
                .description("Freezes (advancement paused + snapshot requested)")
                .register(registry);
        resumes = Counter.builder("thread_lab_demo_resumes_total")
                .description("Resumes of a frozen demo")
                .register(registry);
        resets = Counter.builder("thread_lab_demo_resets_total")
                .description("Demo resets (thread torn down, back to NEW)")
                .register(registry);
        snapshots = Counter.builder("thread_lab_demo_snapshot_requests_total")
                .description("Snapshot requests handed to the LifecycleSnapshotSink")
                .register(registry);

        Gauge.builder("thread_lab_demo_state_code", this::stateCode)
                .description("Real demo-thread state code: 0 NEW, 1 RUNNABLE, 2 BLOCKED, "
                        + "3 WAITING, 4 TIMED_WAITING, 5 TERMINATED")
                .register(registry);
        Gauge.builder("thread_lab_demo_frozen", this, LifecycleDemoMetrics::frozenCode)
                .description("1 while the demo is frozen (advancement paused)")
                .register(registry);
        Gauge.builder("thread_lab_demo_step", this, LifecycleDemoMetrics::stepValue)
                .description("Completed advances of the demo state machine")
                .register(registry);
    }

    /**
     * Binds this metrics instance to the single service it observes. Called once by
     * the Spring configuration (the service needs the metrics, so it cannot be a
     * constructor dependency); tests bind their own instance the same way.
     */
    public void bind(LifecycleDemoService service) {
        Objects.requireNonNull(service, "service");
        LifecycleDemoService current = this.service;
        if (current != null && current != service) {
            throw new IllegalStateException("LifecycleDemoMetrics is already bound");
        }
        this.service = service;
    }

    void onTransition(DemoState target) {
        transitions.get(target).increment();
    }

    void onStep(String source) {
        ("auto".equals(source) ? autoSteps : manualSteps).increment();
    }

    void onStart() {
        starts.increment();
    }

    void onFreeze() {
        freezes.increment();
    }

    void onResume() {
        resumes.increment();
    }

    void onReset() {
        resets.increment();
    }

    void onSnapshot() {
        snapshots.increment();
    }

    private double stateCode() {
        LifecycleDemoService current = service;
        return current == null ? DemoState.NEW.code() : current.liveState().code();
    }

    private double frozenCode() {
        LifecycleDemoService current = service;
        return current != null && current.isFrozen() ? 1D : 0D;
    }

    private double stepValue() {
        LifecycleDemoService current = service;
        return current == null ? 0D : current.completedSteps();
    }
}
