package com.javalearn.lab.seckill.jdk8.lab.toggle;

/**
 * The six runtime experiment switches of the lab.
 *
 * <p>The enum order is the public order of {@code GET /lab/toggles} on the
 * JDK 21 shell, so the JSON contract stays stable across instances and
 * runtimes.</p>
 */
public enum LabToggle {

    LOCK("lock"),
    IDEMPOTENCY("idempotency"),
    MQ("mq"),
    RATELIMIT("ratelimit"),
    CIRCUITBREAKER("circuitbreaker"),
    CACHE("cache");

    private final String key;

    LabToggle(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * Resolves an external key exactly (no trimming/case folding) so a typo is
     * a 400 instead of silently toggling a different protection.
     */
    public static LabToggle fromKey(String key) {
        for (LabToggle toggle : values()) {
            if (toggle.key.equals(key)) {
                return toggle;
            }
        }
        return null;
    }
}
