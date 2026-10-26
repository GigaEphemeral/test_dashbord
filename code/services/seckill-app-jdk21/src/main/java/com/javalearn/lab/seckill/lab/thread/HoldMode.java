package com.javalearn.lab.seckill.lab.thread;

/**
 * How long an injected fault is meant to live.
 *
 * <ul>
 *   <li>{@code auto} — the fault auto-reverts when its TTL expires.</li>
 *   <li>{@code manual} — a hold mode: the fault never auto-reverts and must be
 *       stopped explicitly (or via {@code stop-all}).</li>
 * </ul>
 */
public enum HoldMode {

    AUTO("auto"),
    MANUAL("manual");

    private final String key;

    HoldMode(String key) {
        this.key = key;
    }

    public String key() {
        return key;
    }

    /**
     * @return the matching hold mode, or {@code null} when the key is unknown
     */
    public static HoldMode fromKey(String key) {
        for (HoldMode mode : values()) {
            if (mode.key.equals(key)) {
                return mode;
            }
        }
        return null;
    }
}
