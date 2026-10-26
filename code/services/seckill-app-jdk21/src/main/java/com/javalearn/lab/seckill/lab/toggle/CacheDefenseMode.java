package com.javalearn.lab.seckill.lab.toggle;

/**
 * The runtime cache-penetration strategies of experiment M4-1.
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
 * </ul>
 */
public enum CacheDefenseMode {

    OFF("off"),
    NULL_CACHE("null-cache"),
    BLOOM("bloom");

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
