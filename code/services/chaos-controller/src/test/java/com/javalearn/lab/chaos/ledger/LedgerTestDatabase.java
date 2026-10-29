package com.javalearn.lab.chaos.ledger;

import org.flywaydb.core.Flyway;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.MySQLContainer;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;

/**
 * One MySQL 8.4.4 container per surefire JVM, shared by the ledger integration
 * tests (schema, stash, recovery, retention). The ledger schema is migrated once
 * with the production Flyway location; each test cleans {@code lab_run} (all
 * children cascade) and the stash audit table.
 *
 * <p>Testcontainers containers are not lab containers: they have no
 * {@code jl-} prefix, no {@code lab=java-learn} label and are removed by Ryuk
 * when the JVM exits (avoid_common_error 12).</p>
 */
final class LedgerTestDatabase {

    static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");

    static final MySQLContainer<?> MYSQL = new MySQLContainer<>("mysql:8.4.4")
            .withDatabaseName("lab_ledger")
            .withUsername("test")
            .withPassword("test")
            .withUrlParam("useSSL", "false")
            .withUrlParam("allowPublicKeyRetrieval", "true")
            .withUrlParam("serverTimezone", "UTC");

    private static final DriverManagerDataSource DATA_SOURCE;

    static {
        MYSQL.start();
        DATA_SOURCE = new DriverManagerDataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(),
                MYSQL.getPassword());
        Flyway.configure()
                .dataSource(DATA_SOURCE)
                .locations("classpath:db/ledger")
                .schemas("lab_ledger")
                .defaultSchema("lab_ledger")
                .createSchemas(false)
                .load()
                .migrate();
    }

    private LedgerTestDatabase() {
    }

    static JdbcTemplate jdbc() {
        return new JdbcTemplate(DATA_SOURCE);
    }

    static RunLedgerRepository repository() {
        return new RunLedgerRepository(jdbc());
    }

    static Clock clock() {
        return Clock.fixed(NOW, ZoneOffset.UTC);
    }

    /** B1-02 idempotency-window and lock-TTL tests need to move time. */
    static MutableClock mutableClock() {
        return new MutableClock(NOW);
    }

    static final class MutableClock extends Clock {

        private Instant now;

        private MutableClock(Instant start) {
            this.now = start;
        }

        void advanceSeconds(long seconds) {
            now = now.plusSeconds(seconds);
        }

        void set(Instant instant) {
            now = instant;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    static LedgerProperties properties(Path stashDir) {
        LedgerProperties properties = new LedgerProperties();
        properties.setMysqlMode("standalone");
        properties.setStandaloneUrl(MYSQL.getJdbcUrl());
        properties.setRouterUrl(MYSQL.getJdbcUrl());
        properties.setSchema("lab_ledger");
        properties.setUser(MYSQL.getUsername());
        properties.setPassword(MYSQL.getPassword());
        properties.setStashDir(stashDir.toString());
        properties.setConnectionTimeoutMs(1000);
        properties.setValidationTimeoutMs(500);
        properties.validate();
        return properties;
    }

    static void clean() {
        JdbcTemplate jdbc = jdbc();
        // Operations outside a run have a NULL run_id and do not cascade, so
        // they are deleted explicitly.
        jdbc.update("DELETE FROM lab_operation");
        jdbc.update("DELETE FROM lab_run");
        jdbc.update("DELETE FROM lab_ledger_stash");
        jdbc.update("DELETE FROM lab_env_lock");
        jdbc.update("DELETE FROM lab_instance_config");
    }

    static RunRecord run(String runId, RunStatus status, Instant createdAt) {
        return new RunRecord(runId, "spike", status, "test-commit", "local",
                createdAt, createdAt, null, null);
    }
}
