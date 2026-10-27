package com.javalearn.lab.seckill.lab.thread;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link JvmCommandExecutor} backed by a {@code jcmd} subprocess (design §9.3).
 *
 * <p>Runs <em>inside</em> the app container against this JVM's own pid, so no
 * container name or {@code docker exec} is needed at capture time. The runtime
 * stage of the module Dockerfile is a JDK image, which is what makes {@code jcmd}
 * available; the same commands can still be typed by hand as
 * {@code docker exec jl-app-1 jcmd 1 Thread.print -l} for the teaching story.</p>
 *
 * <p>The output stream is drained on a daemon thread while the process is waited
 * for, so a dump larger than the OS pipe buffer cannot deadlock the capture and a
 * hung {@code jcmd} is still bounded by the externalized timeout.</p>
 */
public final class ProcessJvmCommandExecutor implements JvmCommandExecutor {

    /** Grace period for the drain thread after a forced kill, before we give up on it. */
    private static final long DRAIN_JOIN_GRACE_MS = 1_000L;

    private final String executable;
    private final long pid;

    public ProcessJvmCommandExecutor(String executable, long pid) {
        this.executable = Objects.requireNonNull(executable, "executable");
        this.pid = pid;
    }

    @Override
    public String execute(List<String> args, Duration timeout) throws IOException, InterruptedException {
        List<String> command = new ArrayList<>(args.size() + 2);
        command.add(executable);
        command.add(Long.toString(pid));
        command.addAll(args);

        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The app container exports JAVA_TOOL_OPTIONS=-Xmx512m; the jcmd launcher
        // is a JVM too and would prepend "Picked up JAVA_TOOL_OPTIONS: ..." to
        // every dump, heap-info and histogram file. The tool needs no heap
        // settings, so strip the launcher-only variables from the child.
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(() -> drain(process, output), "thread-snapshot-jcmd-drain");
        drain.setDaemon(true);
        drain.start();

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            drain.join(DRAIN_JOIN_GRACE_MS);
            throw new IOException("jcmd timed out after " + timeout.toMillis() + "ms: "
                    + String.join(" ", command));
        }
        drain.join(DRAIN_JOIN_GRACE_MS);
        String text = output.toString(StandardCharsets.UTF_8);
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IOException("jcmd exited with " + exit + ": " + text.trim());
        }
        return text;
    }

    private static void drain(Process process, ByteArrayOutputStream sink) {
        try (InputStream in = process.getInputStream()) {
            in.transferTo(sink);
        } catch (IOException closed) {
            // The process was destroyed on timeout; the timeout itself is reported.
        }
    }
}
