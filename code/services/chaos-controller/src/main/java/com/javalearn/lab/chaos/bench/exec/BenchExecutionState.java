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
 * @param exitCode        Docker exit code, {@code null} while running/absent
 * @param startedAt       Docker ISO timestamp, {@code null} when never started
 * @param finishedAt      Docker ISO timestamp, {@code null} when still running
 * @param reportAvailable whether {@code /reports/<runId>/<summary-file>} exists
 * @param reportBytes     report size in bytes, {@code 0} when missing
 * @param note            human hint (absent run, missing report, oversized
 *                        report); {@code null} when there is nothing to add
 */
public record BenchExecutionState(String status, String containerName, String containerId,
                                  String scenario, Integer exitCode, String startedAt,
                                  String finishedAt, boolean reportAvailable, long reportBytes,
                                  String note) {
}
