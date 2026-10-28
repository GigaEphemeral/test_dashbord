package com.javalearn.lab.chaos.bench.exec;

/**
 * The launch request is not allowed by the UI whitelist or the parameter
 * contract: unknown/non-UI scenario, unknown parameter name, wrong type,
 * negative number, above the configured VU/duration cap, control characters,
 * or an attempt to override a forced value ({@code RUN_ID}, {@code BASE_URL},
 * {@code K6_PROMETHEUS_RW_*}).
 *
 * <p>This is a request rejection (a 4xx once B2-02b exposes the endpoint), not
 * an availability problem; a missing {@code scripts-host-dir} is
 * {@code BenchUnavailableException} (503/50303) instead. The planner never
 * touches Docker.</p>
 */
public class BenchLaunchRejectedException extends RuntimeException {

    public BenchLaunchRejectedException(String message) {
        super(message);
    }

    public BenchLaunchRejectedException(String message, Throwable cause) {
        super(message, cause);
    }
}
