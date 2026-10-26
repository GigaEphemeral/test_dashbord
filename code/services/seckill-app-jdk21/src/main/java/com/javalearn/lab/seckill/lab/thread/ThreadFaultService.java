package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.api.ThreadActionView;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * The fault-thread lab store and lifecycle controller (design §9.1, Task M5-1).
 *
 * <p>Per-instance and in-memory, like {@link com.javalearn.lab.seckill.lab.toggle.LabToggleService}:
 * the console drives one instance through {@code /lab/thread/**}, so a degraded
 * instance can be compared against a healthy one. The chaos-controller will
 * broadcast these actions later.</p>
 *
 * <p>Contract proven by the tests:</p>
 * <ul>
 *   <li>Multiple concurrent faults of <em>different</em> modes are supported; the
 *       <em>same</em> mode twice is rejected with {@code 40901 CONFLICT}.</li>
 *   <li>{@code auto} faults auto-revert at TTL; {@code manual} holds never do.</li>
 *   <li>{@code pause} freezes the remaining TTL, {@code resume} re-arms only the
 *       remainder.</li>
 *   <li>{@code stop-all} removes and fully tears down every injected fault.</li>
 *   <li>{@code oom} is refused unless {@code app.lab.thread.oom.throwaway-instance}
 *       is set, so the shared app can never kill itself.</li>
 * </ul>
 */
public final class ThreadFaultService {

    private final LabThreadProperties properties;
    private final Clock clock;
    private final FaultTtlScheduler scheduler;
    private final ThreadLabMetrics metrics;
    private final Map<FaultMode, FaultAction> active = new LinkedHashMap<>();
    private final Object lock = new Object();

    public ThreadFaultService(LabThreadProperties properties, Clock clock,
                              FaultTtlScheduler scheduler, ThreadLabMetrics metrics) {
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
    }

    /**
     * Injects one fault.
     *
     * @throws BizException {@code 40001} unknown mode/hold/bad ttl or oom refused;
     *                      {@code 40901} when the mode is already injected
     */
    public ThreadActionView start(String modeKey, String holdKey, Long ttlSeconds, Boolean virtual) {
        FaultMode mode = parseMode(modeKey);
        HoldMode hold = parseHold(holdKey);
        Duration ttl = resolveTtl(ttlSeconds, hold);
        if (mode == FaultMode.OOM && !properties.getOom().isThrowawayInstance()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT,
                    "Mode 'oom' is refused: it needs app.lab.thread.oom.throwaway-instance=true "
                            + "so only a disposable small-heap node runs it");
        }
        synchronized (lock) {
            if (active.containsKey(mode)) {
                throw new BizException(CommonErrorCode.CONFLICT,
                        "Mode already injected: " + mode.key());
            }
            FaultAction action = new FaultAction(mode, hold, ttl, properties, clock, scheduler,
                    metrics, this::unpublish, virtual);
            action.startThreads();
            metrics.onInject(mode);
            metrics.publish(action);
            active.put(mode, action);
            action.armTtl(ttl);
            return view(action);
        }
    }

    public ThreadActionView stop(String modeKey) {
        FaultMode mode = parseMode(modeKey);
        FaultAction action;
        synchronized (lock) {
            action = active.remove(mode);
        }
        requirePresent(mode, action);
        action.stop();
        return view(action);
    }

    public ThreadActionView pause(String modeKey) {
        FaultMode mode = parseMode(modeKey);
        FaultAction action = snapshotOne(mode);
        requirePresent(mode, action);
        action.pause();
        return view(action);
    }

    public ThreadActionView resume(String modeKey) {
        FaultMode mode = parseMode(modeKey);
        FaultAction action = snapshotOne(mode);
        requirePresent(mode, action);
        action.resume();
        return view(action);
    }

    /**
     * Removes every injected fault with the {@code stop-all} reason and fully tears
     * down each action's threads.
     *
     * @return the views of the faults that were stopped (each now {@code STOPPED})
     */
    public List<ThreadActionView> stopAll() {
        List<FaultAction> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(active.values());
            active.clear();
        }
        List<ThreadActionView> stopped = new ArrayList<>(snapshot.size());
        for (FaultAction action : snapshot) {
            action.stopAll();
            stopped.add(view(action));
        }
        return stopped;
    }

    /** Current live faults, in a stable order, for {@code GET /lab/thread/state}. */
    public List<ThreadActionView> list() {
        List<FaultAction> snapshot;
        synchronized (lock) {
            snapshot = new ArrayList<>(active.values());
        }
        snapshot.sort((a, b) -> a.getMode().compareTo(b.getMode()));
        List<ThreadActionView> views = new ArrayList<>(snapshot.size());
        for (FaultAction action : snapshot) {
            views.add(view(action));
        }
        return views;
    }

    private void unpublish(FaultAction action) {
        synchronized (lock) {
            active.remove(action.getMode(), action);
        }
    }

    private FaultAction snapshotOne(FaultMode mode) {
        synchronized (lock) {
            return active.get(mode);
        }
    }

    private static void requirePresent(FaultMode mode, FaultAction action) {
        if (action == null) {
            throw new BizException(CommonErrorCode.NOT_FOUND, "No active fault for mode: " + mode.key());
        }
    }

    private Duration resolveTtl(Long ttlSeconds, HoldMode hold) {
        if (hold == HoldMode.MANUAL) {
            return Duration.ofMillis(0L);
        }
        long seconds = ttlSeconds == null ? properties.getDefaultTtlSeconds() : ttlSeconds;
        if (seconds <= 0L) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT,
                    "ttlSeconds must be positive for an auto fault");
        }
        return Duration.ofSeconds(seconds);
    }

    private static FaultMode parseMode(String modeKey) {
        if (modeKey == null || modeKey.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Mode is required");
        }
        FaultMode mode = FaultMode.fromKey(modeKey);
        if (mode == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Unknown mode: " + modeKey);
        }
        return mode;
    }

    private static HoldMode parseHold(String holdKey) {
        if (holdKey == null || holdKey.trim().isEmpty()) {
            return HoldMode.AUTO;
        }
        HoldMode hold = HoldMode.fromKey(holdKey);
        if (hold == null) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "Unknown hold: " + holdKey);
        }
        return hold;
    }

    private static ThreadActionView view(FaultAction action) {
        return new ThreadActionView(
                action.getMode().key(),
                action.getHold().key(),
                action.getState().name(),
                action.getStartedAt().toEpochMilli(),
                action.remainingTtlMillis(),
                List.copyOf(action.threadIds()));
    }
}
