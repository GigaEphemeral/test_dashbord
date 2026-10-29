package com.javalearn.lab.chaos.instance;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JdbcTemplate access to {@code lab_instance_config} in the ledger schema.
 * Timestamps are UTC {@code DATETIME(3)}; JSON is passed as text.
 *
 * <p>{@link #upsertDraft} is a single atomic statement: on the first save the
 * row starts at revision 1 with no previous config; every later save moves the
 * current config into {@code previous_config}, resets the status to
 * {@code DRAFT}, clears the applied/error columns and increments
 * {@code revision}. B4-02 can therefore restore the prior normalized config
 * from the same row.</p>
 */
public class InstanceConfigRepository {

    private static final String COLUMNS = "instance, config, previous_config, status, revision, "
            + "updated_at, applied_at, last_error";

    private static final RowMapper<StoredInstanceConfig> MAPPER = (rs, rowNum) -> new StoredInstanceConfig(
            rs.getString("instance"),
            rs.getString("config"),
            rs.getString("previous_config"),
            InstanceConfigStatus.valueOf(rs.getString("status")),
            rs.getInt("revision"),
            instant(rs, "updated_at"),
            instant(rs, "applied_at"),
            rs.getString("last_error"));

    private final JdbcTemplate jdbc;

    public InstanceConfigRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public Optional<StoredInstanceConfig> find(String instance) {
        List<StoredInstanceConfig> rows = jdbc.query(
                "SELECT " + COLUMNS + " FROM lab_instance_config WHERE instance = ?", MAPPER,
                instance);
        return rows.stream().findFirst();
    }

    public List<StoredInstanceConfig> list() {
        return jdbc.query("SELECT " + COLUMNS + " FROM lab_instance_config ORDER BY instance",
                MAPPER);
    }

    /**
     * Insert-or-update the draft. Returns the row after the write (revision
     * already incremented). The update branch keeps the previous config and
     * never touches {@code applied_at} history beyond clearing the pending
     * application marker.
     */
    public StoredInstanceConfig upsertDraft(String instance, String configJson, Instant now) {
        jdbc.update("INSERT INTO lab_instance_config (" + COLUMNS + ") "
                        + "VALUES (?, ?, NULL, ?, 1, ?, NULL, NULL) "
                        + "ON DUPLICATE KEY UPDATE "
                        + "previous_config = config, "
                        + "config = VALUES(config), "
                        + "status = VALUES(status), "
                        + "revision = revision + 1, "
                        + "updated_at = VALUES(updated_at), "
                        + "applied_at = NULL, "
                        + "last_error = NULL",
                instance, configJson, InstanceConfigStatus.DRAFT.name(), timestamp(now));
        return find(instance).orElseThrow(() -> new IllegalStateException(
                "instance config row vanished after upsert: " + instance));
    }

    /** B4-02 apply success; the draft becomes the effective configuration. */
    public int markApplied(String instance, Instant appliedAt) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = ?, "
                        + "updated_at = ?, last_error = NULL WHERE instance = ?",
                InstanceConfigStatus.APPLIED.name(), timestamp(appliedAt), timestamp(appliedAt),
                instance);
    }

    /** B4-02 apply failure; the message is capped to the column width. */
    public int markFailed(String instance, String error, Instant now) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = NULL, "
                        + "updated_at = ?, last_error = ? WHERE instance = ?",
                InstanceConfigStatus.FAILED.name(), timestamp(now), truncate(error), instance);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() <= 1024 ? error : error.substring(0, 1024);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
