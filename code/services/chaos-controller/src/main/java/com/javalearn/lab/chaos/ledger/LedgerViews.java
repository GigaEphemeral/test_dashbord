package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** REST payloads for the ledger API. All instants are ISO-8601 UTC strings. */
public final class LedgerViews {

    private LedgerViews() {
    }

    /**
     * B3-00a draft shape. {@code name} is the display label; {@code nodes} is
     * the enabled Java service subset ({@code null} = all configured targets).
     * {@code config} and {@code faultSchedule} are stored as their own
     * {@code lab_run_param} groups and are never forwarded to the k6 planner.
     */
    public record RunCreateRequest(String scenario, Map<String, Object> params, String name,
                                   List<String> nodes, Map<String, Object> config,
                                   List<Map<String, Object>> faultSchedule) {

        public RunCreateRequest(String scenario, Map<String, Object> params) {
            this(scenario, params, null, null, null, null);
        }
    }

    public record RunCreateView(String runId, String status, String createdAt) {
    }

    public record RunView(String runId, String scenario, String name, List<String> nodes,
                          String status, String sourceCommit, String operator, String createdAt,
                          String updatedAt, String startedAt, String endedAt, boolean archived) {
    }

    /** Result of a hard delete: the row and its child rows are gone. */
    public record RunDeleteView(String runId, boolean deleted, String artifacts) {
    }

    public record StepView(String stepKey, String fromStatus, String toStatus, String occurredAt,
                           Long dwellMs, JsonNode detail) {
    }

    public record ParamView(String group, JsonNode payload, String savedAt) {
    }

    public record EnvView(String containerName, String imageTag, String imageId, String javaVersion,
                          JsonNode jvmArgs, JsonNode gc, String capturedAt) {
    }

    public record CheckView(String checkKey, String expected, String actual, String verdict,
                            String checkedAt) {
    }

    public record OperationView(String operationId, String runId, String targetKind, String targetName,
                                String action, String status, Integer ttlSeconds, String requestedAt,
                                String appliedAt, String restoredAt, String lastError,
                                String operator, JsonNode beforeSummary, JsonNode afterSummary,
                                boolean idempotentReplay) {
    }

    /**
     * B2-04a: operator closure of an UNKNOWN operation. Only {@code RESTORED} and
     * {@code FAILED} are accepted; {@code note} is optional (max 512 chars, no
     * control characters) and becomes {@code last_error} when present.
     */
    public record OperationCloseRequest(String status, String note) {
    }

    /** B2-04a: one {@code lab_fault_event} row on the run timeline. */
    public record FaultEventView(String operationId, String source, String target, String event,
                                 String occurredAt, JsonNode detail) {
    }

    public record OperationPageView(List<OperationView> items, long total, int limit, int offset) {
    }

    public record RunPageView(List<RunView> items, long total, int limit, int offset) {
    }

    public record RunDetailView(RunView run, List<StepView> steps, List<EnvView> env,
                                List<ParamView> params, List<CheckView> checks,
                                List<FaultEventView> faults) {

        public RunDetailView {
            faults = faults == null ? List.of() : faults;
        }

        /** Compatibility constructor for callers that predate B2-04a. */
        public RunDetailView(RunView run, List<StepView> steps, List<EnvView> env,
                             List<ParamView> params, List<CheckView> checks) {
            this(run, steps, env, params, checks, List.of());
        }
    }

    public record LedgerStatusView(String state, String schemaVersion, long pendingStash,
                                   String lastWriteAt, String lastError, String mode) {
    }

    public record LedgerRecoveryView(List<RunView> unknownRuns, List<OperationView> unconfirmedOperations,
                                     List<String> cleanupSuggestions) {
    }
}
