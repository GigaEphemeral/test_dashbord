package com.javalearn.lab.chaos.action;

import java.util.List;

/**
 * Outcome of a network-fault cleanup, embedded in {@link StopAllResult} and
 * returned by {@code POST /chaos/toxiproxy/reset-all}.
 *
 * @param status       {@code reset} (all proxies restored), {@code partial}
 *                     (some restored), {@code failed} (none restored),
 *                     {@code disabled} ({@code chaos.toxiproxy.enabled=false})
 *                     or {@code not-configured} (no cleaner wired)
 * @param proxiesReset proxies whose toxics were removed and which are enabled
 * @param proxiesTotal proxies the cleaner was configured to restore
 * @param errors       one human-readable line per failed proxy; never a secret
 */
public record NetworkResetReport(String status, int proxiesReset, int proxiesTotal, List<String> errors) {

    public static final String STATUS_RESET = "reset";
    public static final String STATUS_PARTIAL = "partial";
    public static final String STATUS_FAILED = "failed";
    public static final String STATUS_DISABLED = "disabled";
    public static final String STATUS_NOT_CONFIGURED = "not-configured";

    public NetworkResetReport {
        errors = errors == null ? List.of() : List.copyOf(errors);
    }

    public static NetworkResetReport notConfigured() {
        return new NetworkResetReport(STATUS_NOT_CONFIGURED, 0, 0, List.of());
    }

    public static NetworkResetReport disabled() {
        return new NetworkResetReport(STATUS_DISABLED, 0, 0, List.of());
    }
}
