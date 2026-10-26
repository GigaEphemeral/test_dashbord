package com.javalearn.lab.seckill.lab.thread;

import java.util.Objects;

/**
 * Real {@link LifecycleSnapshotSink} of design §9.3: every {@code freeze} writes a
 * timestamped bundle ({@code jcmd Thread.print -l}, {@code GC.heap_info},
 * {@code GC.class_histogram}, metadata and the analysis template) through
 * {@link ThreadSnapshotService}.
 *
 * <p>Thin adapter on purpose: the service owns capture, retention, metrics and the
 * best-effort Grafana annotation, and it never throws, so this sink cannot undo a
 * freeze. {@link LifecycleDemoService} still guards the call, as the seam allows
 * other implementations.</p>
 */
public final class FileLifecycleSnapshotSink implements LifecycleSnapshotSink {

    private final ThreadSnapshotService snapshots;

    public FileLifecycleSnapshotSink(ThreadSnapshotService snapshots) {
        this.snapshots = Objects.requireNonNull(snapshots, "snapshots");
    }

    @Override
    public void capture(LifecycleSnapshot snapshot) {
        snapshots.capture(snapshot);
    }
}
