package com.javalearn.lab.chaos.bench.exec;

import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.error.BenchReportTooLargeException;
import com.javalearn.lab.chaos.error.BenchRunConflictException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.regex.Pattern;

/**
 * Stage2 B2-02b-1: executes an already-rendered {@link BenchLaunchPlan} and
 * answers read-only questions about it.
 *
 * <p>The executor consumes plans only. It does not read the ledger and does not
 * change the run state - B2-02b-2 owns the lifecycle wiring. Its filesystem
 * surface is exactly {@code <reports-mount>/<runId>/<summary-file>}; every run
 * id is re-validated before it becomes a path segment, so {@code ..},
 * separators and absolute paths are refused.</p>
 */
public class BenchRunExecutor {

    /** Same character set the planner keeps; the operator path is no exception. */
    private static final Pattern SAFE_RUN_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final int MAX_RUN_ID_LENGTH = 128;
    static final int DEFAULT_LOG_TAIL = 200;

    private final BenchExecProperties properties;
    private final K6ContainerGateway gateway;

    public BenchRunExecutor(BenchExecProperties properties, K6ContainerGateway gateway) {
        this.properties = properties;
        this.gateway = gateway;
    }

    /**
     * Ensures the image exists, creates the report directory, refuses a live
     * name conflict (an exited container is removed), then creates and starts.
     */
    public BenchExecutionState start(BenchLaunchPlan plan) {
        if (plan == null) {
            throw new BenchLaunchRejectedException("a launch plan is required to start k6");
        }
        String runId = requireSafeRunId(plan.runId());
        gateway.ensureImage(plan.image());

        Path runDir = reportDirectory(runId);
        try {
            Files.createDirectories(runDir);
            // The grafana/k6 image runs as uid 12345, not root. The controller
            // (root) creates this directory on the shared volume, so without an
            // explicit mode k6 cannot write summary.json and still exits 0.
            // 0777 on the dedicated per-run reports directory keeps the k6
            // process unprivileged; non-POSIX hosts (unit tests) ignore it.
            Files.setPosixFilePermissions(runDir,
                    java.nio.file.attribute.PosixFilePermissions.fromString("rwxrwxrwx"));
        } catch (UnsupportedOperationException ignored) {
            // Windows/unit-test filesystem: the live container always sees a
            // POSIX volume, so this branch only affects local tests.
        } catch (IOException e) {
            throw new BenchUnavailableException("cannot create the k6 report directory '"
                    + runDir + "': " + e.getMessage(), e);
        }

        K6ContainerGateway.Snapshot existing = gateway.inspect(runId);
        if (!existing.isAbsent()) {
            if (existing.isLive()) {
                throw new BenchRunConflictException("k6 container '" + existing.containerName()
                        + "' for run '" + runId + "' is still " + existing.status()
                        + "; stop it before launching again");
            }
            String staleName = existing.containerName() == null
                    ? plan.containerName() : existing.containerName();
            gateway.remove(staleName);
        }

        gateway.createAndStart(plan);
        return state(runId);
    }

    public BenchExecutionState state(String runId) {
        String safeRunId = requireSafeRunId(runId);
        K6ContainerGateway.Snapshot snapshot = gateway.inspect(safeRunId);
        Path report = reportFile(safeRunId);
        boolean available = Files.isRegularFile(report);
        long bytes = 0L;
        if (available) {
            try {
                bytes = Files.size(report);
            } catch (IOException e) {
                available = false;
            }
        }
        return new BenchExecutionState(snapshot.status(), snapshot.containerName(),
                snapshot.containerId(), snapshot.scenario(), snapshot.exitCode(),
                snapshot.startedAt(), snapshot.finishedAt(), available, bytes,
                noteFor(snapshot, available, bytes));
    }

    /**
     * Reads a bounded tail. Out-of-range {@code tail}/{@code maxBytes} are
     * clamped to the configured caps (never a 400); an unknown run is 404.
     */
    public K6ContainerGateway.LogChunk logs(String runId, Integer tail, Integer maxBytes) {
        String safeRunId = requireSafeRunId(runId);
        K6ContainerGateway.Snapshot snapshot = gateway.inspect(safeRunId);
        if (snapshot.isAbsent()) {
            throw new ContainerNotFoundException("k6 container for run '" + safeRunId
                    + "' does not exist");
        }
        int effectiveTail = clamp(tail == null ? DEFAULT_LOG_TAIL : tail,
                1, Math.max(1, properties.getLogsMaxTail()));
        int effectiveBytes = clamp(maxBytes == null ? properties.getLogsMaxBytes() : maxBytes,
                1, Math.max(1, properties.getLogsMaxBytes()));
        String containerName = snapshot.containerName() == null
                ? properties.getContainerPrefix() + safeRunId : snapshot.containerName();
        return gateway.logs(containerName, effectiveTail, effectiveBytes);
    }

    public BenchReportContent report(String runId) {
        String safeRunId = requireSafeRunId(runId);
        Path report = reportFile(safeRunId);
        if (!Files.isRegularFile(report)) {
            throw new BenchReportNotFoundException("summary report for run '" + safeRunId
                    + "' is not available at " + report
                    + " (k6 writes it on exit; check the k6 logs)");
        }
        long maxBytes = Math.max(1L, properties.getReportMaxBytes());
        try {
            long size = Files.size(report);
            if (size > maxBytes) {
                throw new BenchReportTooLargeException("summary report for run '" + safeRunId
                        + "' is " + size + " bytes, above lab.bench.exec.report-max-bytes ("
                        + maxBytes + "); the download is refused");
            }
            byte[] content = Files.readAllBytes(report);
            if (content.length > maxBytes) {
                throw new BenchReportTooLargeException("summary report for run '" + safeRunId
                        + "' grew to " + content.length + " bytes while reading, above "
                        + "lab.bench.exec.report-max-bytes (" + maxBytes + ")");
            }
            return new BenchReportContent(safeRunId + "-" + properties.getSummaryFile(), content);
        } catch (IOException e) {
            throw new BenchUnavailableException("cannot read the summary report at '"
                    + report + "': " + e.getMessage(), e);
        }
    }

    public boolean stop(String runId) {
        String safeRunId = requireSafeRunId(runId);
        K6ContainerGateway.Snapshot snapshot = gateway.inspect(safeRunId);
        if (snapshot.isAbsent()) {
            return false;
        }
        String containerName = snapshot.containerName() == null
                ? properties.getContainerPrefix() + safeRunId : snapshot.containerName();
        return gateway.stop(containerName, Math.max(1, properties.getStopGraceSeconds()));
    }

    /**
     * Best-effort stop + remove. The reports directory is kept: the console may
     * still download the summary after the container is gone.
     */
    public boolean cleanup(String runId) {
        String safeRunId = requireSafeRunId(runId);
        K6ContainerGateway.Snapshot snapshot = gateway.inspect(safeRunId);
        if (snapshot.isAbsent()) {
            return false;
        }
        String containerName = snapshot.containerName() == null
                ? properties.getContainerPrefix() + safeRunId : snapshot.containerName();
        gateway.stop(containerName, Math.max(1, properties.getStopGraceSeconds()));
        return gateway.remove(containerName);
    }

    /**
     * Read-only inventory of {@code lab.role=k6} containers. B2-02b-2 decides
     * what to clean when the controller boots; this step only exposes it and
     * never removes anything by itself.
     */
    public List<K6ContainerGateway.ContainerSummary> sweepOrphans() {
        return gateway.listK6Containers();
    }

    // ------------------------------------------------------------------ paths

    private String noteFor(K6ContainerGateway.Snapshot snapshot, boolean available, long bytes) {
        if (snapshot.isAbsent()) {
            return "no k6 container has been created for this run";
        }
        if (available && bytes > Math.max(1L, properties.getReportMaxBytes())) {
            return "the summary report exceeds lab.bench.exec.report-max-bytes and cannot be "
                    + "downloaded";
        }
        if (available) {
            return null;
        }
        if (K6ContainerGateway.STATUS_RUNNING.equals(snapshot.status())) {
            return "the summary report is written when k6 exits";
        }
        if (K6ContainerGateway.STATUS_EXITED.equals(snapshot.status())) {
            return "the container exited without writing " + properties.getSummaryFile()
                    + "; check the logs";
        }
        return "no summary report has been written yet";
    }

    private Path reportDirectory(String runId) {
        String mount = properties.getReportsMount();
        if (mount == null || mount.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.reports-mount is blank: the "
                    + "executor cannot locate the reports directory");
        }
        Path root = Paths.get(mount).toAbsolutePath().normalize();
        Path directory = root.resolve(runId).normalize();
        if (!directory.startsWith(root) || directory.equals(root)) {
            throw new BenchLaunchRejectedException("runId '" + runId + "' escapes the reports "
                    + "directory; refusing an unsafe path segment");
        }
        return directory;
    }

    private Path reportFile(String runId) {
        String summaryFile = properties.getSummaryFile();
        if (summaryFile == null || summaryFile.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.summary-file is blank: the "
                    + "executor cannot locate the summary report");
        }
        Path directory = reportDirectory(runId);
        Path file = directory.resolve(summaryFile).normalize();
        if (!file.startsWith(directory) || file.equals(directory)) {
            throw new BenchUnavailableException("lab.bench.exec.summary-file must be a plain "
                    + "file name inside the run directory; got '" + summaryFile + "'");
        }
        return file;
    }

    /**
     * Defence in depth for path variables: the planner already sanitises, but a
     * request can still carry {@code ../} or a separator, and both must be
     * refused before touching the filesystem.
     */
    static String requireSafeRunId(String runId) {
        String value = runId == null ? "" : runId;
        if (value.isEmpty()) {
            throw new BenchLaunchRejectedException("runId must not be blank");
        }
        if (value.length() > MAX_RUN_ID_LENGTH) {
            throw new BenchLaunchRejectedException("runId is longer than " + MAX_RUN_ID_LENGTH
                    + " characters; refusing an unwieldy container/path segment");
        }
        if (!SAFE_RUN_ID.matcher(value).matches() || value.contains("..")) {
            throw new BenchLaunchRejectedException("runId '" + runId + "' is not a safe "
                    + "container/path segment (allowed: letters, digits, '.', '_', '-'; no '..')");
        }
        return value;
    }

    private static int clamp(int value, int min, int max) {
        return Math.max(min, Math.min(value, max));
    }
}
