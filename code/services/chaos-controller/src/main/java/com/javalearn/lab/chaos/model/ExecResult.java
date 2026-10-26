package com.javalearn.lab.chaos.model;

/**
 * Outcome of one bounded {@code docker exec} through the gateway.
 *
 * <p>stdout and stderr are kept apart on purpose: parsers only ever read
 * stdout, while stderr carries the diagnostic text of a failed command
 * (truncated by the caller before it reaches a log line).</p>
 *
 * @param stdout   captured standard output (UTF-8)
 * @param stderr   captured standard error (UTF-8)
 * @param exitCode process exit code, or {@code null} when the exec was aborted
 *                 before the container reported one
 * @param timedOut true when the Java-side wait elapsed first; the command may
 *                 still be running inside the container, so the exit code is
 *                 unknown
 */
public record ExecResult(String stdout, String stderr, Integer exitCode, boolean timedOut) {

    /** True when the exec completed and the process exited with {@code 0}. */
    public boolean succeeded() {
        return !timedOut && exitCode != null && exitCode == 0;
    }
}
