package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2-04a fault timeline: a valid run gets one row after a successful action, no
 * run id writes nothing, a ledger failure never fails the action, and run-id
 * validation separates 404/400 from "ledger unreachable".
 */
class FaultEventServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");

    private RunLedgerRepository repository;
    private FaultEventService service;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        repository = mock(RunLedgerRepository.class);
        objectMapper = new ObjectMapper();
        service = new FaultEventService(repository, objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void aSuccessfulActionWritesOneContainerEventWithTheDocumentedDetail() throws Exception {
        boolean written = service.recordContainerAction("run-20261028-001", "op-1", "jl-app-3",
                "kill", 30, "active", Instant.parse("2026-10-28T10:16:00Z"), "DRAFT");

        assertThat(written).isTrue();
        ArgumentCaptor<String> detail = ArgumentCaptor.forClass(String.class);
        verify(repository).insertFaultEvent(org.mockito.ArgumentMatchers.eq("run-20261028-001"),
                org.mockito.ArgumentMatchers.eq("op-1"),
                org.mockito.ArgumentMatchers.eq("container"),
                org.mockito.ArgumentMatchers.eq("jl-app-3"),
                org.mockito.ArgumentMatchers.eq("kill"),
                org.mockito.ArgumentMatchers.eq(NOW), detail.capture());
        JsonNode json = objectMapper.readTree(detail.getValue());
        assertThat(json.get("ttlSeconds").asInt()).isEqualTo(30);
        assertThat(json.get("status").asText()).isEqualTo("active");
        assertThat(json.get("operationId").asText()).isEqualTo("op-1");
        assertThat(json.get("expiresAt").asText()).isEqualTo("2026-10-28T10:16:00Z");
        assertThat(json.get("runStatus").asText()).isEqualTo("DRAFT");
    }

    @Test
    void noRunIdMeansNoTimelineRow() {
        assertThat(service.recordContainerAction(null, "op-1", "jl-app-3", "kill", 30, "active",
                null, null)).isFalse();
        assertThat(service.recordContainerAction("  ", "op-1", "jl-app-3", "kill", 30, "active",
                null, null)).isFalse();

        verify(repository, never()).insertFaultEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void aLedgerWriteFailureIsSwallowedSoTheActionStaysSuccessful() {
        org.mockito.Mockito.doThrow(new DataAccessResourceFailureException("mysql is down"))
                .when(repository).insertFaultEvent(any(), any(), any(), any(), any(), any(), any());

        assertThat(service.recordContainerAction("run-20261028-001", "op-1", "jl-app-3", "kill",
                30, "active", null, "RUNNING")).isFalse();
    }

    @Test
    void validateRunReturnsTheCurrentStatusWithoutRequiringRunning() {
        when(repository.findRun("run-20261028-001")).thenReturn(Optional.of(new RunRecord(
                "run-20261028-001", "spike", RunStatus.DRAFT, "abc", "local", NOW, NOW, null, null)));

        assertThat(service.validateRun("run-20261028-001")).isEqualTo("DRAFT");
        assertThat(service.validateRun(null)).isNull();
        assertThat(service.validateRun("  ")).isNull();
    }

    @Test
    void aMissingRunIsRejectedByTheRepositoryAnswer() {
        when(repository.findRun("run-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.validateRun("run-missing"))
                .isInstanceOf(LedgerRunNotFoundException.class)
                .hasMessageContaining("run-missing");
    }

    @Test
    void anUnreachableLedgerSkipsTheTimelineInsteadOfFailingTheFault() {
        when(repository.findRun(anyString()))
                .thenThrow(new DataAccessResourceFailureException("mysql is down"));

        assertThat(service.validateRun("run-20261028-001")).isNull();
    }

    @Test
    void malformedRunIdsAreRejectedAs400BeforeTheQuery() {
        assertThatThrownBy(() -> service.validateRun("run/../etc"))
                .isInstanceOf(InvalidLedgerRequestException.class);
        assertThatThrownBy(() -> service.validateRun("x".repeat(65)))
                .isInstanceOf(InvalidLedgerRequestException.class);
        verify(repository, never()).findRun(any());
    }
}
