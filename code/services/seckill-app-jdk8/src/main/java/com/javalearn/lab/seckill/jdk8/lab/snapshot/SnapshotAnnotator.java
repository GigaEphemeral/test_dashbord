package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import com.javalearn.lab.seckill.jdk8.lab.api.SnapshotView;

/**
 * Best-effort Grafana marker of a successful JDK 8 snapshot bundle (design §9.3).
 *
 * <p>Same contract as the JDK 21 {@code SnapshotAnnotator}: it is called only
 * after the bundle is complete, and an implementation must never throw — a
 * broken Grafana must not turn a successful capture into a failed one.</p>
 */
@FunctionalInterface
public interface SnapshotAnnotator {

    /**
     * Posts one annotation for a captured bundle. Implementations swallow every
     * failure, count it in {@code thread_lab_snapshot_annotations_total{result}}
     * and return; the bundle is already on disk.
     */
    void annotate(SnapshotView view);
}
