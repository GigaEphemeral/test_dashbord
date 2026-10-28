package com.javalearn.lab.chaos.bench.exec;

/**
 * Stage2 B2-02b-1: read-only view of one k6 run. It is assembled from Docker
 * metadata plus the reports directory; it never reads the ledger and never
 * changes the run state.
 *
 * @param status          {@code RUNNING}/{@code EXITED}/{@code CREATED}/... or
 *                        {@code ABSENT} (an absent container is a normal answer,
 *                        not a 404)
 * @param containerName   plan container name; for an absent container this is
 *                        the name it would have
 * @param containerId     Docker id, {@code null} when absent
 * @param scenario        {@code lab.scenario} label, {@code null} when absent
 * @param trafficMode     {@code lab.traffic-mode} label (Stage2 B2-03a),
 *                        {@code null} for an absent container or a container
 *                        created by a pre-B2-03a build
 * @param targetInstance  {@code lab.target-instance} label, {@code null} for
 *                        an absent container or a pre-B2-03a container
 * @param exitCode        Docker exit code, {@code null} while running/absent
 * @param startedAt       Docker ISO timestamp, {@code null} when never started
 * @param finishedAt      Docker ISO timestamp, {@code null} when still running
 * @param reportAvailable whether {@code /reports/<runId>/<summary-file>} exists
 * @param reportBytes     report size in bytes, {@code 0} when missing
 * @param note            human hint (absent run, missing report, oversized
 *                        report); {@code null} when there is nothing to add
 */
public record BenchExecutionState(String status, String containerName, String containerId,
                                  String scenario, String trafficMode, String targetInstance,
                                  Integer exitCode, String startedAt, String finishedAt,
                                  boolean reportAvailable, long reportBytes, String note) {

    /**
     * State without traffic attribution (absent containers and legacy
     * fixtures): both attribution fields stay {@code null}, which the JSON
     * contract documents as "unknown".
     */
    public BenchExecutionState(String status, String containerName, String containerId,
                               String scenario, Integer exitCode, String startedAt,
                               String finishedAt, boolean reportAvailable, long reportBytes,
                               String note) {
        this(status, containerName, containerId, scenario, null, null, exitCode, startedAt,
                finishedAt, reportAvailable, reportBytes, note);
    }
}
