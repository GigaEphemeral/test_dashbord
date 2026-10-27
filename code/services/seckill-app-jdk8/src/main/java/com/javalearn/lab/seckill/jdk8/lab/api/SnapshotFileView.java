package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * One file of an on-disk bundle with its size, for {@code GET /lab/snapshot}.
 * The name is the bundle file name ({@code thread-dump.txt} …), never a full
 * path, so the listing stays console-friendly.
 */
public final class SnapshotFileView {

    private final String name;
    private final long bytes;

    public SnapshotFileView(String name, long bytes) {
        this.name = name;
        this.bytes = bytes;
    }

    public String getName() {
        return name;
    }

    public long getBytes() {
        return bytes;
    }
}
