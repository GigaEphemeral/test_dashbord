package com.javalearn.lab.chaos.ledger;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * JdbcTemplate access to the {@code lab_ledger} schema. No JPA, no second
 * datastore: the ledger is the same MySQL instance as the business database but
 * a physically separate schema, so retention can cascade without touching
 * {@code seckill}.
 *
 * <p>All timestamps are UTC {@code DATETIME(3)}; callers pass {@link Instant}
 * and this class converts at the JDBC boundary. JSON columns travel as strings
 * so the serialization policy (and the credential guard) stays in one place.</p>
 */
public class RunLedgerRepository {

    private static final String RUN_COLUMNS = "run_id, scenario, status, source_commit, operator, "
            + "created_at, updated_at, started_at, ended_at";

    private static final RowMapper<RunRecord> RUN_MAPPER = (rs, rowNum) -> new RunRecord(
            rs.getString("run_id"),
            rs.getString("scenario"),
            RunStatus.valueOf(rs.getString("status")),
            rs.getString("source_commit"),
            rs.getString("operator"),
            instant(rs, "created_at"),
            instant(rs, "updated_at"),
            instant(rs, "started_at"),
            instant(rs, "ended_at"));

    private final JdbcTemplate jdbc;

    public RunLedgerRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ runs

    public void insertRun(RunRecord run) {
        jdbc.update("INSERT INTO lab_run (" + RUN_COLUMNS + ") VALUES (?,?,?,?,?,?,?,?,?)",
                run.runId(), run.scenario(), run.status().name(), run.sourceCommit(), run.operator(),
                timestamp(run.createdAt()), timestamp(run.updatedAt()),
                timestamp(run.startedAt()), timestamp(run.endedAt()));
    }

    public Optional<RunRecord> findRun(String runId) {
        List<RunRecord> rows = jdbc.query("SELECT " + RUN_COLUMNS + " FROM lab_run WHERE run_id = ?",
                RUN_MAPPER, runId);
        return rows.stream().findFirst();
    }

    public List<RunRecord> listRuns(String scenario, RunStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + RUN_COLUMNS + " FROM lab_run WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (scenario != null) {
            sql.append(" AND scenario = ?");
            args.add(scenario);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, run_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), RUN_MAPPER, args.toArray());
    }

    public long countRuns(String scenario, RunStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM lab_run WHERE 1=1");
        List<Object> args = new ArrayList<>();
        if (scenario != null) {
            sql.append(" AND scenario = ?");
            args.add(scenario);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public List<RunRecord> findRunsByStatuses(Collection<RunStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        String placeholders = placeholders(statuses.size(), args, statuses.stream().map(s -> s.name()).toList());
        return jdbc.query("SELECT " + RUN_COLUMNS + " FROM lab_run WHERE status IN (" + placeholders
                + ") ORDER BY created_at ASC", RUN_MAPPER, args.toArray());
    }

    /** Highest numeric suffix already used with this {@code run-yyyyMMdd-} prefix. */
    public int maxRunSequence(String prefix) {
        Integer max = jdbc.queryForObject(
                "SELECT COALESCE(MAX(CAST(SUBSTRING(run_id, ?) AS UNSIGNED)), 0) FROM lab_run "
                        + "WHERE run_id LIKE ?",
                Integer.class, prefix.length() + 1, prefix + "%");
        return max == null ? 0 : max;
    }

    /**
     * Compare-and-set transition. Returns 0 when the stored status is no longer
     * {@code expected} (lost race), which callers surface as a conflict.
     */
    public int updateRunStatus(String runId, RunStatus expected, RunStatus next,
                               Instant at, Instant startedAt, Instant endedAt) {
        return jdbc.update("UPDATE lab_run SET status = ?, updated_at = ?, "
                        + "started_at = COALESCE(?, started_at), ended_at = COALESCE(?, ended_at) "
                        + "WHERE run_id = ? AND status = ?",
                next.name(), timestamp(at), timestamp(startedAt), timestamp(endedAt),
                runId, expected.name());
    }

    /** Recovery-only update: UNKNOWN is written here, never through a transition. */
    public int markRunUnknown(String runId, RunStatus expected, Instant at) {
        return jdbc.update("UPDATE lab_run SET status = ?, updated_at = ? WHERE run_id = ? AND status = ?",
                RunStatus.UNKNOWN.name(), timestamp(at), runId, expected.name());
    }

    /** Deletes runs outside the newest {@code keep}; child rows cascade. */
    public int deleteRunsBeyondKeep(int keep) {
        return jdbc.update("DELETE FROM lab_run WHERE run_id NOT IN ("
                + "SELECT run_id FROM (SELECT run_id FROM lab_run ORDER BY created_at DESC, run_id DESC LIMIT ?) kept)",
                keep);
    }

    // ------------------------------------------------------------- run steps

    public void insertStep(RunStepRecord step) {
        jdbc.update("INSERT INTO lab_run_step (run_id, step_key, from_state, to_state, occurred_at, dwell_ms, detail) "
                        + "VALUES (?,?,?,?,?,?,?)",
                step.runId(), step.stepKey(),
                step.fromStatus() == null ? null : step.fromStatus().name(),
                step.toStatus() == null ? null : step.toStatus().name(),
                timestamp(step.occurredAt()),
                step.dwellMs(),
                step.detailJson());
    }

    public List<RunStepRecord> findSteps(String runId) {
        return jdbc.query("SELECT run_id, step_key, from_state, to_state, occurred_at, dwell_ms, detail "
                        + "FROM lab_run_step WHERE run_id = ? ORDER BY occurred_at ASC, id ASC",
                (rs, rowNum) -> new RunStepRecord(
                        rs.getString("run_id"),
                        rs.getString("step_key"),
                        runStatus(rs.getString("from_state")),
                        runStatus(rs.getString("to_state")),
                        instant(rs, "occurred_at"),
                        nullableLong(rs, "dwell_ms"),
                        rs.getString("detail")),
                runId);
    }

    // ------------------------------------------------------------ run params

    public void insertParam(String runId, String groupName, String payloadJson, Instant savedAt) {
        jdbc.update("INSERT INTO lab_run_param (run_id, group_name, payload, saved_at) VALUES (?,?,?,?)",
                runId, groupName, payloadJson, timestamp(savedAt));
    }

    public List<RunParamRow> findParams(String runId) {
        return jdbc.query("SELECT group_name, payload, saved_at FROM lab_run_param "
                        + "WHERE run_id = ? ORDER BY saved_at ASC, id ASC",
                (rs, rowNum) -> new RunParamRow(
                        rs.getString("group_name"),
                        rs.getString("payload"),
                        instant(rs, "saved_at")),
                runId);
    }

    // --------------------------------------------------------------- run env

    public void insertEnv(String runId, String containerName, String imageTag, String imageId,
                          String javaVersion, String jvmArgsJson, String gcJson, Instant capturedAt) {
        jdbc.update("INSERT INTO lab_run_env (run_id, container_name, image_tag, image_id, java_version, "
                        + "jvm_args, gc, captured_at) VALUES (?,?,?,?,?,?,?,?)",
                runId, containerName, imageTag, imageId, javaVersion, jvmArgsJson, gcJson,
                timestamp(capturedAt));
    }

    public List<RunEnvRow> findEnvs(String runId) {
        return jdbc.query("SELECT container_name, image_tag, image_id, java_version, jvm_args, gc, captured_at "
                        + "FROM lab_run_env WHERE run_id = ? ORDER BY captured_at ASC, id ASC",
                (rs, rowNum) -> new RunEnvRow(
                        rs.getString("container_name"),
                        rs.getString("image_tag"),
                        rs.getString("image_id"),
                        rs.getString("java_version"),
                        rs.getString("jvm_args"),
                        rs.getString("gc"),
                        instant(rs, "captured_at")),
                runId);
    }

    // ------------------------------------------------------------ operations

    public void insertOperation(OperationRecord op) {
        insertOperation(op, null);
    }

    /**
     * Inserts the operation row. {@code idempotencyKey} is null for callers that
     * did not send the header (MySQL ignores NULL parts of the composite unique
     * key, so those rows are unconstrained).
     */
    public void insertOperation(OperationRecord op, String idempotencyKey) {
        jdbc.update("INSERT INTO lab_operation (" + OPERATION_COLUMNS + ", idempotency_key) "
                        + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)",
                op.operationId(), op.runId(), op.targetKind(), op.targetName(), op.action(),
                op.status().name(), op.ttlSeconds(), timestamp(op.requestedAt()),
                timestamp(op.appliedAt()), timestamp(op.restoredAt()), op.lastError(),
                op.operator(), op.beforeSummaryJson(), op.afterSummaryJson(), idempotencyKey);
    }

    public Optional<OperationRecord> findOperation(String operationId) {
        return jdbc.query("SELECT " + OPERATION_COLUMNS + " FROM lab_operation WHERE operation_id = ?",
                OPERATION_MAPPER, operationId).stream().findFirst();
    }

    /**
     * Idempotency lookup. The unique key is (key, action, target_kind,
     * target_name): the same key used for a different action or target is
     * simply not found here and becomes a new operation.
     */
    public Optional<OperationRecord> findOperationByIdempotencyKey(String idempotencyKey, String action,
                                                                  String targetKind, String targetName) {
        return jdbc.query("SELECT " + OPERATION_COLUMNS + " FROM lab_operation "
                        + "WHERE idempotency_key = ? AND action = ? AND target_kind = ? AND target_name = ?",
                OPERATION_MAPPER, idempotencyKey, action, targetKind, targetName).stream().findFirst();
    }

    /** Frees an expired key so the window can restart with a new operation. */
    public int releaseIdempotencyKey(String operationId, String idempotencyKey) {
        return jdbc.update("UPDATE lab_operation SET idempotency_key = NULL "
                + "WHERE operation_id = ? AND idempotency_key = ?", operationId, idempotencyKey);
    }

    public List<OperationRecord> findOperations(String runId) {
        return jdbc.query("SELECT " + OPERATION_COLUMNS + " FROM lab_operation "
                        + "WHERE run_id = ? ORDER BY requested_at ASC",
                OPERATION_MAPPER, runId);
    }

    public List<OperationRecord> listOperations(String runId, OperationStatus status, int limit, int offset) {
        StringBuilder sql = new StringBuilder("SELECT " + OPERATION_COLUMNS + " FROM lab_operation WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendOperationFilters(sql, args, runId, status);
        sql.append(" ORDER BY requested_at DESC, operation_id DESC LIMIT ? OFFSET ?");
        args.add(limit);
        args.add(offset);
        return jdbc.query(sql.toString(), OPERATION_MAPPER, args.toArray());
    }

    public long countOperations(String runId, OperationStatus status) {
        StringBuilder sql = new StringBuilder("SELECT COUNT(*) FROM lab_operation WHERE 1=1");
        List<Object> args = new ArrayList<>();
        appendOperationFilters(sql, args, runId, status);
        Long count = jdbc.queryForObject(sql.toString(), Long.class, args.toArray());
        return count == null ? 0L : count;
    }

    public List<OperationRecord> findOperationsByStatuses(Collection<OperationStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return List.of();
        }
        List<Object> args = new ArrayList<>();
        String placeholders = placeholders(statuses.size(), args, statuses.stream().map(s -> s.name()).toList());
        return jdbc.query("SELECT " + OPERATION_COLUMNS + " FROM lab_operation "
                        + "WHERE status IN (" + placeholders + ") ORDER BY requested_at ASC",
                OPERATION_MAPPER, args.toArray());
    }

    /**
     * Settles an operation. Only REQUESTED / PENDING_CONFIRMATION are updated, so
     * a slow worker can never overwrite a terminal state (or the UNKNOWN written
     * by restart recovery). Returns 0 when the row already moved on.
     */
    public int completeOperation(String operationId, OperationStatus status, Instant appliedAt,
                                 Instant restoredAt, String lastError, String afterSummaryJson) {
        return jdbc.update("UPDATE lab_operation SET status = ?, last_error = ?, "
                        + "applied_at = COALESCE(?, applied_at), restored_at = COALESCE(?, restored_at), "
                        + "after_summary = COALESCE(?, after_summary) "
                        + "WHERE operation_id = ? AND status IN (?,?)",
                status.name(), truncate(lastError, 1024), timestamp(appliedAt), timestamp(restoredAt),
                afterSummaryJson, operationId,
                OperationStatus.REQUESTED.name(), OperationStatus.PENDING_CONFIRMATION.name());
    }

    /** REQUESTED → PENDING_CONFIRMATION after the synchronous wait expired. */
    public int markOperationPending(String operationId, String lastError) {
        return jdbc.update("UPDATE lab_operation SET status = ?, last_error = ? "
                        + "WHERE operation_id = ? AND status = ?",
                OperationStatus.PENDING_CONFIRMATION.name(), truncate(lastError, 1024), operationId,
                OperationStatus.REQUESTED.name());
    }

    private static void appendOperationFilters(StringBuilder sql, List<Object> args, String runId,
                                               OperationStatus status) {
        if (runId != null) {
            sql.append(" AND run_id = ?");
            args.add(runId);
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
    }

    /**
     * Restart recovery closure: an operation the controller cannot prove either
     * way becomes UNKNOWN. Only REQUESTED / PENDING_CONFIRMATION are touched, so
     * re-running is idempotent.
     */
    public int markOperationUnknown(String operationId, String error) {
        return jdbc.update("UPDATE lab_operation SET status = ?, last_error = ? "
                        + "WHERE operation_id = ? AND status IN (?,?)",
                OperationStatus.UNKNOWN.name(), truncate(error, 1024), operationId,
                OperationStatus.REQUESTED.name(), OperationStatus.PENDING_CONFIRMATION.name());
    }

    // --------------------------------------------------------- fault events

    public void insertFaultEvent(String runId, String operationId, String source, String target,
                                 String event, Instant occurredAt, String detailJson) {
        jdbc.update("INSERT INTO lab_fault_event (run_id, operation_id, source, target, `event`, occurred_at, detail) "
                        + "VALUES (?,?,?,?,?,?,?)",
                runId, operationId, source, target, event, timestamp(occurredAt), detailJson);
    }

    public List<FaultEventRow> findFaultEvents(String runId) {
        return jdbc.query("SELECT operation_id, source, target, `event`, occurred_at, detail "
                        + "FROM lab_fault_event WHERE run_id = ? ORDER BY occurred_at ASC, id ASC",
                (rs, rowNum) -> new FaultEventRow(
                        rs.getString("operation_id"),
                        rs.getString("source"),
                        rs.getString("target"),
                        rs.getString("event"),
                        instant(rs, "occurred_at"),
                        rs.getString("detail")),
                runId);
    }

    // -------------------------------------------------------- metric windows

    public void insertMetricWindow(String runId, String phase, String metricKey, String promql,
                                   Instant windowStart, Instant windowEnd, String statsJson,
                                   String dataQuality) {
        jdbc.update("INSERT INTO lab_metric_window (run_id, phase, metric_key, promql, window_start, "
                        + "window_end, stats, data_quality) VALUES (?,?,?,?,?,?,?,?)",
                runId, phase, metricKey, promql, timestamp(windowStart), timestamp(windowEnd),
                statsJson, dataQuality);
    }

    // ----------------------------------------------------------- run checks

    public void insertCheck(String runId, String checkKey, String expected, String actual,
                            String verdict, Instant checkedAt) {
        jdbc.update("INSERT INTO lab_run_check (run_id, check_key, expected, actual, verdict, checked_at) "
                        + "VALUES (?,?,?,?,?,?)",
                runId, checkKey, truncate(expected, 1024), truncate(actual, 1024), verdict,
                timestamp(checkedAt));
    }

    public List<RunCheckRow> findChecks(String runId) {
        return jdbc.query("SELECT check_key, expected, actual, verdict, checked_at FROM lab_run_check "
                        + "WHERE run_id = ? ORDER BY checked_at ASC, id ASC",
                (rs, rowNum) -> new RunCheckRow(
                        rs.getString("check_key"),
                        rs.getString("expected"),
                        rs.getString("actual"),
                        rs.getString("verdict"),
                        instant(rs, "checked_at")),
                runId);
    }

    // ----------------------------------------------------------- stash audit

    public void insertStashAudit(String kind, String payloadJson, Instant createdAt, Instant flushedAt) {
        jdbc.update("INSERT INTO lab_ledger_stash (kind, payload, created_at, flushed_at) VALUES (?,?,?,?)",
                kind, payloadJson, timestamp(createdAt), timestamp(flushedAt));
    }

    public List<StashAuditRow> findStashAudits() {
        return jdbc.query("SELECT kind, payload, created_at, flushed_at FROM lab_ledger_stash ORDER BY id ASC",
                (rs, rowNum) -> new StashAuditRow(
                        rs.getString("kind"),
                        rs.getString("payload"),
                        instant(rs, "created_at"),
                        instant(rs, "flushed_at")));
    }

    // ------------------------------------------------------------- env locks

    public void insertEnvLock(String lockKey, String runId, String holder,
                              Instant acquiredAt, Instant expiresAt) {
        jdbc.update("INSERT INTO lab_env_lock (lock_key, run_id, holder, acquired_at, expires_at) "
                        + "VALUES (?,?,?,?,?)",
                lockKey, runId, holder, timestamp(acquiredAt), timestamp(expiresAt));
    }

    public Optional<EnvLockRow> findEnvLock(String lockKey) {
        return jdbc.query("SELECT lock_key, run_id, holder, acquired_at, expires_at FROM lab_env_lock "
                        + "WHERE lock_key = ?",
                (rs, rowNum) -> new EnvLockRow(
                        rs.getString("lock_key"),
                        rs.getString("run_id"),
                        rs.getString("holder"),
                        instant(rs, "acquired_at"),
                        instant(rs, "expires_at")),
                lockKey).stream().findFirst();
    }

    public List<EnvLockRow> listEnvLocks() {
        return jdbc.query("SELECT lock_key, run_id, holder, acquired_at, expires_at FROM lab_env_lock "
                        + "ORDER BY acquired_at ASC",
                (rs, rowNum) -> new EnvLockRow(
                        rs.getString("lock_key"),
                        rs.getString("run_id"),
                        rs.getString("holder"),
                        instant(rs, "acquired_at"),
                        instant(rs, "expires_at")));
    }

    /**
     * Takes over a lock whose stored expiry has passed. Compare-and-set on the
     * observed expiry: a concurrent takeover or a renewal makes this return 0,
     * so two callers can never both believe they hold the lock.
     */
    public int takeOverEnvLock(String lockKey, Instant expectedExpiresAt, String runId, String holder,
                               Instant acquiredAt, Instant expiresAt) {
        return jdbc.update("UPDATE lab_env_lock SET run_id = ?, holder = ?, acquired_at = ?, expires_at = ? "
                        + "WHERE lock_key = ? AND expires_at = ?",
                runId, holder, timestamp(acquiredAt), timestamp(expiresAt), lockKey,
                timestamp(expectedExpiresAt));
    }

    /** Release by the holder only; another run's lock is never deleted. */
    public int releaseEnvLock(String lockKey, String runId) {
        return jdbc.update("DELETE FROM lab_env_lock WHERE lock_key = ? AND run_id = ?", lockKey, runId);
    }

    // ------------------------------------------------------------------ util

    private static final String OPERATION_COLUMNS = "operation_id, run_id, target_kind, target_name, action, "
            + "status, ttl_seconds, requested_at, applied_at, restored_at, last_error, `operator`, "
            + "before_summary, after_summary";

    private static final RowMapper<OperationRecord> OPERATION_MAPPER = (rs, rowNum) -> new OperationRecord(
            rs.getString("operation_id"),
            rs.getString("run_id"),
            rs.getString("target_kind"),
            rs.getString("target_name"),
            rs.getString("action"),
            OperationStatus.valueOf(rs.getString("status")),
            nullableInt(rs, "ttl_seconds"),
            instant(rs, "requested_at"),
            instant(rs, "applied_at"),
            instant(rs, "restored_at"),
            rs.getString("last_error"),
            rs.getString("operator"),
            rs.getString("before_summary"),
            rs.getString("after_summary"));

    private static String placeholders(int count, List<Object> args, List<String> values) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) {
                builder.append(',');
            }
            builder.append('?');
            args.add(values.get(i));
        }
        return builder.toString();
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(ResultSet rs, String column) throws SQLException {
        Timestamp value = rs.getTimestamp(column);
        return value == null ? null : value.toInstant();
    }

    private static RunStatus runStatus(String value) {
        return value == null ? null : RunStatus.valueOf(value);
    }

    private static Long nullableLong(ResultSet rs, String column) throws SQLException {
        long value = rs.getLong(column);
        return rs.wasNull() ? null : value;
    }

    private static Integer nullableInt(ResultSet rs, String column) throws SQLException {
        int value = rs.getInt(column);
        return rs.wasNull() ? null : value;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public record RunParamRow(String groupName, String payloadJson, Instant savedAt) {
    }

    public record RunEnvRow(String containerName, String imageTag, String imageId, String javaVersion,
                            String jvmArgsJson, String gcJson, Instant capturedAt) {
    }

    public record FaultEventRow(String operationId, String source, String target, String event,
                                Instant occurredAt, String detailJson) {
    }

    public record RunCheckRow(String checkKey, String expected, String actual, String verdict,
                              Instant checkedAt) {
    }

    public record StashAuditRow(String kind, String payloadJson, Instant createdAt, Instant flushedAt) {
    }

    public record EnvLockRow(String lockKey, String runId, String holder, Instant acquiredAt,
                             Instant expiresAt) {
    }
}
