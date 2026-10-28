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
    /**
     * Stage2 B2-02b-1: the launch request is not allowed by the bench
     * whitelist/parameter contract (or a run id is not a safe container/path
     * segment). Distinct from the generic 40001 so the console can point at
     * the scenario/parameter that was refused.
     */
    BENCH_REQUEST_REJECTED("40002", "Bench launch request rejected"),
    /**
     * Stage2 B2-02b-1: a k6 container with the same name is already running (or
     * not yet exited). The executor never replaces a live run; the console can
     * stop it or wait for it to finish.
     */
    K6_CONTAINER_CONFLICT("40904", "k6 run container conflict"),
    /**
     * Stage2 B2-04a: an operation close was refused because the row is not
     * UNKNOWN anymore (already settled, or closed with the other terminal
     * verdict). Distinct from 40901 so the console can explain that only
     * UNKNOWN operations can be closed.
     */
    OPERATION_NOT_CLOSEABLE("40905", "Operation is not UNKNOWN and cannot be closed"),
    BAD_GATEWAY("50201", "Bad gateway"),
    RESPONSE_TOO_LARGE("50202", "Upstream response too large"),
    /**
     * Stage2 B1-01: the ledger cannot answer because MySQL/migration is not
     * ready (state DEGRADED/NO_DATA) or a live ledger query failed. Distinct
     * from the generic 50301 so the console can point at
     * {@code GET /lab/ledger/status}.
     */
    LEDGER_UNAVAILABLE("50302", "Ledger unavailable"),
    /**
     * Stage2 B2-01b: the k6 scenario manifest is missing, unreadable or fails
     * validation. The endpoint refuses with 503 instead of returning an empty
     * catalog, so the console can render "场景目录不可用" rather than a fake OK.
     */
    BENCH_UNAVAILABLE("50303", "Bench scenario catalog unavailable");

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
