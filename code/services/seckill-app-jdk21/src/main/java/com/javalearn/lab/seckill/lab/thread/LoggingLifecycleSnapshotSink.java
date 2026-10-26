package com.javalearn.lab.seckill.lab.thread;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Placeholder {@link LifecycleSnapshotSink}: logs the observation and captures
 * nothing (design §9.2, Task #33).
 *
 * <p>Deliberately no {@code jcmd}/{@code jstack} call here: the runtime image is a
 * JRE, so the capture command and its storage ({@code doc/process/snapshots/})
 * are the next task's decision. Keeping this implementation a no-op means
 * {@code /lab/thread/lifecycle/freeze} is already safe to call on the shared app.</p>
 */
public final class LoggingLifecycleSnapshotSink implements LifecycleSnapshotSink {

    private static final Logger log = LoggerFactory.getLogger(LoggingLifecycleSnapshotSink.class);

    @Override
    public void capture(LifecycleSnapshot snapshot) {
        log.info("lifecycle snapshot placeholder (no jcmd capture yet): reason={} thread={} id={} "
                        + "state={}({}) step={} at={}",
                snapshot.reason(), snapshot.threadName(), snapshot.threadId(),
                snapshot.state(), snapshot.stateCode(), snapshot.step(),
                snapshot.capturedAtEpochMs());
    }
}
