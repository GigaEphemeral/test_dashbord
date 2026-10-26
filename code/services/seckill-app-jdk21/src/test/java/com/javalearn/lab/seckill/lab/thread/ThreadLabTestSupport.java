package com.javalearn.lab.seckill.lab.thread;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Deterministic test doubles for the fault-thread lifecycle.
 *
 * <ul>
 *   <li>{@link MutableClock} lets a test pin and advance wall time so the
 *       remaining-TTL math is exact and instant.</li>
 *   <li>{@link ManualTtlScheduler} captures the armed revert tasks (and their
 *       delays) instead of running them on a timer, so TTL expiry, pause-cancel
 *       and resume-remainder are all driven explicitly.</li>
 *   <li>{@link #liveThreadIds()} reads the JVM's live thread set so a cleanup
 *       assertion can prove a recorded id really died.</li>
 * </ul>
 */
final class ThreadLabTestSupport {

    private ThreadLabTestSupport() {
    }

    static MutableClock clock(long epochMs) {
        return new MutableClock(Instant.ofEpochMilli(epochMs));
    }

    /** Ids of every thread the JVM considers alive right now. */
    static Set<Long> liveThreadIds() {
        return Thread.getAllStackTraces().keySet().stream()
                .map(Thread::threadId)
                .collect(Collectors.toSet());
    }

    /**
     * Polls {@code probe} every 10ms until it returns a non-null value or the
     * budget runs out. Only used for conditions that are PERMANENT once true
     * (a formed deadlock cycle, a grown leak counter), never for transitory
     * states, so the assertion direction stays deterministic.
     */
    static <T> T waitFor(String what, Duration budget, java.util.function.Supplier<T> probe) {
        long deadline = System.nanoTime() + budget.toNanos();
        T result;
        while ((result = probe.get()) == null) {
            if (System.nanoTime() >= deadline) {
                throw new AssertionError("Timed out waiting for: " + what);
            }
            try {
                Thread.sleep(10L);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                throw new AssertionError("Interrupted waiting for: " + what, interrupted);
            }
        }
        return result;
    }

    static final class MutableClock extends Clock {
        private Instant instant;

        MutableClock(Instant instant) {
            this.instant = instant;
        }

        void advance(Duration delta) {
            instant = instant.plus(delta);
        }

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }
    }

    /** Records scheduled revert tasks; {@link Handle#cancel()} and manual firing are explicit. */
    static final class ManualTtlScheduler implements FaultTtlScheduler {
        final List<ManualHandle> armed = new ArrayList<>();

        @Override
        public synchronized Handle schedule(Runnable task, Duration delay) {
            ManualHandle handle = new ManualHandle(task, delay);
            armed.add(handle);
            return handle;
        }

        synchronized ManualHandle latest() {
            return armed.get(armed.size() - 1);
        }

        synchronized int pendingCount() {
            int count = 0;
            for (ManualHandle handle : armed) {
                if (handle.isPending()) {
                    count++;
                }
            }
            return count;
        }

        void fireAllPending() {
            List<ManualHandle> snapshot;
            synchronized (this) {
                snapshot = new ArrayList<>(armed);
            }
            for (ManualHandle handle : snapshot) {
                handle.fire();
            }
        }
    }

    static final class ManualHandle implements FaultTtlScheduler.Handle {
        private final Runnable task;
        private final Duration delay;
        private boolean cancelled;
        private boolean fired;

        ManualHandle(Runnable task, Duration delay) {
            this.task = task;
            this.delay = delay;
        }

        Duration delay() {
            return delay;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public boolean isPending() {
            return !cancelled && !fired;
        }

        /** Runs the revert exactly like the real timer firing would. */
        void fire() {
            if (isPending()) {
                fired = true;
                task.run();
            }
        }
    }
}
