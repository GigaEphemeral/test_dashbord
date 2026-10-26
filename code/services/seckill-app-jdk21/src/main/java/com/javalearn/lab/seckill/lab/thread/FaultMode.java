package com.javalearn.lab.seckill.lab.thread;

/**
 * The seven injectable faulty-thread modes of the thread lab (design §9.1).
 *
 * <p>The keys are the public contract of {@code /lab/thread/**} and of the
 * scenario JSON consumed by the Vue page (Task #34), so they are matched exactly
 * like {@link com.javalearn.lab.seckill.lab.toggle.CacheDefenseMode}: a typo must
 * be a 400 instead of silently injecting a different fault.</p>
 *
 * <ul>
 *   <li>{@code deadlock} — two threads acquiring two monitors in opposite order:
 *       the {@code blocked} thread-state fingerprint.</li>
 *   <li>{@code stuck} — threads parked on a latch that never opens: the
 *       {@code waiting} fingerprint.</li>
 *   <li>{@code cpu-burn} — spinning runnable threads: {@code process_cpu_usage}
 *       and the {@code runnable} fingerprint.</li>
 *   <li>{@code heap-leak} — controlled-rate allocations with a hard cap so the
 *       shared JVM is never pushed to a real OOM: the stepped-heap fingerprint.</li>
 *   <li>{@code oom} — unbounded allocations; refused unless the instance is
 *       explicitly marked throwaway so the shared app can never kill itself.</li>
 *   <li>{@code thread-leak} — a steady drip of (platform or virtual) threads with
 *       a bounded live ceiling: the rising-thread-count fingerprint.</li>
 *   <li>{@code starvation} — a tiny pool fed more blocking tasks than it can
 *       serve: {@code active == max} plus a growing queue.</li>
 * </ul>
 */
public enum FaultMode {

    DEADLOCK("deadlock"),
    STUCK("stuck"),
    CPU_BURN("cpu-burn"),
    HEAP_LEAK("heap-leak"),
    OOM("oom"),
    THREAD_LEAK("thread-leak"),
    STARVATION("starvation");

    private final String key;

    FaultMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * @return the matching mode, or {@code null} when the key is unknown
     */
    public static FaultMode fromKey(String key) {
        for (FaultMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }
}
