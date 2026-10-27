package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Seam between the snapshot bundle (design §9.3) and the JDK 8 diagnostic tools.
 *
 * <p>One call = one process invocation of the <em>complete</em> argv (tool, flags,
 * pid), e.g. {@code jstack -l 1}. JDK 8's tools put the pid last, unlike
 * {@code jcmd <pid> ...}, so the executor receives the whole command instead of
 * {@code args-after-pid}. The returned text is the combined stdout/stderr; a
 * non-zero exit or a timeout is reported as an exception, which the snapshot
 * service turns into WARN + failure metric instead of a failed request.</p>
 *
 * <p>Tests supply canned output (or a failure) without Docker and without a real
 * JVM to attach to.</p>
 */
@FunctionalInterface
public interface JvmCommandExecutor {

    /**
     * Runs one diagnostic command and returns its text output.
     *
     * @param command complete argv, e.g. {@code [jstack, -l, 1]}
     * @param timeout hard wall-clock budget for the whole invocation
     * @throws IOException          the process could not run, timed out, or exited non-zero
     * @throws InterruptedException the calling thread was interrupted while waiting
     */
    String execute(List<String> command, Duration timeout) throws IOException, InterruptedException;
}
