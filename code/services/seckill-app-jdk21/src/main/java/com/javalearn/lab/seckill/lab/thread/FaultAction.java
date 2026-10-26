package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.CpuBurn;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.Deadlock;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.HeapLeak;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.Oom;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.Starvation;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.Stuck;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties.ThreadLeak;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * One injected faulty-thread action: its participant threads, its TTL lifecycle
 * (auto-revert / manual hold / pause-freeze / resume), and a teardown that is
 * guaranteed to leave nothing behind.
 *
 * <p>Every participant thread is a daemon, namespaced {@code thread-lab-<mode>-*},
 * honours the {@code volatile stopped} flag and interrupts, and is registered in
 * {@link #participants}. {@link #stop()} is idempotent and terminal: it
 * cancels the armed TTL timer, flips {@code stopped}, interrupts the leak sink /
 * starvation pool, then joins each thread bounded by
 * {@code shutdown-join-timeout-ms}. This is the acceptance core: after a revert
 * or stop no thread of the action is alive and no leaked memory persists —
 * the one exception is the operator-gated {@code deadlock.true-deadlock} variant
 * (a real JVM-detected monitor cycle cannot be interrupted; those threads are
 * reclaimed by the JVM restart of the disposable instance, see
 * {@link #startTrueDeadlock}).</p>
 *
 * <p>Concurrency: a fault of a given mode may be {@code ACTIVE} or {@code PAUSED}
 * at most once (the service rejects a duplicate); transitions and teardown are
 * guarded by {@code synchronized} plus {@link #tornDown} so a TTL firing on the
 * scheduler thread races safely with a concurrent {@code stop}.</p>
 */
final class FaultAction {

    private final FaultMode mode;
    private final HoldMode hold;
    private final LabThreadProperties properties;
    private final Clock clock;
    private final FaultTtlScheduler scheduler;
    private final ThreadLabMetrics metrics;
    private final Consumer<FaultAction> onReverted;

    private final List<Thread> participants = new ArrayList<>();
    private final ConcurrentLinkedQueue<Thread> spawnedLeakThreads = new ConcurrentLinkedQueue<>();
    private final AtomicLong heapLeaked = new AtomicLong();
    private final Object monitorA = new Object();
    private final Object monitorB = new Object();
    private final java.util.concurrent.CountDownLatch stuckLatch =
            new java.util.concurrent.CountDownLatch(1);

    private final AtomicBoolean tornDown = new AtomicBoolean(false);

    private volatile LifecycleState state = LifecycleState.ACTIVE;
    private volatile boolean stopped = false;
    private volatile Instant startedAt;
    private volatile long deadlineEpochMs;
    private volatile long frozenRemainingMs;
    private volatile FaultTtlScheduler.Handle ttlHandle;

    private ThreadPoolExecutor starvationPool;
    private HeapLeakAllocator heapAllocator;
    private boolean virtualLeak;
    private final Boolean virtualOverride;

    FaultAction(FaultMode mode, HoldMode hold, Duration ttl,
                LabThreadProperties properties, Clock clock,
                FaultTtlScheduler scheduler, ThreadLabMetrics metrics,
                Consumer<FaultAction> onReverted, Boolean virtualOverride) {
        this.mode = Objects.requireNonNull(mode, "mode");
        this.hold = Objects.requireNonNull(hold, "hold");
        this.properties = Objects.requireNonNull(properties, "properties");
        this.clock = Objects.requireNonNull(clock, "clock");
        this.scheduler = Objects.requireNonNull(scheduler, "scheduler");
        this.metrics = Objects.requireNonNull(metrics, "metrics");
        this.onReverted = onReverted;
        this.virtualOverride = virtualOverride;
        this.startedAt = clock.instant();
        if (hold == HoldMode.AUTO) {
            this.deadlineEpochMs = clock.millis() + Math.max(0L, ttl.toMillis());
        } else {
            this.deadlineEpochMs = Long.MAX_VALUE;
        }
    }

    // ---- lifecycle -------------------------------------------------------

    /** Arms the TTL timer (auto only). Must be called before any pause/stop. */
    synchronized void armTtl(Duration ttl) {
        if (hold == HoldMode.AUTO && !tornDown.get()) {
            scheduleRevert(ttl);
        }
    }

    private void scheduleRevert(Duration delay) {
        this.deadlineEpochMs = clock.millis() + Math.max(0L, delay.toMillis());
        this.ttlHandle = scheduler.schedule(() -> revert(ThreadLabMetrics.REASON_TTL), delay);
    }

    synchronized void pause() {
        if (state != LifecycleState.ACTIVE || tornDown.get()) {
            return;
        }
        if (hold == HoldMode.AUTO) {
            if (ttlHandle != null) {
                ttlHandle.cancel();
            }
            frozenRemainingMs = Math.max(0L, deadlineEpochMs - clock.millis());
        }
        state = LifecycleState.PAUSED;
    }

    synchronized void resume() {
        if (state != LifecycleState.PAUSED || tornDown.get()) {
            return;
        }
        if (hold == HoldMode.AUTO) {
            scheduleRevert(Duration.ofMillis(frozenRemainingMs));
        }
        state = LifecycleState.ACTIVE;
    }

    /**
     * Fires on TTL expiry: tears the fault down and un-publishes its metrics.
     * Idempotent against a concurrent manual stop.
     */
    void revert(String reason) {
        if (teardown()) {
            // Identity-based un-publish: if a same-mode restart already replaced
            // this action, reverting the old one must not blank the new one's gauges.
            metrics.onRevert(this, reason);
            if (onReverted != null) {
                onReverted.accept(this);
            }
        }
    }

    /** Explicit stop (manual hold end or {@code /stop}); counts as the {@code manual} reason. */
    void stop() {
        revert(ThreadLabMetrics.REASON_MANUAL);
    }

    /** Removes every fault of this action; reported as the {@code stop-all} reason. */
    void stopAll() {
        revert(ThreadLabMetrics.REASON_STOP_ALL);
    }

    private synchronized boolean teardown() {
        if (!tornDown.compareAndSet(false, true)) {
            return false;
        }
        state = LifecycleState.STOPPED;
        if (ttlHandle != null) {
            ttlHandle.cancel();
        }
        stopped = true;

        if (heapAllocator != null) {
            heapAllocator.drain();
        }
        if (starvationPool != null) {
            starvationPool.shutdownNow();
        }

        List<Thread> toJoin = new ArrayList<>(participants);
        toJoin.addAll(spawnedLeakThreads);
        for (Thread thread : toJoin) {
            thread.interrupt();
        }
        long joinBudget = Math.max(1L, properties.getShutdownJoinTimeoutMs());
        for (Thread thread : toJoin) {
            try {
                thread.join(joinBudget);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Final drain after the join: an allocation raced between the first drain
        // and the worker's stop-flag check may still hold a chunk; this guarantees
        // the "no memory growth persists after the action" acceptance for good.
        if (heapAllocator != null) {
            heapAllocator.drain();
            heapLeaked.set(0L);
        }
        return true;
    }

    // ---- read model ------------------------------------------------------

    FaultMode getMode() {
        return mode;
    }

    HoldMode getHold() {
        return hold;
    }

    LifecycleState getState() {
        return state;
    }

    Instant getStartedAt() {
        return startedAt;
    }

    /** Remaining TTL in millis; {@code -1} for a manual hold that never reverts. */
    synchronized long remainingTtlMillis() {
        if (hold == HoldMode.MANUAL) {
            return -1L;
        }
        if (state == LifecycleState.PAUSED) {
            return frozenRemainingMs;
        }
        if (state == LifecycleState.STOPPED) {
            return 0L;
        }
        return Math.max(0L, deadlineEpochMs - clock.millis());
    }

    /** Thread ids of every participant created so far (display-safe). */
    synchronized Set<Long> threadIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (Thread thread : participants) {
            ids.add(thread.threadId());
        }
        for (Thread thread : spawnedLeakThreads) {
            ids.add(thread.threadId());
        }
        return ids;
    }

    int liveThreadCount() {
        int count = 0;
        for (Thread thread : participants) {
            if (thread.isAlive()) {
                count++;
            }
        }
        for (Thread thread : spawnedLeakThreads) {
            if (thread.isAlive()) {
                count++;
            }
        }
        return count;
    }

    long heapLeakedBytes() {
        return heapLeaked.get();
    }

    int poolActiveCount() {
        return starvationPool == null ? 0 : starvationPool.getActiveCount();
    }

    int poolQueueSize() {
        return starvationPool == null ? 0 : starvationPool.getQueue().size();
    }

    // ---- fault behaviour -------------------------------------------------

    /**
     * Creates and starts the participant threads for the mode. Called once by the
     * service immediately after construction, before the TTL is armed.
     */
    void startThreads() {
        switch (mode) {
            case DEADLOCK -> startDeadlock();
            case STUCK -> startStuck();
            case CPU_BURN -> startCpuBurn();
            case HEAP_LEAK -> startHeapLeak(false);
            case OOM -> startHeapLeak(true);
            case THREAD_LEAK -> startThreadLeak();
            case STARVATION -> startStarvation();
        }
    }

    private void startDeadlock() {
        Deadlock cfg = properties.getDeadlock();
        int total = Math.toIntExact(cfg.getThreadCount());
        long hold = cfg.getHoldMs();
        if (cfg.isTrueDeadlock()) {
            startTrueDeadlock(total, hold);
            return;
        }
        // The two threads cross over the same two monitors in opposite order. Each
        // holds ONE monitor, sleeps while holding it, then RELEASES it before taking
        // the next: so they contend hard (a real BLOCKED fingerprint) yet never hold
        // two at once, which keeps every iteration drainable by stop(). A permanent
        // intrinsic deadlock could not be interrupted, and the acceptance rule is
        // that no thread may outlive the action — see the step-32 defect note.
        // For the dump-visible "Found one Java-level deadlock" on a disposable
        // instance, enable app.lab.thread.deadlock.true-deadlock instead.
        for (int i = 0; i < total; i++) {
            final boolean firstOrder = (i % 2 == 0);
            // Half-period stagger between the two orders. Without it the cycles run in
            // lockstep: one worker holds A while the other holds B, so they never
            // contend on the SAME monitor and BLOCKED appears only by scheduling luck.
            final long phaseOffset = firstOrder ? 0L : Math.max(1L, hold / 2);
            register(run("worker", () -> {
                if (phaseOffset > 0L) {
                    sleep(phaseOffset);
                }
                Object first = firstOrder ? monitorA : monitorB;
                Object second = firstOrder ? monitorB : monitorA;
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    synchronized (first) {
                        sleep(hold);
                    }
                    synchronized (second) {
                        sleep(hold);
                    }
                }
            }));
        }
    }

    /**
     * The genuine two-monitor inversion of design §9.1: each thread holds its
     * first monitor while blocking forever on the second, so the JVM deadlock
     * detector sees the cycle and {@code jstack} prints
     * {@code Found one Java-level deadlock} — the documented teaching artefact,
     * proven in tests via {@link java.lang.management.ThreadMXBean#findDeadlockedThreads()},
     * the same detector the dump is rendered from.
     *
     * <p>A blocked {@code synchronized} entry cannot be interrupted, so this
     * variant deliberately trades cleanup for fidelity and is config-gated
     * ({@code true-deadlock}, default false) exactly like {@code oom} trades the
     * shared JVM for a throwaway one: {@code stop()}/TTL revert still cancel the
     * timer, unregister the fault, zero the gauges and make the mode re-injectable
     * (the monitors are per-action, so a follow-up fault never touches the old
     * cycle); the deadlocked daemon threads themselves are reclaimed only when
     * the disposable instance restarts. Use on a marked throwaway node only.</p>
     */
    private void startTrueDeadlock(int total, long hold) {
        for (int i = 0; i < total; i++) {
            final boolean firstOrder = (i % 2 == 0);
            register(run("worker", () -> {
                Object first = firstOrder ? monitorA : monitorB;
                Object second = firstOrder ? monitorB : monitorA;
                synchronized (first) {
                    sleep(hold);                    // let the peer take `second` first
                    synchronized (second) {         // BLOCKED forever: the cycle
                        sleep(Long.MAX_VALUE);
                    }
                }
            }));
        }
    }

    private void startStuck() {
        Stuck cfg = properties.getStuck();
        int total = Math.toIntExact(cfg.getThreadCount());
        long poll = cfg.getPollMs();
        for (int i = 0; i < total; i++) {
            register(run("worker", () -> {
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    try {
                        // The latch never counts down: this parks the thread in
                        // WAITING/TIMED_WAITING, the documented stuck fingerprint,
                        // while still honouring interrupt for clean teardown.
                        stuckLatch.await(poll, TimeUnit.MILLISECONDS);
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }));
        }
    }

    private void startCpuBurn() {
        CpuBurn cfg = properties.getCpuBurn();
        int total = Math.toIntExact(cfg.getThreadCount());
        long yieldEvery = cfg.getYieldEveryLoops();
        for (int i = 0; i < total; i++) {
            register(run("worker", () -> {
                long spin = 0L;
                while (!stopped && !Thread.currentThread().isInterrupted()) {
                    spin = spin * 31 + 7;
                    if (yieldEvery > 0 && (spin % yieldEvery) == 0) {
                        Thread.yield();
                    }
                    // Never-true guard keeps `spin` live and the loop observable.
                    if (spin == Long.MIN_VALUE && stopped) {
                        return;
                    }
                }
            }));
        }
    }

    private void startHeapLeak(boolean unbounded) {
        long intervalMs;
        if (unbounded) {
            // Truly no ceiling: a cap at 100% of max would keep pre-checking
            // "used + chunk <= max" and pin the heap just under the cliff without
            // ever throwing OOM — the oom fingerprint (断崖 + ExitOnOutOfMemoryError
            // process exit) requires the unbounded sink. The safety is the gate in
            // ThreadFaultService: only a marked throwaway instance ever gets here.
            Oom oom = properties.getOom();
            heapAllocator = HeapLeakAllocator.unbounded(HeapLeakAllocator.RUNTIME_PROBE,
                    oom.getChunkBytes());
            intervalMs = oom.getIntervalMs();
        } else {
            HeapLeak cfg = properties.getHeapLeak();
            heapAllocator = new HeapLeakAllocator(HeapLeakAllocator.RUNTIME_PROBE,
                    cfg.getCapPercent(), cfg.getChunkBytes());
            intervalMs = cfg.getIntervalMs();
        }
        register(run("worker", () -> {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                heapAllocator.allocateOnce();
                heapLeaked.set(heapAllocator.allocatedBytes());
                // For the capped (heap-leak) mode once the ceiling is hit the
                // allocator stops growing and the loop just idles: the fingerprint
                // stays flat and everything is still reclaimable. The unbounded
                // (oom) variant keeps allocating until the throwaway JVM dies.
                sleep(intervalMs);
            }
        }));
    }

    private void startThreadLeak() {
        ThreadLeak cfg = properties.getThreadLeak();
        int maxLive = Math.toIntExact(cfg.getMaxLiveThreads());
        int spawnCount = Math.toIntExact(cfg.getSpawnCount());
        long interval = cfg.getSpawnIntervalMs();
        long hold = cfg.getHoldMs();
        virtualLeak = virtualOverride != null ? virtualOverride : cfg.isVirtual();
        register(run("controller", () -> {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                for (int i = 0; i < spawnCount && liveThreadCount() < maxLive; i++) {
                    spawnLeakWorker(hold);
                }
                sleep(interval);
            }
        }));
    }

    private void spawnLeakWorker(long holdMs) {
        Runnable body = () -> {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                sleep(holdMs);
            }
        };
        // JDK21 contrast: virtual threads make the leak a heap-growth story instead
        // of a live-platform-thread-count story (design §9.1, thread-leak row).
        Thread worker = virtualLeak
                ? Thread.ofVirtual().name("thread-lab-thread-leak-vt-").start(body)
                : Thread.ofPlatform().daemon(true).name("thread-lab-thread-leak-", 0).start(body);
        spawnedLeakThreads.add(worker);
    }

    private void startStarvation() {
        Starvation cfg = properties.getStarvation();
        int poolSize = Math.toIntExact(cfg.getPoolSize());
        int queueCapacity = Math.toIntExact(cfg.getQueueCapacity());
        int submitCount = Math.toIntExact(cfg.getSubmitCount());
        long blockMs = cfg.getTaskBlockMs();
        ThreadFactory recording = task -> {
            Thread thread = new Thread(task, "thread-lab-starvation-");
            thread.setDaemon(true);
            synchronized (this) {
                participants.add(thread);
            }
            return thread;
        };
        starvationPool = new ThreadPoolExecutor(poolSize, poolSize, 0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(queueCapacity), recording);
        Runnable blocking = () -> {
            while (!stopped && !Thread.currentThread().isInterrupted()) {
                try {
                    TimeUnit.MILLISECONDS.sleep(blockMs);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    return;
                }
            }
        };
        for (int i = 0; i < submitCount; i++) {
            try {
                starvationPool.execute(blocking);
            } catch (RejectedExecutionException saturated) {
                // Queue full: the pool is already active==max with a full queue,
                // which is exactly the starvation fingerprint.
                break;
            }
        }
    }

    // ---- helpers ---------------------------------------------------------

    private void register(Thread thread) {
        participants.add(thread);
    }

    private Thread run(String suffix, Runnable body) {
        Thread thread = new Thread(body, "thread-lab-" + mode.key() + "-" + suffix);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    private static void sleep(long millis) {
        if (millis <= 0L) {
            Thread.yield();
            return;
        }
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }
}
