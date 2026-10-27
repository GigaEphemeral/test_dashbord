package com.javalearn.lab.seckill.jdk8.lab.api;

import java.util.Collections;
import java.util.List;

/**
 * One snapshot bundle as seen by {@code GET /lab/snapshot}: folder name, absolute
 * directory, UTC timestamp, trigger read back from {@code metadata.json}, total
 * size and per-file sizes. Newest first. Read-only and safe to render.
 */
public final class SnapshotBundleView {

    private final String name;
    private final String directory;
    private final String timestamp;
    private final String trigger;
    private final long totalBytes;
    private final List<SnapshotFileView> files;

    public SnapshotBundleView(String name,
                              String directory,
                              String timestamp,
                              String trigger,
                              long totalBytes,
                              List<SnapshotFileView> files) {
        this.name = name;
        this.directory = directory;
        this.timestamp = timestamp;
        this.trigger = trigger;
        this.totalBytes = totalBytes;
        this.files = Collections.unmodifiableList(files);
    }

    public String getName() {
        return name;
    }

    public String getDirectory() {
        return directory;
    }

    public String getTimestamp() {
        return timestamp;
    }

    public String getTrigger() {
        return trigger;
    }

    public long getTotalBytes() {
        return totalBytes;
    }

    public List<SnapshotFileView> getFiles() {
        return files;
    }
}
