package com.javalearn.lab.seckill.lab.thread;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code heap-leak} hard cap (design §9.1): allocations must stop the instant
 * projected heap usage would cross {@code capPercent}, so the shared JVM is never
 * pushed toward a real OOM, and {@link HeapLeakAllocator#drain()} releases every
 * retained byte afterwards.
 */
class HeapLeakAllocatorTest {

    private static final class ScriptedProbe implements HeapLeakAllocator.HeapProbe {
        private final AtomicLong used = new AtomicLong();
        private final long max;

        ScriptedProbe(long max) {
            this.max = max;
        }

        @Override
        public long usedBytes() {
            return used.get();
        }

        @Override
        public long maxBytes() {
            return max;
        }

        void grow(long delta) {
            used.addAndGet(delta);
        }
    }

    @Test
    void neverAllocatesPastCap() {
        ScriptedProbe probe = new ScriptedProbe(100L);
        HeapLeakAllocator allocator = new HeapLeakAllocator(probe, 50L, 10L); // cap = 50 bytes

        long allocated = 0L;
        int guard = 0;
        while (allocator.allocateOnce()) {
            allocated += 10L;
            probe.grow(10L); // simulate the heap tracking the retained sink
            assertThat(allocated).isLessThanOrEqualTo(50L);
            if (++guard > 1_000) {
                throw new AssertionError("allocator never capped");
            }
        }

        assertThat(allocated).isEqualTo(50L);   // exactly at 50% of max
        assertThat(probe.usedBytes()).isEqualTo(50L);
        assertThat(allocator.isCapped()).isTrue();
        assertThat(allocator.allocatedBytes()).isEqualTo(50L);
    }

    @Test
    void capOfEightyPercentStopsJustBeforeTheCeiling() {
        ScriptedProbe probe = new ScriptedProbe(1_000L);
        HeapLeakAllocator allocator = new HeapLeakAllocator(probe, 80L, 100L); // cap = 800

        int allocated = 0;
        while (allocator.allocateOnce()) {
            allocated += 100;
            probe.grow(100);
            assertThat(allocated).isLessThanOrEqualTo(800);
        }
        assertThat(allocated).isEqualTo(800);
    }

    @Test
    void drainReleasesEverythingSoNothingPersists() {
        ScriptedProbe probe = new ScriptedProbe(1_000L);
        HeapLeakAllocator allocator = new HeapLeakAllocator(probe, 80L, 100L);
        allocator.allocateOnce();
        allocator.allocateOnce();
        assertThat(allocator.allocatedBytes()).isEqualTo(200L);

        allocator.drain();

        assertThat(allocator.allocatedBytes()).isZero();
        assertThat(allocator.isCapped()).isFalse();
    }

    @Test
    void refusesWhenAlreadyAtCapWithoutTouchingHeap() {
        ScriptedProbe probe = new ScriptedProbe(100L);
        probe.grow(50L); // already at the 50% cap
        HeapLeakAllocator allocator = new HeapLeakAllocator(probe, 50L, 10L);

        assertThat(allocator.allocateOnce()).isFalse();
        assertThat(allocator.isCapped()).isTrue();
        assertThat(allocator.allocatedBytes()).isZero();
    }
}
