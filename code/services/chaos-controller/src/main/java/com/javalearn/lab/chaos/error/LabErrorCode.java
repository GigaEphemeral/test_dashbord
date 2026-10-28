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
