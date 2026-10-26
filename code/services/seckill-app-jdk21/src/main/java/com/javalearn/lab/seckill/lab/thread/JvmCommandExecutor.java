package com.javalearn.lab.seckill.lab.thread;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

/**
 * Seam between the snapshot bundle (design §9.3) and the JVM diagnostic tool that
 * produces it. The production implementation spawns {@code jcmd}; tests supply
 * canned output (or a failure) without Docker and without a real JVM to attach to.
 *
 * <p>One call = one {@code jcmd <pid> <args...>} invocation. The returned text is
 * the command's combined stdout/stderr; a non-zero exit or a timeout is reported
 * as an exception, which the snapshot service turns into a WARN + failure metric
 * instead of a failed {@code freeze}.</p>
 */
@FunctionalInterface
public interface JvmCommandExecutor {

    /**
     * Runs one diagnostic command against this JVM and returns its text output.
     *
     * @param args    jcmd arguments after the pid, e.g. {@code ["Thread.print", "-l"]}
     * @param timeout hard wall-clock budget for the whole invocation
     * @throws IOException          the process could not run, timed out, or exited non-zero
     * @throws InterruptedException the calling thread was interrupted while waiting
     */
    String execute(List<String> args, Duration timeout) throws IOException, InterruptedException;
}
