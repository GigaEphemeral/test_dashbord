package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.List;
import java.util.Map;

/** REST payloads for the ledger API. All instants are ISO-8601 UTC strings. */
public final class LedgerViews {

    private LedgerViews() {
    }

    public record RunCreateRequest(String scenario, Map<String, Object> params) {
    }

    public record RunCreateView(String runId, String status, String createdAt) {
    }

    public record RunView(String runId, String scenario, String status, String sourceCommit,
                          String operator, String createdAt, String updatedAt,
                          String startedAt, String endedAt) {
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

    public record OperationPageView(List<OperationView> items, long total, int limit, int offset) {
    }

    public record RunPageView(List<RunView> items, long total, int limit, int offset) {
    }

    public record RunDetailView(RunView run, List<StepView> steps, List<EnvView> env,
                                List<ParamView> params, List<CheckView> checks) {
    }

    public record LedgerStatusView(String state, String schemaVersion, long pendingStash,
                                   String lastWriteAt, String lastError, String mode) {
    }

    public record LedgerRecoveryView(List<RunView> unknownRuns, List<OperationView> unconfirmedOperations,
                                     List<String> cleanupSuggestions) {
    }
}
