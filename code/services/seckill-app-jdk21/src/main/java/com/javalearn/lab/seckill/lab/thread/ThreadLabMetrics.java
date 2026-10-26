package com.javalearn.lab.seckill.lab.thread;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@code thread_lab_*} meter family (design §5 metrics list). Registered
 * eagerly — every {@code mode} label series exists at zero before the first
 * injection so dashboards (D6, Task #34) bind to the label set, not to observed
 * traffic, mirroring {@link com.javalearn.lab.seckill.admission.app.SeckillMetrics}.
 *
 * <p>Gauges read live from the currently-active {@link FaultAction} per mode via
 * the {@link #active} map, so the reported thread count, heap bytes, pool
 * saturation and lifecycle code track the running fault without the service
 * having to poll.</p>
 */
public final class ThreadLabMetrics {

    static final String REASON_TTL = "ttl";
    static final String REASON_MANUAL = "manual";
    static final String REASON_STOP_ALL = "stop-all";

    private final MeterRegistry registry;
    private final Map<FaultMode, Counter> injected = new EnumMap<>(FaultMode.class);
    /** Live per-mode action backing the gauge suppliers; cleared on revert. */
    private final Map<FaultMode, FaultAction> active = new ConcurrentHashMap<>();

    public ThreadLabMetrics(MeterRegistry registry) {
        this.registry = Objects.requireNonNull(registry, "registry");
        for (FaultMode mode : FaultMode.values()) {
            injected.put(mode, Counter.builder("thread_lab_injected_total")
                    .description("Fault injections started, by mode")
                    .tag("mode", mode.key())
                    .register(registry));
            Gauge.builder("thread_lab_fault_active", () -> isActive(mode) ? 1D : 0D)
                    .description("1 while a fault of this mode is injected")
                    .tag("mode", mode.key())
                    .register(registry);
            Gauge.builder("thread_lab_lifecycle_state_code", () -> lifecycleCode(mode))
                    .description("Lifecycle of the active fault (0 stopped, 1 active, 2 paused)")
                    .tag("mode", mode.key())
                    .register(registry);
            Gauge.builder("thread_lab_participant_threads", () -> liveThreads(mode))
                    .description("Live participant threads of the active fault")
                    .tag("mode", mode.key())
                    .register(registry);
            Gauge.builder("thread_lab_heap_leaked_bytes", () -> heapBytes(mode))
                    .description("Bytes retained by the heap-leak sink before it drains")
                    .tag("mode", mode.key())
                    .register(registry);
            Gauge.builder("thread_lab_pool_active", () -> poolActive(mode))
                    .description("Active worker threads of the starvation pool")
                    .tag("mode", mode.key())
                    .register(registry);
            Gauge.builder("thread_lab_pool_queue_size", () -> poolQueueSize(mode))
                    .description("Queued tasks of the starvation pool")
                    .tag("mode", mode.key())
                    .register(registry);
        }
    }

    void onInject(FaultMode mode) {
        injected.get(mode).increment();
    }

    /** Publishes the running action so its live gauges start reporting. */
    void publish(FaultAction action) {
        active.put(action.getMode(), action);
    }

    /** Removes the action so its gauges fall back to zero. */
    void onRevert(FaultMode mode, String reason) {
        active.remove(mode);
        Counter.builder("thread_lab_reverted_total")
                .description("Faults auto-reverted or stopped, by mode and reason")
                .tag("mode", mode.key())
                .tag("reason", reason)
                .register(registry)
                .increment();
    }

    private FaultAction current(FaultMode mode) {
        return active.get(mode);
    }

    private boolean isActive(FaultMode mode) {
        FaultAction action = current(mode);
        return action != null && action.getState() != LifecycleState.STOPPED;
    }

    private double lifecycleCode(FaultMode mode) {
        FaultAction action = current(mode);
        return action == null ? LifecycleState.STOPPED.code() : action.getState().code();
    }

    private double liveThreads(FaultMode mode) {
        FaultAction action = current(mode);
        return action == null ? 0D : action.liveThreadCount();
    }

    private double heapBytes(FaultMode mode) {
        FaultAction action = current(mode);
        return action == null ? 0D : action.heapLeakedBytes();
    }

    private double poolActive(FaultMode mode) {
        FaultAction action = current(mode);
        return action == null ? 0D : action.poolActiveCount();
    }

    private double poolQueueSize(FaultMode mode) {
        FaultAction action = current(mode);
        return action == null ? 0D : action.poolQueueSize();
    }
}
