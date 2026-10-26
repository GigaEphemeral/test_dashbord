package com.javalearn.lab.seckill.lab.thread;

import java.time.Duration;

/**
 * Schedules the TTL auto-revert of a fault.
 *
 * <p>Abstracted behind this tiny interface so the lifecycle tests are fully
 * deterministic and instant: production uses a real {@link ScheduledExecutorService}
 * ({@link ExecutorFaultTtlScheduler}) while a test injects a manual trigger and
 * fires (or cancels) the pending revert without sleeping.</p>
 */
@FunctionalInterface
public interface FaultTtlScheduler {

    /**
     * Arm {@code task} to run once after {@code delay}.
     *
     * @return a handle that can cancel the pending revert before it fires
     */
    Handle schedule(Runnable task, Duration delay);

    /** Cancellation handle for an armed revert. */
    interface Handle {

        /** Cancels the pending task; no-op if it already fired or was cancelled. */
        void cancel();

        /** {@code true} while the task is still waiting to fire. */
        boolean isPending();
    }
}
