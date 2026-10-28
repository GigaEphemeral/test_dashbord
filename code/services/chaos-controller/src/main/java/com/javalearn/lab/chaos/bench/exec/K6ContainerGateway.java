package com.javalearn.lab.chaos.bench.exec;

import java.util.List;

/**
 * Stage2 B2-02b-1: Docker operations for controlled k6 runs. The gateway only
 * consumes a {@link BenchLaunchPlan} - image, argv, env, mounts, labels and
 * network are never taken from a request.
 *
 * <p>Every created container carries the existing safety label
 * {@code lab=java-learn} plus {@code lab.role=k6}, {@code lab.run-id},
 * {@code lab.scenario}, and since Stage2 B2-03a {@code lab.traffic-mode} /
 * {@code lab.target-instance} (see {@link #LABEL_TRAFFIC_MODE} and
 * {@link #LABEL_TARGET_INSTANCE}). Log reads and stop/remove re-check the label
 * so the k6 layer cannot touch other lab containers.</p>
 *
 * <p>Statuses are upper-case ({@code RUNNING}/{@code EXITED}/{@code CREATED}/
 * {@code RESTARTING}/{@code PAUSED}/{@code DEAD}); an unknown run is
 * {@code ABSENT}, never an exception.</p>
 */
public interface K6ContainerGateway {

    /** Stage2 B2-03a attribution label written by the planner. */
    String LABEL_TRAFFIC_MODE = "lab.traffic-mode";
    /** Stage2 B2-03a attribution label written by the planner. */
    String LABEL_TARGET_INSTANCE = "lab.target-instance";

    String STATUS_RUNNING = "RUNNING";
    String STATUS_EXITED = "EXITED";
    String STATUS_CREATED = "CREATED";
    String STATUS_RESTARTING = "RESTARTING";
    String STATUS_PAUSED = "PAUSED";
    String STATUS_DEAD = "DEAD";
    String STATUS_ABSENT = "ABSENT";

    /**
     * Verifies the k6 image is present locally. It is never pulled
     * automatically.
     *
     * @throws com.javalearn.lab.chaos.error.BenchUnavailableException when the
     *         image is missing (message names the {@code docker pull} command)
     *         or the daemon cannot be queried
     */
    void ensureImage(String image);

    /**
     * Creates and starts the container described by the plan.
     *
     * @return the new container id
     * @throws com.javalearn.lab.chaos.error.BenchRunConflictException when a
     *         container with the same name exists and has not exited
     * @throws com.javalearn.lab.chaos.error.BenchUnavailableException when the
     *         image vanished or the daemon is unavailable
     */
    String createAndStart(BenchLaunchPlan plan);

    /** Looks the run up by {@code lab.run-id} label, then by container name. */
    Snapshot inspect(String runId);

    /**
     * Reads a bounded tail of the container's logs.
     *
     * @param tail     requested line count (clamped to the gateway's hard cap)
     * @param maxBytes byte budget; output beyond it is dropped and flagged
     * @throws com.javalearn.lab.chaos.error.ContainerNotFoundException when the
     *         container does not exist
     * @throws com.javalearn.lab.chaos.error.ContainerNotAllowedException when it
     *         is not a k6 run container
     */
    LogChunk logs(String containerName, int tail, int maxBytes);

    /**
     * Stops the container; idempotent.
     *
     * @return {@code true} when a stop was issued, {@code false} when the
     *         container was already stopped or absent
     */
    boolean stop(String containerName, int graceSeconds);

    /**
     * Removes the container; best-effort (a running container is left alone).
     *
     * @return {@code true} when the container was removed
     */
    boolean remove(String containerName);

    /**
     * Read-only inventory of {@code lab.role=k6} containers. B2-02b-2 decides
     * what to clean at startup; this step only exposes the capability.
     */
    List<ContainerSummary> listK6Containers();

    /**
     * One inspected k6 run.
     *
     * @param status          upper-case status or {@link #STATUS_ABSENT}
     * @param exitCode        Docker exit code, {@code null} while running/absent
     * @param startedAt       Docker ISO timestamp, {@code null} when never started
     * @param finishedAt      Docker ISO timestamp, {@code null} when still running
     * @param image           image reported by Docker, {@code null} when absent
     * @param scenario        {@code lab.scenario} label, {@code null} when absent
     * @param trafficMode     {@code lab.traffic-mode} label, {@code null} when
     *                        absent or written by a pre-B2-03a build
     * @param targetInstance  {@code lab.target-instance} label, {@code null}
     *                        when absent or written by a pre-B2-03a build
     */
    record Snapshot(String status, String containerName, String containerId, Integer exitCode,
                    String startedAt, String finishedAt, String image, String scenario,
                    String trafficMode, String targetInstance) {

        /** A container that does not exist: no attribution labels either. */
        public static Snapshot absent(String containerName) {
            return new Snapshot(STATUS_ABSENT, containerName, null, null, null, null, null, null,
                    null, null);
        }

        /**
         * Snapshot without traffic attribution (labels unread by older fakes).
         * The real gateway always fills the two attribution labels.
         */
        public Snapshot(String status, String containerName, String containerId, Integer exitCode,
                        String startedAt, String finishedAt, String image, String scenario) {
            this(status, containerName, containerId, exitCode, startedAt, finishedAt, image,
                    scenario, null, null);
        }

        public boolean isAbsent() {
            return STATUS_ABSENT.equals(status);
        }

        /** True when the container exists but the run is not finished. */
        public boolean isLive() {
            return STATUS_RUNNING.equals(status) || STATUS_CREATED.equals(status)
                    || STATUS_RESTARTING.equals(status) || STATUS_PAUSED.equals(status);
        }
    }

    /**
     * A bounded log answer.
     *
     * @param text          decoded UTF-8 text actually returned
     * @param truncated     true when the byte budget dropped output
     * @param bytes         bytes in {@code text} (UTF-8)
     * @param originalBytes bytes received from Docker before truncation
     */
    record LogChunk(String text, boolean truncated, long bytes, long originalBytes) {
    }

    /** Inventory entry used by {@link BenchRunExecutor#sweepOrphans()}. */
    record ContainerSummary(String containerName, String containerId, String runId, String status) {
    }
}
