package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.InstanceConfigStatus;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B4-01 V4 migration + real MySQL round-trips for {@code lab_instance_config}.
 * Reuses the shared LedgerTestDatabase container (no extra Docker cost).
 */
class InstanceConfigRepositoryIT {

    private InstanceConfigRepository repository;
    private JdbcTemplate jdbc;

    @BeforeEach
    void setUp() {
        jdbc = LedgerTestDatabase.jdbc();
        jdbc.update("DELETE FROM lab_instance_config");
        repository = new InstanceConfigRepository(jdbc);
    }

    @Test
    void v4AndV5CreateTheExpectedColumnsAndNativeJsonTypes() {
        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'lab_ledger' AND table_name = 'lab_instance_config' "
                        + "ORDER BY ordinal_position", String.class);
        assertThat(columns).containsExactly("instance", "config", "previous_config", "status",
                "revision", "updated_at", "applied_at", "last_error", "stable_until",
                "applied_image_tag", "applied_image_id", "last_apply_at", "previous_image_tag",
                "previous_image_id");

        List<String> jsonColumns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns "
                        + "WHERE table_schema = 'lab_ledger' AND table_name = 'lab_instance_config' "
                        + "AND data_type = 'json' ORDER BY column_name", String.class);
        assertThat(jsonColumns).containsExactly("config", "previous_config");

        // V5 widened the status column for DEGRADED.
        assertThat(jdbc.queryForObject(
                "SELECT character_maximum_length FROM information_schema.columns "
                        + "WHERE table_schema = 'lab_ledger' AND table_name = 'lab_instance_config' "
                        + "AND column_name = 'status'", Integer.class)).isEqualTo(32);
    }

    @Test
    void secondUpsertKeepsThePreviousConfigAndIncrementsRevision() {
        Instant first = Instant.parse("2026-10-30T10:00:00Z");
        Instant second = first.plusSeconds(60);
        StoredInstanceConfig one = repository.upsertDraft("app-1",
                "{\"jdkVersion\":21,\"xmxMb\":256}", first);

        assertThat(one.revision()).isEqualTo(1);
        assertThat(one.previousConfigJson()).isNull();
        assertThat(one.status()).isEqualTo(InstanceConfigStatus.DRAFT);
        assertThat(one.updatedAt()).isEqualTo(first);

        StoredInstanceConfig two = repository.upsertDraft("app-1",
                "{\"jdkVersion\":21,\"xmxMb\":384}", second);

        assertThat(two.revision()).isEqualTo(2);
        assertThat(two.configJson()).contains("384");
        assertThat(two.previousConfigJson()).contains("256");
        assertThat(repository.find("app-1")).contains(two);
    }

    @Test
    void markAppliedAndFailedDriveTheStatusColumns() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        repository.upsertDraft("app-1", "{\"jdkVersion\":21}", now);

        assertThat(repository.markApplied("app-1", now.plusSeconds(30))).isEqualTo(1);
        StoredInstanceConfig applied = repository.find("app-1").orElseThrow();
        assertThat(applied.status()).isEqualTo(InstanceConfigStatus.APPLIED);
        assertThat(applied.appliedAt()).isEqualTo(now.plusSeconds(30));
        assertThat(applied.lastError()).isNull();

        String tooLong = "x".repeat(1500);
        assertThat(repository.markFailed("app-1", tooLong, now.plusSeconds(60))).isEqualTo(1);
        StoredInstanceConfig failed = repository.find("app-1").orElseThrow();
        assertThat(failed.status()).isEqualTo(InstanceConfigStatus.FAILED);
        assertThat(failed.appliedAt()).isNull();
        assertThat(failed.lastError()).hasSize(1024);
    }

    @Test
    void listReturnsEveryInstanceInOrder() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        repository.upsertDraft("app-2", "{\"jdkVersion\":21}", now);
        repository.upsertDraft("app-1", "{\"jdkVersion\":21}", now);

        assertThat(repository.list()).extracting(StoredInstanceConfig::instance)
                .containsExactly("app-1", "app-2");
    }

    @Test
    void v5RoundTripsTheApplyOutcomeAndRollsBackBySwappingConfigs() {
        Instant now = Instant.parse("2026-10-30T10:00:00Z");
        repository.upsertDraft("app-1", "{\"jdkVersion\":21,\"xmxMb\":384}", now);
        repository.upsertDraft("app-1", "{\"jdkVersion\":21,\"xmxMb\":256}", now.plusSeconds(10));

        assertThat(repository.markApplying("app-1", now.plusSeconds(20))).isEqualTo(1);
        assertThat(repository.find("app-1").orElseThrow().status())
                .isEqualTo(InstanceConfigStatus.APPLYING);

        Instant appliedAt = now.plusSeconds(120);
        Instant stableUntil = appliedAt.plusSeconds(60);
        assertThat(repository.markApplied("app-1", "java-learn-lab/seckill-app:jdk21",
                "sha256:new", "java-learn-lab/seckill-app:jdk21", "sha256:old", appliedAt,
                stableUntil)).isEqualTo(1);
        StoredInstanceConfig applied = repository.find("app-1").orElseThrow();
        assertThat(applied.status()).isEqualTo(InstanceConfigStatus.APPLIED);
        assertThat(applied.appliedImageTag()).isEqualTo("java-learn-lab/seckill-app:jdk21");
        assertThat(applied.appliedImageId()).isEqualTo("sha256:new");
        assertThat(applied.previousImageId()).isEqualTo("sha256:old");
        assertThat(applied.lastApplyAt()).isEqualTo(appliedAt);
        assertThat(applied.stableUntil()).isEqualTo(stableUntil);
        assertThat(applied.appliedAt()).isEqualTo(appliedAt);

        String current = applied.configJson();
        String previous = applied.previousConfigJson();
        Instant rolledAt = now.plusSeconds(200);
        assertThat(repository.markRolledBack("app-1", previous, current,
                "java-learn-lab/seckill-app:jdk21", "sha256:older",
                "java-learn-lab/seckill-app:jdk21", "sha256:new", rolledAt,
                rolledAt.plusSeconds(60))).isEqualTo(1);
        StoredInstanceConfig rolledBack = repository.find("app-1").orElseThrow();
        assertThat(rolledBack.configJson()).isEqualTo(previous);
        assertThat(rolledBack.previousConfigJson()).isEqualTo(current);
        assertThat(rolledBack.revision()).isEqualTo(applied.revision() + 1);
        assertThat(rolledBack.status()).isEqualTo(InstanceConfigStatus.ROLLED_BACK);

        assertThat(repository.markFailed("app-1", "check pool-probe failed", rolledAt,
                rolledAt.plusSeconds(60), "java-learn-lab/seckill-app:jdk21", "sha256:older"))
                .isEqualTo(1);
        StoredInstanceConfig failed = repository.find("app-1").orElseThrow();
        assertThat(failed.status()).isEqualTo(InstanceConfigStatus.FAILED);
        assertThat(failed.stableUntil()).isEqualTo(rolledAt.plusSeconds(60));
        assertThat(failed.appliedAt()).isNull();
        assertThat(failed.lastError()).contains("pool-probe");

        assertThat(repository.markDegraded("app-1", "restore failed", rolledAt,
                rolledAt.plusSeconds(60))).isEqualTo(1);
        assertThat(repository.find("app-1").orElseThrow().status())
                .isEqualTo(InstanceConfigStatus.DEGRADED);
    }
}
