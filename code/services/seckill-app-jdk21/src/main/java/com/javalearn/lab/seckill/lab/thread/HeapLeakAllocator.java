package com.javalearn.lab.seckill.lab.thread;

import java.util.ArrayList;
import java.util.List;

/**
 * The hard-capped allocation sink behind {@code heap-leak}.
 *
 * <p>The design fingerprint is a stepped heap climb, but the shared demo JVM must
 * never actually be pushed to OOM, so every allocation is gated: a chunk is only
 * taken when {@code used + chunk <= capPercent * max}, checked before the
 * {@code byte[]} is created. Once the ceiling is hit the allocator flips
 * {@code capped} and refuses further growth; the retained list is emptied by
 * {@link #drain()} on stop so no memory growth persists after the action.</p>
 *
 * <p>The {@link HeapProbe} is injectable so the cap logic is unit-testable against
 * a scripted heap that climbs deterministically, without waiting on real GC.</p>
 */
final class HeapLeakAllocator {

    /** Abstraction over {@link Runtime} heap counters so tests can script the climb. */
    interface HeapProbe {
        long usedBytes();

        long maxBytes();
    }

    /** The live JVM view used in production. */
    static final HeapProbe RUNTIME_PROBE = new HeapProbe() {
        @Override
        public long usedBytes() {
            Runtime runtime = Runtime.getRuntime();
            return runtime.totalMemory() - runtime.freeMemory();
        }

        @Override
        public long maxBytes() {
            return Runtime.getRuntime().maxMemory();
        }
    };

    private final HeapProbe probe;
    private final long capPercent;
    private final int chunkBytes;
    private final List<byte[]> sink = new ArrayList<>();

    private long allocatedBytes;
    private boolean capped;

    HeapLeakAllocator(HeapProbe probe, long capPercent, long chunkBytes) {
        this.probe = probe;
        this.capPercent = capPercent;
        this.chunkBytes = Math.toIntExact(chunkBytes);
    }

    /**
     * Allocate one chunk if doing so keeps heap usage at or under the cap.
     *
     * @return {@code true} when a chunk was retained, {@code false} when the cap
     *         was already reached (and the allocator is now permanently capped)
     */
    synchronized boolean allocateOnce() {
        long capBytes = probe.maxBytes() * capPercent / 100L;
        if (probe.usedBytes() + chunkBytes > capBytes) {
            capped = true;
            return false;
        }
        sink.add(new byte[chunkBytes]);
        allocatedBytes += chunkBytes;
        return true;
    }

    /** @return whether the ceiling has been reached and no more growth is allowed. */
    synchronized boolean isCapped() {
        return capped;
    }

    /** Current retained bytes, exposed as the {@code thread_lab_heap_leaked_bytes} gauge. */
    synchronized long allocatedBytes() {
        return allocatedBytes;
    }

    /** Drops every strong reference so the leaked heap becomes reclaimable. */
    synchronized void drain() {
        sink.clear();
        allocatedBytes = 0L;
    }
}
