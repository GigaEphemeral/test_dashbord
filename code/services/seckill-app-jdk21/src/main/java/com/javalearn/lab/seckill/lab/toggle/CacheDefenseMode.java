package com.javalearn.lab.seckill.lab.toggle;

/**
 * The runtime cache-defense strategies of experiment M4 (design section 8.4):
 * M4-1 added the three penetration modes, M4-2 the two hot-key breakdown modes
 * and this step the three avalanche modes. One switch drives the whole read
 * path because it runs a single guard per L1+L2 miss.
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
 *   <li>{@code ttl-jitter} — every product-cache L2 write gets a TTL spread
 *       over ±percent (default ±20%), so the 200 experiment keys that were
 *       seeded with one TTL no longer expire in the same second (design 8.4
 *       雪崩 row, strategy one).</li>
 *   <li>{@code l1-cache} — an explicit L1-first posture: a bounded per-instance
 *       mirror serves the last good value while L2 avalanches, stale for at most
 *       one refresh interval (10s default) before an async rebuild, never longer
 *       than the stale window (60s default), which IS the documented
 *       multi-instance staleness cost (design 8.4 雪崩 row, strategy two).</li>
 *   <li>{@code db-fallback} — 熔断降级到 DB + 限流: avalanche misses still read
 *       the database, but only inside a bounded permits/period budget; beyond
 *       it the request fast fails with {@code 42901} instead of joining the
 *       stampede (design 8.4 雪崩 row, strategy three).</li>
 * </ul>
 */
public enum CacheDefenseMode {

    OFF("off"),
    NULL_CACHE("null-cache"),
    BLOOM("bloom"),
    MUTEX("mutex"),
    LOGICAL_EXPIRE("logical-expire"),
    TTL_JITTER("ttl-jitter"),
    L1_CACHE("l1-cache"),
    DB_FALLBACK("db-fallback");

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
