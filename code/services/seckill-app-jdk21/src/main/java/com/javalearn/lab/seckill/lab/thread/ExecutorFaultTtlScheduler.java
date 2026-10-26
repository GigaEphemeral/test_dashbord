package com.javalearn.lab.seckill.lab.thread;

import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Production {@link FaultTtlScheduler} backed by a single daemon
 * {@link ScheduledExecutorService}. Every thread it creates is a daemon so the
 * lab can never keep the JVM alive; the executor itself is closed on context
 * shutdown.
 */
public final class ExecutorFaultTtlScheduler implements FaultTtlScheduler, AutoCloseable {

    private final ScheduledExecutorService executor;

    public ExecutorFaultTtlScheduler() {
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "thread-lab-ttl");
            thread.setDaemon(true);
            return thread;
        };
        this.executor = Executors.newSingleThreadScheduledExecutor(factory);
    }

    @Override
    public Handle schedule(Runnable task, Duration delay) {
        AtomicBoolean fired = new AtomicBoolean(false);
        ScheduledFuture<?> future = executor.schedule(() -> {
            fired.set(true);
            task.run();
        }, Math.max(0L, delay.toMillis()), TimeUnit.MILLISECONDS);
        return new Handle() {
            @Override
            public void cancel() {
                future.cancel(false);
            }

            @Override
            public boolean isPending() {
                return !fired.get() && !future.isCancelled();
            }
        };
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }
}
