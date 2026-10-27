package com.javalearn.lab.seckill.jdk8.lab.api;

import java.util.Collections;
import java.util.List;

/**
 * Result of one JDK 8 snapshot bundle capture (design §9.3).
 *
 * <p>Display-safe: folder path, file names and a short error text. It never
 * contains the lab token or stack content. A capture that failed or is disabled
 * still returns this object with {@code captured=false} and an {@code error}, so
 * {@code POST /lab/snapshot} answers 200 and never surfaces a 5xx.</p>
 *
 * <p>Field names mirror the JDK 21 {@code ThreadSnapshotView} record, so the D6
 * consumer can read both runtimes with one shape.</p>
 */
public final class SnapshotView {

    private final boolean captured;
    private final String trigger;
    private final String directory;
    private final List<String> files;
    private final long durationMs;
    private final long capturedAtEpochMs;
    private final String error;

    public SnapshotView(boolean captured,
                        String trigger,
                        String directory,
                        List<String> files,
                        long durationMs,
                        long capturedAtEpochMs,
                        String error) {
        this.captured = captured;
        this.trigger = trigger;
        this.directory = directory;
        this.files = Collections.unmodifiableList(files);
        this.durationMs = durationMs;
        this.capturedAtEpochMs = capturedAtEpochMs;
        this.error = error;
    }

    public boolean isCaptured() {
        return captured;
    }

    public String getTrigger() {
        return trigger;
    }

    public String getDirectory() {
        return directory;
    }

    public List<String> getFiles() {
        return files;
    }

    public long getDurationMs() {
        return durationMs;
    }

    public long getCapturedAtEpochMs() {
        return capturedAtEpochMs;
    }

    public String getError() {
        return error;
    }
}
