package com.javalearn.lab.seckill.lab.toggle;

/**
 * The runtime cache-defense strategies of experiment M4 (design section 8.4):
 * M4-1 added the three penetration modes, M4-2 adds the two hot-key breakdown
 * modes. One switch drives both paths because the read path runs a single guard
 * per L1+L2 miss.
 *
 * <p>The keys are the public contract of {@code GET/POST /lab/cache/mode} and of
 * the scenario JSON, so they are matched exactly (no trimming or case folding):
 * a typo must be a 400 instead of silently enabling a different protection.</p>
 *
 * <ul>
 *   <li>{@code off} — no protection: every request for a missing product
 *       reaches the database. This is the symptom the lab demonstrates.</li>
 *   <li>{@code null-cache} — the miss is cached as a marker with a short TTL
 *       plus jitter, so repeats do not stampede the database and the markers do
 *       not expire in lockstep with the avalanche keys.</li>
 *   <li>{@code bloom} — a Redisson bloom filter over the existing product ids;
 *       a false positive falls back to the database and keeps the normal 404
 *       contract.</li>
 *   <li>{@code mutex} — a hot key that just expired is rebuilt by exactly one
 *       request holding a Redisson lock; the others wait and double-check the
 *       cache before touching the database (design 8.4 击穿 row; costs P99,
 *       which is the visible trade-off).</li>
 *   <li>{@code logical-expire} — the guard keeps the last good value with its
 *       own logical expiry inside a longer physical retention window; a
 *       logically expired value is served stale immediately while one bounded
 *       asynchronous refresh rebuilds it (brief staleness is the trade-off).</li>
 * </ul>
 */
public enum CacheDefenseMode {

    OFF("off"),
    NULL_CACHE("null-cache"),
    BLOOM("bloom"),
    MUTEX("mutex"),
    LOGICAL_EXPIRE("logical-expire");

    private final String key;

    CacheDefenseMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * @return the matching mode, or {@code null} when the key is unknown
     */
    public static CacheDefenseMode fromKey(String key) {
        for (CacheDefenseMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }
}
