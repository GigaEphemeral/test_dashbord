package com.javalearn.lab.seckill.lab.thread;

import com.javalearn.lab.seckill.lab.api.ThreadSnapshotView;

/** Best-effort Grafana marker of a successful snapshot (design §9.3). */
@FunctionalInterface
public interface SnapshotAnnotator {

    /**
     * Posts one annotation for a captured bundle. Implementations must never
     * throw: the snapshot is already on disk and a broken Grafana must not turn
     * a successful freeze into a failed one.
     */
    void annotate(ThreadSnapshotView view, LifecycleSnapshot snapshot);
}
