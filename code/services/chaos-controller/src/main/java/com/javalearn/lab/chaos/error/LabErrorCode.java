package com.javalearn.lab.chaos.error;

import com.javalearn.lab.common.ErrorCode;

public enum LabErrorCode implements ErrorCode {

    FORBIDDEN("40301", "Forbidden"),
    /**
     * B0-08 slice B: the fault target is the MySQL cluster probe container, so a
     * kill/freeze/restart would take the observer down with it. Distinct from
     * {@link #FORBIDDEN} on purpose - the refusal is about observability, not
     * about permission, and the console offers a different remedy.
     */
    PROBE_TARGET_PROTECTED("40302", "Target is the MySQL cluster probe container"),
    /**
     * B1-02: the single-writer environment lock is held by another run. Distinct
     * from the generic 40901 so the console can show the holder run and the
     * remedy instead of a bare conflict.
     */
    ENV_LOCK_HELD("40902", "Write-run environment lock is held"),
    /**
     * Stage2 B1-04: the run pre-check found at least one blocking item, so the
     * start is refused before any transition/lock. The response {@code data}
     * carries the full report; there is no force override.
     */
    PRECHECK_BLOCKED("40903", "Run pre-check blocked the start"),
    METHOD_NOT_ALLOWED("40501", "Method not allowed"),
    PAYLOAD_TOO_LARGE("41301", "Payload too large"),
    BAD_GATEWAY("50201", "Bad gateway"),
    RESPONSE_TOO_LARGE("50202", "Upstream response too large"),
    /**
     * Stage2 B1-01: the ledger cannot answer because MySQL/migration is not
     * ready (state DEGRADED/NO_DATA) or a live ledger query failed. Distinct
     * from the generic 50301 so the console can point at
     * {@code GET /lab/ledger/status}.
     */
    LEDGER_UNAVAILABLE("50302", "Ledger unavailable");

    private final String code;
    private final String message;

    LabErrorCode(String code, String message) {
        this.code = code;
        this.message = message;
    }

    @Override
    public String code() {
        return code;
    }

    @Override
    public String message() {
        return message;
    }
}
