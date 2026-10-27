package com.javalearn.lab.seckill.jdk8.lab.snapshot;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.TimeUnit;

/**
 * {@link JvmCommandExecutor} backed by {@code jstack}/{@code jmap}/{@code jcmd}
 * subprocesses (design §9.3, JDK 8 variant).
 *
 * <p>Runs <em>inside</em> the app container against this JVM's own pid, so no
 * container name or {@code docker exec} is needed at capture time. The runtime
 * stage of the module Dockerfile is {@code eclipse-temurin:8-jdk-alpine}, which
 * is what makes {@code jstack}/{@code jmap} available (the old JRE stage did not
 * ship them). The same commands can be typed by hand for the teaching story:
 * {@code docker exec jl-seckill-app-jdk8 jstack -l 1}.</p>
 *
 * <p>The output stream is drained on a daemon thread while the process is waited
 * for, so a dump larger than the OS pipe buffer cannot deadlock the capture and a
 * hung tool is still bounded by the externalized timeout. This is the Java 8
 * implementation: no {@code InputStream.transferTo}, the copy loop is explicit.</p>
 */
public final class ProcessJvmCommandExecutor implements JvmCommandExecutor {

    /** Grace period for the drain thread after a forced kill, before we give up on it. */
    private static final long DRAIN_JOIN_GRACE_MS = 1_000L;
    private static final int BUFFER_SIZE = 8_192;

    @Override
    public String execute(List<String> command, Duration timeout)
            throws IOException, InterruptedException {
        Objects.requireNonNull(command, "command");
        if (command.isEmpty()) {
            throw new IOException("empty diagnostic command");
        }
        ProcessBuilder builder = new ProcessBuilder(command).redirectErrorStream(true);
        // The app container exports JAVA_TOOL_OPTIONS=-Xmx512m; the tool launcher
        // is a JVM too and would prepend "Picked up JAVA_TOOL_OPTIONS: ..." to the
        // dump. Strip the launcher-only variables from the child (same fix the
        // JDK 21 executor needed, verified live in step-52).
        builder.environment().remove("JAVA_TOOL_OPTIONS");
        builder.environment().remove("_JAVA_OPTIONS");
        builder.environment().remove("JDK_JAVA_OPTIONS");
        Process process = builder.start();
        ByteArrayOutputStream output = new ByteArrayOutputStream();
        Thread drain = new Thread(new Drain(process, output), "lab-snapshot-tool-drain");
        drain.setDaemon(true);
        drain.start();

        if (!process.waitFor(timeout.toMillis(), TimeUnit.MILLISECONDS)) {
            process.destroyForcibly();
            drain.join(DRAIN_JOIN_GRACE_MS);
            throw new IOException("snapshot tool timed out after " + timeout.toMillis() + "ms: "
                    + String.join(" ", command));
        }
        drain.join(DRAIN_JOIN_GRACE_MS);
        String text = new String(output.toByteArray(), java.nio.charset.StandardCharsets.UTF_8);
        int exit = process.exitValue();
        if (exit != 0) {
            throw new IOException("snapshot tool exited with " + exit + ": " + text.trim());
        }
        return text;
    }

    /** Java 8 has no {@code InputStream.transferTo}; a plain blocking copy is enough. */
    private static final class Drain implements Runnable {

        private final Process process;
        private final ByteArrayOutputStream sink;

        private Drain(Process process, ByteArrayOutputStream sink) {
            this.process = process;
            this.sink = sink;
        }

        @Override
        public void run() {
            byte[] buffer = new byte[BUFFER_SIZE];
            try (InputStream in = process.getInputStream()) {
                int read;
                while ((read = in.read(buffer)) != -1) {
                    sink.write(buffer, 0, read);
                }
            } catch (IOException closed) {
                // The process was destroyed on timeout; the timeout itself is reported.
            }
        }
    }
}
