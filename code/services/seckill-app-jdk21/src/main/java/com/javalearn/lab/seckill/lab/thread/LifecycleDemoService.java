package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.lab.api.LifecycleDemoView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * The single-thread lifecycle demo of design §9.2 (Task #33): one dedicated demo
 * thread really walks {@code NEW → RUNNABLE → BLOCKED → WAITING → TIMED_WAITING →
 * TERMINATED}, and every observation reported to the console / Grafana is the real
 * {@link Thread#getState()} of that thread — never a bookkeeping enum alone.
 *
 * <h2>Machine</h2>
 * The demo thread is choreographed in stages. The stage is advanced by
 * {@code step} (manual) or by the auto-advance timer (the same injected
 * {@link FaultTtlScheduler} the fault lab uses, so tests fire it by hand):
 * <ol>
 *   <li>{@code RUNNABLE} — the thread spins at the start gate. A JVM RUNNABLE
 *       thread includes both running and ready, which is the teaching note of
 *       §9.2; the spin is deliberate (a park would report WAITING).</li>
 *   <li>{@code BLOCKED} — a helper thread holds a private monitor for
 *       {@code monitor-hold-ms}; the demo thread enters {@code synchronized} on
 *       it and is really BLOCKED until the helper releases. The release is
 *       natural: the demo returns to RUNNABLE while its stage is still BLOCKED
 *       until the next advance.</li>
 *   <li>{@code WAITING} — {@code Object.wait()} with no timeout; only an
 *       advance (or reset) notifies it.</li>
 *   <li>{@code TIMED_WAITING} — {@code Thread.sleep(timed-wait-ms)}.</li>
 *   <li>{@code TERMINATED} — the run method returns.</li>
 * </ol>
 * {@code freeze} cancels the auto-advance timer, marks the demo frozen and calls
 * the {@link LifecycleSnapshotSink} exactly once with the real state it observed;
 * it never freezes the JVM. {@code resume} re-arms from the frozen step.
 * {@code reset} interrupts and joins both threads, bounded by
 * {@code shutdown-join-timeout-ms}, and returns to NEW.
 *
 * <h2>Concurrency</h2>
 * Commands are serialized by {@code commandLock}; the demo thread never takes
 * that lock (so reset can join it while holding it) and only reads the volatile
 * stage / atomics. The auto-advance handle has its own {@code timerLock}. Every
 * thread is a daemon, so the lab can never keep the JVM alive.
 */
public final class LifecycleDemoService {

    private static final Logger log = LoggerFactory.getLogger(LifecycleDemoService.class);

    private final LabThreadProperties.Lifecycle config;
    private final Clock clock;
    private final FaultTtlScheduler scheduler;
    private final LifecycleDemoMetrics metrics;
    private final LifecycleSnapshotSink snapshotSink;

    private final Object commandLock = new Object();
    private final Object timerLock = new Object();
    private final Object waitMonitor = new Object();

    /** Advance permits handed to the demo thread's gates (issued vs consumed). */
    private final AtomicInteger permits = new AtomicInteger();
    private final AtomicInteger consumed = new AtomicInteger();

    private volatile Thread demoThread;
    private volatile Thread monitorThread;
    private volatile boolean started;
    private volatile boolean frozen;
    private volatile boolean resetRequested;
    private volatile int step;
    private volatile Stage stage = Stage.IDLE;
    /** Guarded by {@link #waitMonitor}. */
    private int waitingPermits;
    private FaultTtlScheduler.Handle autoHandle;

    public LifecycleDemoService(LabThreadProperties properties, Clock clock,
                                FaultTtlScheduler scheduler, LifecycleDemoMetrics metrics,
                                LifecycleSnapshotSink snapshotSink) {
        Objects.requireNonNull(properties, "properties");
        this.config = properties.getLifecycle();
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.snapshotSink = Objects.requireNonNull(snapshotSink, "snapshotSink");
    }

    /** Current observation; safe to poll (never blocks behind a reset join). */
    public LifecycleDemoView view() {
        Thread thread = demoThread;
        DemoState state = liveState();
        return new LifecycleDemoView(
                config.isEnabled(),
                started,
                frozen,
                state.name(),
                state.code(),
                step,
                (int) config.getMaxSteps(),
                stage.name(),
                thread == null ? "" : thread.getName(),
                thread == null ? 0L : thread.threadId(),
                thread != null && thread.isAlive());
    }

    /**
     * Creates and starts the demo thread (NEW becomes RUNNABLE) and arms
     * auto-advance.
     *
     * @throws BizException {@code 40901} when a demo has already been started
     *                      (including one that terminated — reset first);
     *                      {@code 40001} when the feature is disabled
     */
    public LifecycleDemoView start() {
        synchronized (commandLock) {
            requireEnabled();
            if (started) {
                throw conflict("Demo already started (state " + liveState()
                        + "); reset before starting again");
            }
            started = true;
            frozen = false;
            resetRequested = false;
            step = 0;
            permits.set(0);
            consumed.set(0);
            synchronized (waitMonitor) {
                waitingPermits = 0;
            }
            stage = Stage.RUNNABLE;
            Thread thread = new Thread(this::runDemoThread, config.getThreadName());
            thread.setDaemon(true);
            thread.setUncaughtExceptionHandler(
                    (failed, error) -> log.warn("Demo thread {} failed", failed.getName(), error));
            demoThread = thread;
            thread.start();
            metrics.onStart();
            metrics.onTransition(DemoState.RUNNABLE);
            armAuto();
            return view();
        }
    }

    /**
     * Advances the machine one stage immediately (RUNNABLE → BLOCKED → WAITING →
     * TIMED_WAITING → TERMINATED) and re-arms auto-advance.
     *
     * @throws BizException {@code 40901} when not started, frozen, terminated, at
     *                      {@code max-steps}, or when a previous step has not been
     *                      consumed by the demo thread yet
     */
    public LifecycleDemoView step() {
        synchronized (commandLock) {
            requireEnabled();
            requireStarted();
            if (frozen) {
                throw conflict("Demo is frozen; resume before stepping");
            }
            if (stage == Stage.TERMINATED || liveState() == DemoState.TERMINATED) {
                throw conflict("Demo thread terminated; reset to run it again");
            }
            if (step >= config.getMaxSteps()) {
                throw conflict("Max steps reached (" + config.getMaxSteps() + "); reset to run it again");
            }
            cancelAuto();
            if (!tryAdvance("manual")) {
                armAuto();
                throw conflict("Previous step is still in flight; wait for the next state");
            }
            armAuto();
            return view();
        }
    }

    /**
     * Pauses advancement (cancels auto-advance; later {@code step} calls are
     * refused) and captures a snapshot immediately through the sink. The JVM and
     * the demo thread keep running exactly as they are.
     *
     * @throws BizException {@code 40901} when not started, already frozen, or the
     *                      demo already terminated
     */
    public LifecycleDemoView freeze() {
        synchronized (commandLock) {
            requireEnabled();
            requireStarted();
            if (frozen) {
                throw conflict("Demo is already frozen");
            }
            DemoState observed = liveState();
            if (observed == DemoState.TERMINATED) {
                throw conflict("Demo thread terminated; reset to run it again");
            }
            cancelAuto();
            frozen = true;
            metrics.onFreeze();
            Thread thread = demoThread;
            LifecycleSnapshot snapshot = new LifecycleSnapshot(
                    thread == null ? 0L : thread.threadId(),
                    thread == null ? "" : thread.getName(),
                    observed.name(),
                    observed.code(),
                    step,
                    clock.millis(),
                    "freeze");
            try {
                snapshotSink.capture(snapshot);
            } catch (RuntimeException failure) {
                // The seam is pluggable; a broken capture must not undo the freeze.
                log.warn("Snapshot sink failed for {}", snapshot, failure);
            }
            metrics.onSnapshot();
            return view();
        }
    }

    /**
     * Resumes a frozen demo from the step it was frozen at and re-arms
     * auto-advance.
     *
     * @throws BizException {@code 40901} when not started or not frozen
     */
    public LifecycleDemoView resume() {
        synchronized (commandLock) {
            requireEnabled();
            requireStarted();
            if (!frozen) {
                throw conflict("Demo is not frozen");
            }
            frozen = false;
            metrics.onResume();
            armAuto();
            return view();
        }
    }

    /**
     * Tears the demo thread (and any monitor helper) down and returns to NEW.
     * Legal from every started state, including TERMINATED; refused only when the
     * demo is already NEW. Not gated by {@code enabled}: teardown must always be
     * possible.
     *
     * @throws BizException {@code 40901} when the demo is already NEW
     */
    public LifecycleDemoView reset() {
        synchronized (commandLock) {
            if (!started) {
                throw conflict("Demo is already NEW; nothing to reset");
            }
            cancelAuto();
            resetRequested = true;
            frozen = false;
            Thread demo = demoThread;
            Thread helper = monitorThread;
            if (demo != null) {
                demo.interrupt();
            }
            if (helper != null) {
                helper.interrupt();
            }
            synchronized (waitMonitor) {
                waitingPermits = 0;
                waitMonitor.notifyAll();
            }
            boolean demoGone = joinQuietly(demo, config.getShutdownJoinTimeoutMs());
            boolean helperGone = joinQuietly(helper, config.getShutdownJoinTimeoutMs());
            demoThread = null;
            monitorThread = null;
            started = false;
            stage = Stage.IDLE;
            step = 0;
            resetRequested = false;
            permits.set(0);
            consumed.set(0);
            metrics.onReset();
            if (!demoGone || !helperGone) {
                log.warn("Reset finished but a demo thread is still alive (demoGone={}, helperGone={})",
                        demoGone, helperGone);
            }
            return view();
        }
    }

    /** Real state of the demo thread; NEW when no thread exists (before start / after reset). */
    DemoState liveState() {
        Thread thread = demoThread;
        return thread == null ? DemoState.NEW : DemoState.from(thread.getState());
    }

    boolean isFrozen() {
        return frozen;
    }

    int completedSteps() {
        return step;
    }

    // ------------------------------------------------------------------ machine

    private void runDemoThread() {
        try {
            awaitGate(); // stage RUNNABLE
            if (resetRequested) {
                return;
            }
            enterStage(Stage.BLOCKED);
            runBlockedStage();
            awaitGate(); // stage BLOCKED -> permit consumed after the monitor released
            if (resetRequested) {
                return;
            }
            runWaitingStage();
            if (resetRequested) {
                return;
            }
            enterStage(Stage.TIMED_WAITING);
            Thread.sleep(config.getTimedWaitMs());
            awaitGate(); // final gate: TIMED_WAITING -> TERMINATED
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException failure) {
            log.warn("Demo thread failed", failure);
        } finally {
            stage = Stage.TERMINATED;
            metrics.onTransition(DemoState.TERMINATED);
            cancelAuto();
            log.debug("Demo thread finished after {} step(s)", step);
        }
    }

    private void enterStage(Stage next) {
        stage = next;
        metrics.onTransition(next.state);
    }

    /**
     * Spins until an advance permit is available. Deliberately a spin: parking or
     * sleeping here would report WAITING / TIMED_WAITING and teach the wrong state.
     */
    private void awaitGate() throws InterruptedException {
        while (permits.get() == consumed.get()) {
            if (resetRequested) {
                return;
            }
            if (Thread.currentThread().isInterrupted()) {
                throw new InterruptedException("reset requested");
            }
            Thread.onSpinWait();
            Thread.yield();
        }
        consumed.incrementAndGet();
    }

    /**
     * Real monitor contention: a helper daemon holds a private monitor for
     * {@code monitor-hold-ms} while the demo thread tries to enter it. The demo
     * spins (RUNNABLE) until the helper reports it is inside, so the BLOCKED window
     * cannot be missed by a scheduling race.
     */
    private void runBlockedStage() throws InterruptedException {
        Object monitor = new Object();
        AtomicBoolean holding = new AtomicBoolean(false);
        Thread helper = new Thread(() -> {
            synchronized (monitor) {
                holding.set(true);
                try {
                    Thread.sleep(config.getMonitorHoldMs());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                }
            }
        }, config.getMonitorThreadName());
        helper.setDaemon(true);
        helper.setUncaughtExceptionHandler(
                (failed, error) -> log.warn("Monitor helper {} failed", failed.getName(), error));
        monitorThread = helper;
        helper.start();

        long deadline = System.nanoTime() + Duration.ofMillis(config.getMonitorAcquireTimeoutMs()).toNanos();
        while (!holding.get()) {
            if (resetRequested || Thread.currentThread().isInterrupted()) {
                joinQuietly(helper, config.getShutdownJoinTimeoutMs());
                monitorThread = null;
                throw new InterruptedException("reset requested");
            }
            if (System.nanoTime() > deadline) {
                log.warn("Monitor helper did not acquire within {}ms; the BLOCKED window is skipped",
                        config.getMonitorAcquireTimeoutMs());
                joinQuietly(helper, config.getShutdownJoinTimeoutMs());
                monitorThread = null;
                return;
            }
            Thread.onSpinWait();
            Thread.yield();
        }
        synchronized (monitor) {
            // Real BLOCKED while the helper holds it; acquiring also proves release.
        }
        joinQuietly(helper, config.getShutdownJoinTimeoutMs());
        monitorThread = null;
    }

    /**
     * {@code Object.wait()} with no timeout; only an advance (or reset) wakes it.
     * The WAITING stage is published while holding {@code waitMonitor}, so an
     * advance that sees it necessarily waits for the monitor and can only land
     * after the demo thread is inside {@code wait()} — a real WAITING window, no
     * lost notification.
     */
    private void runWaitingStage() throws InterruptedException {
        synchronized (waitMonitor) {
            enterStage(Stage.WAITING);
            while (waitingPermits == 0) {
                if (resetRequested || Thread.currentThread().isInterrupted()) {
                    throw new InterruptedException("reset requested");
                }
                waitMonitor.wait();
            }
            waitingPermits = 0;
        }
    }

    // ---------------------------------------------------------------- commands

    private boolean tryAdvance(String source) {
        if (!started || frozen || resetRequested) {
            return false;
        }
        switch (stage) {
            case RUNNABLE, BLOCKED, TIMED_WAITING -> {
                if (permits.get() != consumed.get()) {
                    return false;
                }
                permits.incrementAndGet();
            }
            case WAITING -> {
                synchronized (waitMonitor) {
                    if (waitingPermits != 0) {
                        return false;
                    }
                    waitingPermits = 1;
                    waitMonitor.notifyAll();
                }
            }
            default -> {
                return false;
            }
        }
        step++;
        metrics.onStep(source);
        return true;
    }

    private void armAuto() {
        synchronized (timerLock) {
            if (autoHandle != null) {
                autoHandle.cancel();
                autoHandle = null;
            }
            if (!started || frozen || resetRequested || stage == Stage.TERMINATED
                    || !config.isEnabled()) {
                return;
            }
            autoHandle = scheduler.schedule(this::onAutoTick,
                    Duration.ofMillis(config.getAutoAdvanceIntervalMs()));
        }
    }

    private void cancelAuto() {
        synchronized (timerLock) {
            if (autoHandle != null) {
                autoHandle.cancel();
                autoHandle = null;
            }
        }
    }

    private void onAutoTick() {
        synchronized (commandLock) {
            if (!started || frozen || resetRequested || stage == Stage.TERMINATED) {
                return;
            }
            // A pending permit means the demo thread has not reached the gate yet;
            // the tick is dropped and the next one retries, so auto-advance tracks
            // the thread instead of drifting ahead of it.
            tryAdvance("auto");
            armAuto();
        }
    }

    // ----------------------------------------------------------------- helpers

    private void requireEnabled() {
        if (!config.isEnabled()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT,
                    "Lifecycle demo is disabled: app.lab.thread.lifecycle.enabled=false");
        }
    }

    private void requireStarted() {
        if (!started) {
            throw conflict("Demo is in NEW; start it first");
        }
    }

    private static BizException conflict(String message) {
        return new BizException(CommonErrorCode.CONFLICT, message);
    }

    private static boolean joinQuietly(Thread thread, long timeoutMs) {
        if (thread == null || thread == Thread.currentThread()) {
            return true;
        }
        try {
            thread.join(timeoutMs);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
        return !thread.isAlive();
    }

    /** Internal stage of the demo thread; not the exported state. */
    private enum Stage {
        IDLE(DemoState.NEW),
        RUNNABLE(DemoState.RUNNABLE),
        BLOCKED(DemoState.BLOCKED),
        WAITING(DemoState.WAITING),
        TIMED_WAITING(DemoState.TIMED_WAITING),
        TERMINATED(DemoState.TERMINATED);

        private final DemoState state;

        Stage(DemoState state) {
            this.state = state;
        }
    }
}
