package com.javalearn.lab.chaos.toxiproxy;

import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;

/**
 * The three network-fault shapes exposed by this controller.
 *
 * <p>Toxiproxy's own vocabulary is used on the wire: {@code latency},
 * {@code bandwidth} and {@code timeout}. A network cut is modelled as the
 * {@code timeout} toxic (see {@link ToxicSpec.Timeout}) rather than an extra
 * type; disabling the proxy is the harder cut offered by the
 * {@code disable}/{@code enable} endpoints.</p>
 */
public enum ToxicType {

    LATENCY("latency"),
    BANDWIDTH("bandwidth"),
    TIMEOUT("timeout");

    private static final String ALLOWED = "latency|bandwidth|timeout";

    private final String wireName;

    ToxicType(String wireName) {
        this.wireName = wireName;
    }

    public String wireName() {
        return wireName;
    }

    public static ToxicType parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new InvalidToxiproxyRequestException("toxic type is required (allowed: " + ALLOWED + ")");
        }
        String normalized = raw.trim();
        for (ToxicType type : values()) {
            if (type.wireName.equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        throw new InvalidToxiproxyRequestException(
                "Unsupported toxic type: " + raw + " (allowed: " + ALLOWED + ")");
    }
}
