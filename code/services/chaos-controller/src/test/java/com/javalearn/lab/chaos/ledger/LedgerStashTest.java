package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * Real write-behind behaviour: the repository talks to a DataSource that
 * refuses every connection (the MySQL-outage shape), the stash is a real
 * directory, and replay goes to the real mysql:8.4.4 container. The audit row
 * and the file lifecycle are asserted, not mocked.
 */
class LedgerStashTest {

    private static final Instant NOW = LedgerTestDatabase.NOW;

    @TempDir
    Path stashDir;

    private final ObjectMapper objectMapper = new ObjectMapper();
    private LedgerProperties properties;

    @BeforeEach
    void setUp() {
        LedgerTestDatabase.clean();
        properties = LedgerTestDatabase.properties(stashDir);
    }

    @Test
    void repositoryThrowsButTheCallerSucceedsAndTheEventLandsOnDisk() throws IOException {
        RunLedgerRepository broken = brokenRepository();
        LedgerStash stash = new LedgerStash(properties, broken, objectMapper, LedgerTestDatabase.clock());
        LedgerWriter writer = new LedgerWriter(broken, stash, objectMapper, LedgerTestDatabase.clock());

        assertThatCode(() -> writer.persistCreate(run("run-20261028-001"), "{}"))
                .doesNotThrowAnyException();

        assertThat(stash.pendingCount()).isEqualTo(1);
        assertThat(stashFiles()).singleElement().satisfies(file ->
                assertThat(read(file)).contains("run-20261028-001").contains("RUN_CREATE"));
        assertThat(writer.lastError()).contains("ledger unavailable");
    }

    @Test
    void successfulReplayInsertsTheRunWritesTheFlushedAuditAndDeletesTheFile() {
        LedgerStash stashing = new LedgerStash(properties, brokenRepository(), objectMapper,
                LedgerTestDatabase.clock());
        LedgerWriter writer = new LedgerWriter(brokenRepository(), stashing, objectMapper,
                LedgerTestDatabase.clock());
        writer.persistCreate(run("run-20261028-002"), "{\"vus\":10}");
        assertThat(stashing.pendingCount()).isEqualTo(1);

        LedgerStash replaying = new LedgerStash(properties, LedgerTestDatabase.repository(), objectMapper,
                LedgerTestDatabase.clock());
        assertThat(replaying.replay()).isEqualTo(1);

        RunLedgerRepository real = LedgerTestDatabase.repository();
        assertThat(real.findRun("run-20261028-002")).isPresent();
        assertThat(real.findParams("run-20261028-002")).hasSize(1);
        List<RunLedgerRepository.StashAuditRow> audits = real.findStashAudits();
        assertThat(audits).hasSize(1);
        assertThat(audits.get(0).kind()).isEqualTo("RUN_CREATE");
        assertThat(audits.get(0).flushedAt()).isNotNull();
        assertThat(replaying.pendingCount()).isZero();

        // Idempotent: a second pass neither re-applies nor writes another audit row.
        assertThat(replaying.replay()).isZero();
        assertThat(real.findStashAudits()).hasSize(1);
    }

    @Test
    void oldestEventIsDroppedWhenTheFileCapIsExceeded() {
        properties.setStashMaxFiles(2);
        LedgerStash stash = new LedgerStash(properties, brokenRepository(), objectMapper,
                LedgerTestDatabase.clock());
        LedgerWriter writer = new LedgerWriter(brokenRepository(), stash, objectMapper,
                LedgerTestDatabase.clock());

        writer.persistCreate(run("run-20261028-001"), "{}");
        writer.persistCreate(run("run-20261028-002"), "{}");
        writer.persistCreate(run("run-20261028-003"), "{}");

        assertThat(stash.pendingCount()).isEqualTo(2);
        String remaining = stashFiles().stream().map(this::read).collect(Collectors.joining("\n"));
        assertThat(remaining).doesNotContain("run-20261028-001")
                .contains("run-20261028-002")
                .contains("run-20261028-003");
    }

    @Test
    void failedReplayKeepsTheFileForTheNextAttempt() {
        LedgerStash stashing = new LedgerStash(properties, brokenRepository(), objectMapper,
                LedgerTestDatabase.clock());
        LedgerWriter writer = new LedgerWriter(brokenRepository(), stashing, objectMapper,
                LedgerTestDatabase.clock());
        writer.persistCreate(run("run-20261028-004"), "{}");

        LedgerStash replaying = new LedgerStash(properties, brokenRepository(), objectMapper,
                LedgerTestDatabase.clock());
        assertThat(replaying.replay()).isZero();
        assertThat(replaying.pendingCount()).isEqualTo(1);
        assertThat(LedgerTestDatabase.repository().findRun("run-20261028-004")).isEmpty();
    }

    @Test
    void stashedOperationIsInsertedAndSettledOnReplay() {
        Instant now = LedgerTestDatabase.NOW;
        OperationRecord operation = new OperationRecord("op-stash-1", null, "container", "jl-app-1",
                "kill", OperationStatus.REQUESTED, 30, now, null, null, null, "local",
                "{\"container\":\"jl-app-1\"}", null);

        LedgerStash stashing = new LedgerStash(properties, brokenRepository(), objectMapper,
                LedgerTestDatabase.clock());
        LedgerWriter broken = new LedgerWriter(brokenRepository(), stashing, objectMapper,
                LedgerTestDatabase.clock());
        assertThat(broken.persistOperation(operation, "key-1").stashed()).isTrue();
        assertThat(stashing.pendingCount()).isEqualTo(1);

        LedgerStash replaying = new LedgerStash(properties, LedgerTestDatabase.repository(), objectMapper,
                LedgerTestDatabase.clock());
        assertThat(replaying.replay()).isEqualTo(1);

        RunLedgerRepository real = LedgerTestDatabase.repository();
        OperationRecord inserted = real.findOperation("op-stash-1").orElseThrow();
        assertThat(inserted.status()).isEqualTo(OperationStatus.REQUESTED);
        assertThat(inserted.operator()).isEqualTo("local");
        assertThat(real.findOperationByIdempotencyKey("key-1", "kill", "container", "jl-app-1")).isPresent();

        // A settle event written while the database is down replays into APPLIED.
        assertThat(broken.completeOperation(inserted, OperationStatus.APPLIED, now, null, null, null)
                .stashed()).isTrue();
        assertThat(replaying.replay()).isEqualTo(1);
        assertThat(real.findOperation("op-stash-1").orElseThrow().status())
                .isEqualTo(OperationStatus.APPLIED);
        assertThat(real.findStashAudits()).extracting(RunLedgerRepository.StashAuditRow::kind)
                .containsExactly("OPERATION", "OPERATION");

        // Replaying an already settled operation is a no-op (first verdict wins).
        assertThat(replaying.replay()).isZero();
    }

    private RunLedgerRepository brokenRepository() {
        return new RunLedgerRepository(new JdbcTemplate(brokenDataSource()));
    }

    private static RunRecord run(String runId) {
        return LedgerTestDatabase.run(runId, RunStatus.DRAFT, NOW);
    }

    private List<Path> stashFiles() {
        try (Stream<Path> stream = Files.list(stashDir)) {
            return stream.filter(path -> path.getFileName().toString().endsWith(".json")).sorted().toList();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static DataSource brokenDataSource() {
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                throw new SQLException("ledger unavailable");
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                throw new SQLException("ledger unavailable");
            }
        };
    }
}
