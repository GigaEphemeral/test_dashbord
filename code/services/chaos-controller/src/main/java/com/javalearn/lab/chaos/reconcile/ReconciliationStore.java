package com.javalearn.lab.chaos.reconcile;

import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Persistence for B3-04 in the existing {@code lab_run_check} table.
 *
 * <p>Mechanism choice: baseline and reconciliation results are separate rows
 * with stable keys, not one JSON blob in {@code lab_run_param}. This keeps
 * {@code GET /lab/runs/{id}} readable (the generic checks table already renders
 * expected/actual/verdict/checkedAt) and avoids inventing a second payload
 * contract.</p>
 *
 * <ul>
 *   <li>{@code baseline.orders}, {@code baseline.stock}, {@code baseline.mq}:
 *       captured once after READY→RUNNING, verdict {@code BASELINE};</li>
 *   <li>{@code reconcile.<item>} plus a final {@code reconcile.summary} row:
 *       one manual reconcile, all sharing one {@code checked_at}.</li>
 * </ul>
 *
 * <p>{@code expected}/{@code actual} are VARCHAR(1024); callers pass compact
 * JSON and the store clips as a last-resort guard.</p>
 */
public class ReconciliationStore {

    public static final String BASELINE_ORDERS = "baseline.orders";
    public static final String BASELINE_STOCK = "baseline.stock";
    public static final String BASELINE_MQ = "baseline.mq";
    public static final String RECONCILE_PREFIX = "reconcile.";
    public static final String RECONCILE_SUMMARY = "reconcile.summary";
    public static final int MAX_VALUE_LENGTH = 1024;
    public static final int MAX_KEY_LENGTH = 128;
    public static final int MAX_VERDICT_LENGTH = 16;

    private static final List<String> BASELINE_KEYS = List.of(
            BASELINE_ORDERS, BASELINE_STOCK, BASELINE_MQ);

    private final JdbcTemplate jdbc;

    public ReconciliationStore(JdbcTemplate ledgerJdbcTemplate) {
        this.jdbc = ledgerJdbcTemplate;
    }

    public boolean runExists(String runId) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM lab_run WHERE run_id = ?",
                Long.class, runId);
        return count != null && count > 0;
    }

    public void insertCheck(String runId, String checkKey, String expected, String actual,
                            String verdict, Instant checkedAt) {
        jdbc.update("INSERT INTO lab_run_check "
                        + "(run_id, check_key, expected, actual, verdict, checked_at) "
                        + "VALUES (?,?,?,?,?,?)",
                runId, clip(checkKey, MAX_KEY_LENGTH), clip(expected, MAX_VALUE_LENGTH),
                clip(actual, MAX_VALUE_LENGTH), clip(verdict, MAX_VERDICT_LENGTH),
                Timestamp.from(checkedAt));
    }

    /** Latest baseline row per key; empty when capture failed or never ran. */
    public Map<String, PersistedRow> findBaselineRows(String runId) {
        Map<String, PersistedRow> rows = new LinkedHashMap<>();
        jdbc.query("SELECT check_key, expected, actual, verdict, checked_at FROM lab_run_check "
                        + "WHERE run_id = ? AND check_key IN (?,?,?) ORDER BY id ASC",
                rs -> {
                    rows.put(rs.getString("check_key"), row(rs));
                },
                runId, BASELINE_KEYS.get(0), BASELINE_KEYS.get(1), BASELINE_KEYS.get(2));
        return rows;
    }

    public Optional<PersistedRow> findLatestSummary(String runId) {
        List<PersistedRow> rows = jdbc.query(
                "SELECT check_key, expected, actual, verdict, checked_at FROM lab_run_check "
                        + "WHERE run_id = ? AND check_key = ? "
                        + "ORDER BY checked_at DESC, id DESC LIMIT 1",
                (rs, rowNum) -> row(rs), runId, RECONCILE_SUMMARY);
        return rows.stream().findFirst();
    }

    public List<PersistedRow> findItemsAt(String runId, Instant checkedAt) {
        return jdbc.query("SELECT check_key, expected, actual, verdict, checked_at "
                        + "FROM lab_run_check WHERE run_id = ? AND check_key LIKE ? "
                        + "AND check_key <> ? AND checked_at = ? ORDER BY id ASC",
                (rs, rowNum) -> row(rs), runId, RECONCILE_PREFIX + "%", RECONCILE_SUMMARY,
                Timestamp.from(checkedAt));
    }

    private static PersistedRow row(ResultSet rs) throws SQLException {
        Timestamp checkedAt = rs.getTimestamp("checked_at");
        return new PersistedRow(
                rs.getString("check_key"),
                rs.getString("expected"),
                rs.getString("actual"),
                rs.getString("verdict"),
                checkedAt == null ? null : checkedAt.toInstant());
    }

    private static String clip(String value, int max) {
        if (value == null || value.length() <= max) {
            return value;
        }
        return value.substring(0, max);
    }

    public record PersistedRow(String checkKey, String expected, String actual, String verdict,
                               Instant checkedAt) {
    }
}
