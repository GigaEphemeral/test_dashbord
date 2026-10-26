package com.javalearn.lab.seckill.lab.thread;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the fault-thread lab (design §9.1, Task M5-1).
 *
 * <p>Bound from {@code app.lab.thread.*}. Mirrors {@link LabCacheProperties}: the
 * class declares no Java defaults — {@code application.yml} is the single source
 * of defaults, every thread count, TTL, interval, chunk size and pool size is
 * {@code ${ENV:default}} overridable, and {@link #validate()} fails startup on a
 * missing or nonsensical value instead of hiding it.</p>
 *
 * <p>The {@code oom.throwaway-instance} flag is the safety gate from the task
 * brief: {@code oom} is refused unless an instance is explicitly marked as a
 * disposable small-heap node, so the shared demo app can never kill itself.</p>
 */
@ConfigurationProperties(prefix = "app.lab.thread")
public class LabThreadProperties {

    /** Default fault lifetime; an {@code auto} fault auto-reverts after this. */
    private long defaultTtlSeconds;
    /** How long stop()/revert waits (per thread) for a participant to exit. */
    private long shutdownJoinTimeoutMs;

    private final Oom oom = new Oom();
    private final Deadlock deadlock = new Deadlock();
    private final Stuck stuck = new Stuck();
    private final CpuBurn cpuBurn = new CpuBurn();
    private final HeapLeak heapLeak = new HeapLeak();
    private final ThreadLeak threadLeak = new ThreadLeak();
    private final Starvation starvation = new Starvation();
    private final Lifecycle lifecycle = new Lifecycle();

    @PostConstruct
    void validate() {
        requirePositive(defaultTtlSeconds, "app.lab.thread.default-ttl-seconds");
        requirePositive(shutdownJoinTimeoutMs, "app.lab.thread.shutdown-join-timeout-ms");

        requireAtLeast(deadlock.threadCount, 2L, "app.lab.thread.deadlock.thread-count");
        requireNonNegative(deadlock.holdMs, "app.lab.thread.deadlock.hold-ms");
        // deadlock.true-deadlock is a boolean variant gate (see Deadlock javadoc);
        // its safety rests on the default being false and on operator opt-in only,
        // so there is no numeric range to validate.

        requirePositive(stuck.threadCount, "app.lab.thread.stuck.thread-count");
        requirePositive(stuck.pollMs, "app.lab.thread.stuck.poll-ms");

        requirePositive(cpuBurn.threadCount, "app.lab.thread.cpu-burn.thread-count");
        requireNonNegative(cpuBurn.yieldEveryLoops, "app.lab.thread.cpu-burn.yield-every-loops");

        requirePositive(heapLeak.chunkBytes, "app.lab.thread.heap-leak.chunk-bytes");
        requirePositive(heapLeak.intervalMs, "app.lab.thread.heap-leak.interval-ms");
        requirePercent(heapLeak.capPercent, "app.lab.thread.heap-leak.cap-percent");

        // oom.throwaway-instance is a boolean safety gate with a false default:
        // when it is false POST {mode:"oom"} is simply refused (see the service),
        // so there is nothing numeric to validate here.
        requirePositive(oom.chunkBytes, "app.lab.thread.oom.chunk-bytes");
        requirePositive(oom.intervalMs, "app.lab.thread.oom.interval-ms");

        requirePositive(threadLeak.maxLiveThreads, "app.lab.thread.thread-leak.max-live-threads");
        requirePositive(threadLeak.spawnCount, "app.lab.thread.thread-leak.spawn-count");
        requireNonNegative(threadLeak.spawnIntervalMs, "app.lab.thread.thread-leak.spawn-interval-ms");
        requireNonNegative(threadLeak.holdMs, "app.lab.thread.thread-leak.hold-ms");

        requirePositive(starvation.poolSize, "app.lab.thread.starvation.pool-size");
        requirePositive(starvation.queueCapacity, "app.lab.thread.starvation.queue-capacity");
        requirePositive(starvation.submitCount, "app.lab.thread.starvation.submit-count");
        requirePositive(starvation.taskBlockMs, "app.lab.thread.starvation.task-block-ms");

        requirePositive(lifecycle.autoAdvanceIntervalMs,
                "app.lab.thread.lifecycle.auto-advance-interval-ms");
        requirePositive(lifecycle.monitorHoldMs, "app.lab.thread.lifecycle.monitor-hold-ms");
        requirePositive(lifecycle.monitorAcquireTimeoutMs,
                "app.lab.thread.lifecycle.monitor-acquire-timeout-ms");
        requirePositive(lifecycle.timedWaitMs, "app.lab.thread.lifecycle.timed-wait-ms");
        requirePositive(lifecycle.shutdownJoinTimeoutMs,
                "app.lab.thread.lifecycle.shutdown-join-timeout-ms");
        // Four advances are the minimum that can reach TERMINATED from RUNNABLE
        // (RUNNABLE -> BLOCKED -> WAITING -> TIMED_WAITING -> TERMINATED).
        requireAtLeast(lifecycle.maxSteps, 4L, "app.lab.thread.lifecycle.max-steps");
        // reset() joins the demo thread while it may still be BLOCKED behind the
        // helper; a join budget at or below monitor-hold-ms would always time out.
        if (lifecycle.shutdownJoinTimeoutMs <= lifecycle.monitorHoldMs) {
            throw new IllegalStateException("app.lab.thread.lifecycle.shutdown-join-timeout-ms must "
                    + "exceed app.lab.thread.lifecycle.monitor-hold-ms");
        }
        if (isBlank(lifecycle.threadName) || isBlank(lifecycle.monitorThreadName)) {
            throw new IllegalStateException("app.lab.thread.lifecycle thread names must not be blank");
        }
        if (lifecycle.threadName.equals(lifecycle.monitorThreadName)) {
            throw new IllegalStateException("app.lab.thread.lifecycle.thread-name and "
                    + "app.lab.thread.lifecycle.monitor-thread-name must differ");
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.trim().isEmpty();
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalStateException("Configuration must be positive: " + key);
        }
    }

    private static void requireAtLeast(long value, long min, String key) {
        if (value < min) {
            throw new IllegalStateException(
                    "Configuration must be at least " + min + ": " + key);
        }
    }

    private static void requireNonNegative(long value, String key) {
        if (value < 0L) {
            throw new IllegalStateException("Configuration must not be negative: " + key);
        }
    }

    private static void requirePercent(long value, String key) {
        if (value <= 0L || value > 100L) {
            throw new IllegalStateException("Configuration must be in (0, 100]: " + key);
        }
    }

    public long getDefaultTtlSeconds() {
        return defaultTtlSeconds;
    }

    public void setDefaultTtlSeconds(long defaultTtlSeconds) {
        this.defaultTtlSeconds = defaultTtlSeconds;
    }

    public long getShutdownJoinTimeoutMs() {
        return shutdownJoinTimeoutMs;
    }

    public void setShutdownJoinTimeoutMs(long shutdownJoinTimeoutMs) {
        this.shutdownJoinTimeoutMs = shutdownJoinTimeoutMs;
    }

    public Oom getOom() {
        return oom;
    }

    public Deadlock getDeadlock() {
        return deadlock;
    }

    public Stuck getStuck() {
        return stuck;
    }

    public CpuBurn getCpuBurn() {
        return cpuBurn;
    }

    public HeapLeak getHeapLeak() {
        return heapLeak;
    }

    public ThreadLeak getThreadLeak() {
        return threadLeak;
    }

    public Starvation getStarvation() {
        return starvation;
    }

    public Lifecycle getLifecycle() {
        return lifecycle;
    }

    /** Gate + geometry of the true-OOM experiment. */
    public static class Oom {
        private boolean throwawayInstance;
        private long chunkBytes;
        private long intervalMs;

        public boolean isThrowawayInstance() {
            return throwawayInstance;
        }

        public void setThrowawayInstance(boolean throwawayInstance) {
            this.throwawayInstance = throwawayInstance;
        }

        public long getChunkBytes() {
            return chunkBytes;
        }

        public void setChunkBytes(long chunkBytes) {
            this.chunkBytes = chunkBytes;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }
    }

    /**
     * Lock-inversion participants. {@code true-deadlock} switches the mode from the
     * default drainable contention (real BLOCKED windows, fully interruptible) to a
     * genuine two-monitor inversion whose cycle the JVM can report in a thread
     * dump ({@code Found one Java-level deadlock}); such threads cannot be
     * interrupted, so {@code stop()} only unregisters the fault and the deadlocked
     * daemon threads await a JVM restart — enable it on a disposable instance only.
     */
    public static class Deadlock {
        private long threadCount;
        private long holdMs;
        private boolean trueDeadlock;

        public long getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(long threadCount) {
            this.threadCount = threadCount;
        }

        public long getHoldMs() {
            return holdMs;
        }

        public void setHoldMs(long holdMs) {
            this.holdMs = holdMs;
        }

        public boolean isTrueDeadlock() {
            return trueDeadlock;
        }

        public void setTrueDeadlock(boolean trueDeadlock) {
            this.trueDeadlock = trueDeadlock;
        }
    }

    public static class Stuck {
        private long threadCount;
        private long pollMs;

        public long getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(long threadCount) {
            this.threadCount = threadCount;
        }

        public long getPollMs() {
            return pollMs;
        }

        public void setPollMs(long pollMs) {
            this.pollMs = pollMs;
        }
    }

    public static class CpuBurn {
        private long threadCount;
        private long yieldEveryLoops;

        public long getThreadCount() {
            return threadCount;
        }

        public void setThreadCount(long threadCount) {
            this.threadCount = threadCount;
        }

        public long getYieldEveryLoops() {
            return yieldEveryLoops;
        }

        public void setYieldEveryLoops(long yieldEveryLoops) {
            this.yieldEveryLoops = yieldEveryLoops;
        }
    }

    /** Bounded leak: allocations stop once heap used crosses {@code capPercent}. */
    public static class HeapLeak {
        private long chunkBytes;
        private long intervalMs;
        private long capPercent;

        public long getChunkBytes() {
            return chunkBytes;
        }

        public void setChunkBytes(long chunkBytes) {
            this.chunkBytes = chunkBytes;
        }

        public long getIntervalMs() {
            return intervalMs;
        }

        public void setIntervalMs(long intervalMs) {
            this.intervalMs = intervalMs;
        }

        public long getCapPercent() {
            return capPercent;
        }

        public void setCapPercent(long capPercent) {
            this.capPercent = capPercent;
        }
    }

    /** Rising live-thread count with a hard ceiling; optional virtual-thread variant. */
    public static class ThreadLeak {
        private long maxLiveThreads;
        private long spawnCount;
        private long spawnIntervalMs;
        private long holdMs;
        private boolean virtual;

        public long getMaxLiveThreads() {
            return maxLiveThreads;
        }

        public void setMaxLiveThreads(long maxLiveThreads) {
            this.maxLiveThreads = maxLiveThreads;
        }

        public long getSpawnCount() {
            return spawnCount;
        }

        public void setSpawnCount(long spawnCount) {
            this.spawnCount = spawnCount;
        }

        public long getSpawnIntervalMs() {
            return spawnIntervalMs;
        }

        public void setSpawnIntervalMs(long spawnIntervalMs) {
            this.spawnIntervalMs = spawnIntervalMs;
        }

        public long getHoldMs() {
            return holdMs;
        }

        public void setHoldMs(long holdMs) {
            this.holdMs = holdMs;
        }

        public boolean isVirtual() {
            return virtual;
        }

        public void setVirtual(boolean virtual) {
            this.virtual = virtual;
        }
    }

    /** Tiny pool + blocking tasks so active saturates at max and the queue grows. */
    public static class Starvation {
        private long poolSize;
        private long queueCapacity;
        private long submitCount;
        private long taskBlockMs;

        public long getPoolSize() {
            return poolSize;
        }

        public void setPoolSize(long poolSize) {
            this.poolSize = poolSize;
        }

        public long getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(long queueCapacity) {
            this.queueCapacity = queueCapacity;
        }

        public long getSubmitCount() {
            return submitCount;
        }

        public void setSubmitCount(long submitCount) {
            this.submitCount = submitCount;
        }

        public long getTaskBlockMs() {
            return taskBlockMs;
        }

        public void setTaskBlockMs(long taskBlockMs) {
            this.taskBlockMs = taskBlockMs;
        }
    }

    /**
     * Single-thread lifecycle demo (design §9.2, Task #33). All values are
     * externalized under {@code app.lab.thread.lifecycle.*}; the startup guard
     * refuses a nonsensical machine (a terminal state out of reach, a reset join
     * budget shorter than the monitor hold, colliding thread names).
     */
    public static class Lifecycle {
        /** Master switch; when false every command is refused with 40001. */
        private boolean enabled;
        /** Name of the single demo thread (it must be easy to find in a dump). */
        private String threadName;
        /** Name of the helper thread that holds the monitor for the BLOCKED window. */
        private String monitorThreadName;
        /** Delay between automatic advances; also the retry period while a step is in flight. */
        private long autoAdvanceIntervalMs;
        /** How long the helper holds the monitor, i.e. the real BLOCKED window. */
        private long monitorHoldMs;
        /** How long the demo spends waiting for the helper to enter the monitor. */
        private long monitorAcquireTimeoutMs;
        /** Duration of the TIMED_WAITING stage ({@code Thread.sleep}). */
        private long timedWaitMs;
        /** Upper bound on accepted advances; at least 4 so TERMINATED is reachable. */
        private long maxSteps;
        /** Join budget for reset(); must exceed {@link #monitorHoldMs}. */
        private long shutdownJoinTimeoutMs;

        public boolean isEnabled() {
            return enabled;
        }

        public void setEnabled(boolean enabled) {
            this.enabled = enabled;
        }

        public String getThreadName() {
            return threadName;
        }

        public void setThreadName(String threadName) {
            this.threadName = threadName;
        }

        public String getMonitorThreadName() {
            return monitorThreadName;
        }

        public void setMonitorThreadName(String monitorThreadName) {
            this.monitorThreadName = monitorThreadName;
        }

        public long getAutoAdvanceIntervalMs() {
            return autoAdvanceIntervalMs;
        }

        public void setAutoAdvanceIntervalMs(long autoAdvanceIntervalMs) {
            this.autoAdvanceIntervalMs = autoAdvanceIntervalMs;
        }

        public long getMonitorHoldMs() {
            return monitorHoldMs;
        }

        public void setMonitorHoldMs(long monitorHoldMs) {
            this.monitorHoldMs = monitorHoldMs;
        }

        public long getMonitorAcquireTimeoutMs() {
            return monitorAcquireTimeoutMs;
        }

        public void setMonitorAcquireTimeoutMs(long monitorAcquireTimeoutMs) {
            this.monitorAcquireTimeoutMs = monitorAcquireTimeoutMs;
        }

        public long getTimedWaitMs() {
            return timedWaitMs;
        }

        public void setTimedWaitMs(long timedWaitMs) {
            this.timedWaitMs = timedWaitMs;
        }

        public long getMaxSteps() {
            return maxSteps;
        }

        public void setMaxSteps(long maxSteps) {
            this.maxSteps = maxSteps;
        }

        public long getShutdownJoinTimeoutMs() {
            return shutdownJoinTimeoutMs;
        }

        public void setShutdownJoinTimeoutMs(long shutdownJoinTimeoutMs) {
            this.shutdownJoinTimeoutMs = shutdownJoinTimeoutMs;
        }
    }
}
