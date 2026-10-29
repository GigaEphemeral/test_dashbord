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
 * {@code revision}. B4-02 restores the prior normalized config from the same
 * row.</p>
 *
 * <p>B4-02 transitions (V5): {@link #markApplying} (rebuild in flight),
 * {@link #markApplied} (applied image tag/id + stable period),
 * {@link #markFailed} (failing check + restore outcome, stable period set as
 * well, design §10.7), {@link #markDegraded} (the automatic restore itself
 * failed) and {@link #markRolledBack} (config/previous swapped so a repeated
 * rollback toggles). Every write is one statement, so a crash can never leave
 * half a transition.</p>
 */
public class InstanceConfigRepository {

    private static final String COLUMNS = "instance, config, previous_config, status, revision, "
            + "updated_at, applied_at, last_error, stable_until, applied_image_tag, "
            + "applied_image_id, last_apply_at, previous_image_tag, previous_image_id";

    private static final RowMapper<StoredInstanceConfig> MAPPER = (rs, rowNum) -> new StoredInstanceConfig(
            rs.getString("instance"),
            rs.getString("config"),
            rs.getString("previous_config"),
            InstanceConfigStatus.valueOf(rs.getString("status")),
            rs.getInt("revision"),
            instant(rs, "updated_at"),
            instant(rs, "applied_at"),
            rs.getString("last_error"),
            instant(rs, "stable_until"),
            rs.getString("applied_image_tag"),
            rs.getString("applied_image_id"),
            instant(rs, "last_apply_at"),
            rs.getString("previous_image_tag"),
            rs.getString("previous_image_id"));

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
     * application marker. The B4-02 image/stable columns keep describing the
     * running container: saving a draft never rebuilds anything.
     */
    public StoredInstanceConfig upsertDraft(String instance, String configJson, Instant now) {
        jdbc.update("INSERT INTO lab_instance_config (" + COLUMNS + ") "
                        + "VALUES (?, ?, NULL, ?, 1, ?, NULL, NULL, NULL, NULL, NULL, NULL, NULL, NULL) "
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

    /** Rebuild started: the stored config is no longer the running one. */
    public int markApplying(String instance, Instant now) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = NULL, "
                        + "updated_at = ?, last_error = NULL WHERE instance = ?",
                InstanceConfigStatus.APPLYING.name(), timestamp(now), instance);
    }

    /** B4-02 apply/rollback success; the draft becomes the effective configuration. */
    public int markApplied(String instance, Instant appliedAt) {
        return markApplied(instance, null, null, null, null, appliedAt, null);
    }

    /**
     * B4-02 apply/rollback success with image identity and stable period.
     * {@code previousImageTag/Id} are the image that was running before this
     * rebuild (the rollback target recorded next to {@code previous_config}).
     */
    public int markApplied(String instance, String appliedImageTag, String appliedImageId,
                           String previousImageTag, String previousImageId, Instant appliedAt,
                           Instant stableUntil) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = ?, "
                        + "last_apply_at = ?, stable_until = ?, applied_image_tag = ?, "
                        + "applied_image_id = ?, previous_image_tag = ?, previous_image_id = ?, "
                        + "updated_at = ?, last_error = NULL WHERE instance = ?",
                InstanceConfigStatus.APPLIED.name(), timestamp(appliedAt), timestamp(appliedAt),
                timestamp(stableUntil), truncate(appliedImageTag, 128),
                truncate(appliedImageId, 255), truncate(previousImageTag, 128),
                truncate(previousImageId, 255), timestamp(appliedAt), instance);
    }

    /** B4-02 apply failure; the message is capped to the column width. */
    public int markFailed(String instance, String error, Instant now) {
        return markFailed(instance, error, now, null, null, null);
    }

    /**
     * B4-02 failure with the restored image identity and the stable period
     * (失败后进入稳定期). {@code restored*} describe the container running now
     * when the automatic restore succeeded; they stay {@code null} when the
     * restore itself failed and the caller records {@link #markDegraded}.
     */
    public int markFailed(String instance, String error, Instant now, Instant stableUntil,
                          String restoredImageTag, String restoredImageId) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = NULL, "
                        + "last_apply_at = ?, stable_until = ?, applied_image_tag = ?, "
                        + "applied_image_id = ?, updated_at = ?, last_error = ? WHERE instance = ?",
                InstanceConfigStatus.FAILED.name(), timestamp(now), timestamp(stableUntil),
                truncate(restoredImageTag, 128), truncate(restoredImageId, 255),
                timestamp(now), truncate(error), instance);
    }

    /**
     * B4-02 rollback-of-the-rollback failure: the new container is gone and the
     * previous one could not be restored. The instance must not enter a formal
     * benchmark (design §10.7), so the status is DEGRADED and the console shows
     * an operator action instead of a plain failure.
     */
    public int markDegraded(String instance, String error, Instant now, Instant stableUntil) {
        return jdbc.update("UPDATE lab_instance_config SET status = ?, applied_at = NULL, "
                        + "last_apply_at = ?, stable_until = ?, updated_at = ?, last_error = ? "
                        + "WHERE instance = ?",
                InstanceConfigStatus.DEGRADED.name(), timestamp(now), timestamp(stableUntil),
                timestamp(now), truncate(error), instance);
    }

    /**
     * Rollback success: the previous config is now the effective one and the
     * config just replaced becomes the next rollback target, so repeated
     * rollbacks toggle instead of needing another save. The JSON values are
     * written explicitly (MySQL evaluates assignments left to right, so an
     * in-place swap is not expressible in one statement).
     */
    public int markRolledBack(String instance, String configJson, String previousConfigJson,
                              String appliedImageTag, String appliedImageId,
                              String previousImageTag, String previousImageId, Instant appliedAt,
                              Instant stableUntil) {
        return jdbc.update("UPDATE lab_instance_config SET config = ?, previous_config = ?, "
                        + "status = ?, applied_at = ?, last_apply_at = ?, stable_until = ?, "
                        + "applied_image_tag = ?, applied_image_id = ?, previous_image_tag = ?, "
                        + "previous_image_id = ?, revision = revision + 1, updated_at = ?, "
                        + "last_error = NULL WHERE instance = ?",
                configJson, previousConfigJson, InstanceConfigStatus.ROLLED_BACK.name(),
                timestamp(appliedAt), timestamp(appliedAt), timestamp(stableUntil),
                truncate(appliedImageTag, 128), truncate(appliedImageId, 255),
                truncate(previousImageTag, 128), truncate(previousImageId, 255),
                timestamp(appliedAt), instance);
    }

    private static String truncate(String error) {
        return truncate(error, 1024);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return null;
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    private static Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(java.sql.ResultSet rs, String column) throws java.sql.SQLException {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
