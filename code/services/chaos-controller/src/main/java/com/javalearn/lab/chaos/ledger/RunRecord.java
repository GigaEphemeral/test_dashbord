package com.javalearn.lab.chaos.ledger;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * One row of {@code lab_run}. Instants are stored as UTC {@code DATETIME(3)}.
 *
 * <p>B3-00a adds three experiment-draft fields: {@code name} (display name,
 * {@code run_id} stays the key), {@code nodes} (enabled Java service names as a
 * comma separated string, {@code null} = pre-B3 behaviour of all configured
 * targets) and {@code archived} (hidden from the default list and exempt from
 * retention). The nine-argument constructor keeps older callers/tests on the
 * pre-B3 shape.</p>
 */
public record RunRecord(
        String runId,
        String scenario,
        String name,
        String nodes,
        RunStatus status,
        String sourceCommit,
        String operator,
        Instant createdAt,
        Instant updatedAt,
        Instant startedAt,
        Instant endedAt,
        boolean archived) {

    public RunRecord(String runId, String scenario, RunStatus status, String sourceCommit,
                     String operator, Instant createdAt, Instant updatedAt, Instant startedAt,
                     Instant endedAt) {
        this(runId, scenario, null, null, status, sourceCommit, operator, createdAt, updatedAt,
                startedAt, endedAt, false);
    }

    /** Enabled nodes as a list; an absent selection yields an empty list. */
    public List<String> nodeList() {
        if (nodes == null || nodes.isBlank()) {
            return List.of();
        }
        return Arrays.stream(nodes.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }
}
