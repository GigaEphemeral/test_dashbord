package com.javalearn.lab.seckill.lab.thread;

/**
 * Seam between the single-thread state machine and whatever captures the demo
 * thread's stack (design §9.2/§9.3, Tasks #33/#M5-2b).
 *
 * <p>The shipped implementation is {@link FileLifecycleSnapshotSink}, which writes
 * a full {@code jcmd} bundle through {@link ThreadSnapshotService}. Swapping it
 * (a no-op sink for a demo instance without a writable snapshot root) needs no
 * change to {@link LifecycleDemoService}: the state machine only promises that
 * every {@code freeze} calls the sink exactly once, synchronously, with the real
 * state it observed.</p>
 *
 * <p>Implementations must be safe to call from an HTTP request thread and should
 * not throw: the service isolates and logs sink failures so a broken capture can
 * never leave the demo half-frozen.</p>
 */
@FunctionalInterface
public interface LifecycleSnapshotSink {

    /** Captures one snapshot of the demo thread. Called once per successful {@code freeze}. */
    void capture(LifecycleSnapshot snapshot);
}
