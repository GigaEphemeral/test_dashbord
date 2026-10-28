package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Flyway schema + real MySQL round-trips. This is the *IT gate under surefire:
 * it proves the 10 tables, native JSON columns, UTC millisecond timestamps and
 * the run-id unique-key retry against mysql:8.4.4 (Testcontainers), not mocks.
 */
class LedgerSchemaIT {

    @TempDir
    Path stashDir;

    private RunLedgerRepository repository;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        repository = LedgerTestDatabase.repository();
    }

    @Test
    void flywayCreatesExactlyTheTenLedgerTablesInInnoDbUtf8mb4() {
        JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
        List<String> tables = jdbc.queryForList(
                "SELECT table_name FROM information_schema.tables "
                        + "WHERE table_schema = 'lab_ledger' AND table_name LIKE 'lab\\_%' "
                        + "ORDER BY table_name",
                String.class);

        assertThat(tables).containsExactly(
                "lab_env_lock", "lab_fault_event", "lab_ledger_stash", "lab_metric_window",
                "lab_operation", "lab_run", "lab_run_check", "lab_run_env", "lab_run_param",
                "lab_run_step");

        Integer innoDbUtf8 = jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.tables "
                        + "WHERE table_schema = 'lab_ledger' AND table_name LIKE 'lab\\_%' "
                        + "AND engine = 'InnoDB' AND table_collation = 'utf8mb4_0900_ai_ci'",
                Integer.class);
        assertThat(innoDbUtf8).isEqualTo(10);
    }

    @Test
    void jsonColumnsAcceptAndReturnStructuredPayloads() throws Exception {
        Instant now = LedgerTestDatabase.NOW;
        repository.insertRun(LedgerTestDatabase.run("run-20261028-101", RunStatus.DRAFT, now));
        repository.insertParam("run-20261028-101", "load",
                "{\"vus\":100,\"thresholds\":{\"p95\":\"200ms\"}}", now);

        JdbcTemplate jdbc = LedgerTestDatabase.jdbc();
        String vus = jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.vus')) FROM lab_run_param "
                        + "WHERE run_id = ? AND group_name = 'load'",
                String.class, "run-20261028-101");
        String p95 = jdbc.queryForObject(
                "SELECT JSON_UNQUOTE(JSON_EXTRACT(payload, '$.thresholds.p95')) FROM lab_run_param "
                        + "WHERE run_id = ? AND group_name = 'load'",
                String.class, "run-20261028-101");

        assertThat(vus).isEqualTo("100");
        assertThat(p95).isEqualTo("200ms");
        assertThat(new ObjectMapper().readTree(
                repository.findParams("run-20261028-101").get(0).payloadJson()).path("vus").asInt())
                .isEqualTo(100);
    }

    @Test
    void runCreateAndQueryRoundTripKeepsUtcMilliseconds() {
        Instant created = Instant.parse("2026-10-28T10:15:30.123Z");
        RunRecord run = new RunRecord("run-20261028-102", "cache-lab", RunStatus.DRAFT,
                "abc1234", "local", created, created, null, null);

        repository.insertRun(run);
        RunRecord loaded = repository.findRun("run-20261028-102").orElseThrow();

        assertThat(loaded).isEqualTo(run);
        assertThat(loaded.createdAt()).isEqualTo(created);
        assertThat(repository.listRuns("cache-lab", RunStatus.DRAFT, 10, 0))
                .extracting(RunRecord::runId).containsExactly("run-20261028-102");
        assertThat(repository.countRuns("cache-lab", null)).isEqualTo(1);
        assertThat(repository.listRuns("cache-lab", RunStatus.COMPLETED, 10, 0)).isEmpty();
    }

    @Test
    void duplicateRunIdIsRetriedWithTheNextDailySequence() {
        Instant created = LedgerTestDatabase.NOW;
        repository.insertRun(LedgerTestDatabase.run("run-20261028-001", RunStatus.DRAFT, created));

        RunLedgerRepository stale = new StaleFirstSequenceRepository(
                new JdbcTemplate(new org.springframework.jdbc.datasource.DriverManagerDataSource(
                        LedgerTestDatabase.MYSQL.getJdbcUrl(),
                        LedgerTestDatabase.MYSQL.getUsername(),
                        LedgerTestDatabase.MYSQL.getPassword())));
        LedgerProperties properties = LedgerTestDatabase.properties(stashDir);
        LedgerStash stash = new LedgerStash(properties, stale, new ObjectMapper(),
                LedgerTestDatabase.clock());
        LedgerWriter writer = new LedgerWriter(stale, stash, new ObjectMapper(),
                LedgerTestDatabase.clock());
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "1", null, created));
        RunLedgerService service = new RunLedgerService(properties,
                new LedgerTargetResolver(properties), bootstrap, stale, writer, stash,
                new ObjectMapper(), LedgerTestDatabase.clock());

        var view = service.create(new RunCreateRequest("spike", Map.of("vus", 10)));

        assertThat(view.runId()).isEqualTo("run-20261028-002");
        assertThat(view.status()).isEqualTo("DRAFT");
        assertThat(repository.countRuns(null, null)).isEqualTo(2);
        assertThat(repository.findParams(view.runId())).hasSize(1);
    }

    /** Simulates the race: the sequence read sees an older state than the insert. */
    private static final class StaleFirstSequenceRepository extends RunLedgerRepository {

        private boolean first = true;

        private StaleFirstSequenceRepository(JdbcTemplate jdbc) {
            super(jdbc);
        }

        @Override
        public int maxRunSequence(String prefix) {
            if (first) {
                first = false;
                return 0;
            }
            return super.maxRunSequence(prefix);
        }
    }
}
