package com.javalearn.lab.chaos.precheck;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * One pre-check finding.
 *
 * @param key     stable check key, e.g. {@code traffic.stopped}
 * @param status  PASS / WARN / BLOCK
 * @param message one-line human explanation, safe to show in the console
 * @param detail  evidence for the operator (container names, remaining TTLs,
 *                image ids, memory numbers). Never contains container env or
 *                credentials.
 */
public record PrecheckItem(String key, PrecheckStatus status, String message,
                           Map<String, Object> detail) {

    public PrecheckItem {
        // Evidence legitimately carries nulls ("the sample is missing"), so this
        // is an unmodifiable copy instead of Map.copyOf (which rejects nulls).
        detail = detail == null ? Map.of()
                : Collections.unmodifiableMap(new LinkedHashMap<>(detail));
    }

    public static PrecheckItem pass(String key, String message, Map<String, Object> detail) {
        return new PrecheckItem(key, PrecheckStatus.PASS, message, detail);
    }

    public static PrecheckItem warn(String key, String message, Map<String, Object> detail) {
        return new PrecheckItem(key, PrecheckStatus.WARN, message, detail);
    }

    public static PrecheckItem block(String key, String message, Map<String, Object> detail) {
        return new PrecheckItem(key, PrecheckStatus.BLOCK, message, detail);
    }

    public boolean blocking() {
        return status == PrecheckStatus.BLOCK;
    }

    public boolean warning() {
        return status == PrecheckStatus.WARN;
    }

    public String wireStatus() {
        return status.name();
    }

    /** Key + message, short enough for an operation summary. */
    public String summary() {
        return key + ": " + message;
    }
}
