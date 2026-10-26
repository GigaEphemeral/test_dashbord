package com.javalearn.lab.seckill.lab.thread;

import java.util.ArrayList;
import java.util.List;

/**
 * The hard-capped allocation sink behind {@code heap-leak}.
 *
 * <p>The {@code heap-leak} design fingerprint is a stepped heap climb, but the
 * shared demo JVM must never actually be pushed to OOM, so every capped allocation
 * is gated: a chunk is only taken when {@code used + chunk <= capPercent * max},
 * checked before the {@code byte[]} is created. Once the ceiling is hit the
 * allocator flips {@code capped} and refuses further growth; the retained list is
 * emptied by {@link #drain()} on stop so no memory growth persists after the
 * action.</p>
 *
 * <p>The {@code oom} mode uses {@link #unbounded}: no ceiling check at all, so a
 * throwaway small-heap instance really does hit the heap cliff and dies through
 * {@code ExitOnOutOfMemoryError} (design §9.1). A "cap at 100% of max" would
 * pre-check {@code used + chunk <= max} forever and pin the heap just under the
 * ceiling without ever OOMing — which is why {@code oom} must not reuse the cap.</p>
 *
 * <p>The {@link HeapProbe} is injectable so the cap logic is unit-testable against
 * a scripted heap that climbs deterministically, without waiting on real GC.</p>
 */
final class HeapLeakAllocator {

    /** Sentinel cap-percent for the {@code oom} sink: allocate with no ceiling. */
    private static final long UNBOUNDED = Long.MIN_VALUE;

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

    /** Allocator for the {@code oom} mode: no ceiling, so the JVM really runs out. */
    static HeapLeakAllocator unbounded(HeapProbe probe, long chunkBytes) {
        return new HeapLeakAllocator(probe, UNBOUNDED, chunkBytes);
    }

    /**
     * Allocate one chunk if doing so keeps heap usage at or under the cap (capped
     * mode) or unconditionally (the unbounded {@code oom} sink).
     *
     * @return {@code true} when a chunk was retained, {@code false} when the cap
     *         was already reached (and the allocator is now permanently capped)
     */
    synchronized boolean allocateOnce() {
        if (capPercent != UNBOUNDED) {
            long capBytes = probe.maxBytes() * capPercent / 100L;
            if (probe.usedBytes() + chunkBytes > capBytes) {
                capped = true;
                return false;
            }
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
