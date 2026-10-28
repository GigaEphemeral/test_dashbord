package com.javalearn.lab.chaos.ledger;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.OperationCloseConflictException;
import com.javalearn.lab.chaos.error.OperationNotFoundException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationCloseRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2-04a UNKNOWN closure: only UNKNOWN may be closed, only as RESTORED/FAILED,
 * the same verdict is idempotent, the other verdict is 409, and the note limits
 * are enforced before anything is written. The run row/lock are never touched
 * (the service has no access to them on this path).
 */
class OperationCloseTest {

    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");

    private LedgerProperties properties;
    private LedgerBootstrap bootstrap;
    private RunLedgerRepository repository;
    private ScheduledExecutorService executor;
    private OperationService operations;

    @BeforeEach
    void setUp() {
        properties = new LedgerProperties();
        bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "2", null, NOW));
        repository = mock(RunLedgerRepository.class);
        executor = Executors.newSingleThreadScheduledExecutor();
        operations = new OperationService(properties, bootstrap, repository, mock(LedgerWriter.class),
                executor, new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @AfterEach
    void tearDown() {
        executor.shutdownNow();
    }

    @Test
    void unknownCanBeClosedAsRestoredWithAStampedRestoredAt() {
        when(repository.findOperation("op-1")).thenReturn(
                Optional.of(operation(OperationStatus.UNKNOWN)),
                Optional.of(operation(OperationStatus.RESTORED, "target verified running")));
        when(repository.closeUnknownOperation("op-1", OperationStatus.RESTORED,
                "target verified running", NOW)).thenReturn(1);

        var view = operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "target verified running"));

        assertThat(view.status()).isEqualTo("RESTORED");
        assertThat(view.lastError()).isEqualTo("target verified running");
        assertThat(view.idempotentReplay()).isFalse();
        verify(repository).closeUnknownOperation("op-1", OperationStatus.RESTORED,
                "target verified running", NOW);
    }

    @Test
    void unknownCanBeClosedAsFailedWithoutFakingARestoredAt() {
        when(repository.findOperation("op-1")).thenReturn(
                Optional.of(operation(OperationStatus.UNKNOWN)),
                Optional.of(operation(OperationStatus.FAILED, "container left stopped")));
        when(repository.closeUnknownOperation("op-1", OperationStatus.FAILED,
                "container left stopped", null)).thenReturn(1);

        var view = operations.closeUnknown("op-1",
                new OperationCloseRequest("FAILED", "container left stopped"));

        assertThat(view.status()).isEqualTo("FAILED");
        assertThat(view.restoredAt()).isNull();
        verify(repository).closeUnknownOperation("op-1", OperationStatus.FAILED,
                "container left stopped", null);
    }

    @Test
    void anAlreadySettledOperationIsNotCloseable() {
        when(repository.findOperation("op-1"))
                .thenReturn(Optional.of(operation(OperationStatus.APPLIED)));

        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "verified")))
                .isInstanceOf(OperationCloseConflictException.class)
                .hasMessageContaining("APPLIED");

        verify(repository, never()).closeUnknownOperation(any(), any(), any(), any());
    }

    @Test
    void aMissingOperationIs404() {
        when(repository.findOperation("op-missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> operations.closeUnknown("op-missing",
                new OperationCloseRequest("RESTORED", null)))
                .isInstanceOf(OperationNotFoundException.class);
    }

    @Test
    void repeatingTheSameVerdictReturnsTheCurrentViewWithoutASecondWrite() {
        when(repository.findOperation("op-1"))
                .thenReturn(Optional.of(operation(OperationStatus.RESTORED, "verified")));

        var view = operations.closeUnknown("op-1",
                new OperationCloseRequest("restored", "verified"));

        assertThat(view.status()).isEqualTo("RESTORED");
        verify(repository, never()).closeUnknownOperation(any(), any(), any(), any());
    }

    @Test
    void theOtherTerminalVerdictIsAConflict() {
        when(repository.findOperation("op-1"))
                .thenReturn(Optional.of(operation(OperationStatus.RESTORED, "verified")));

        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("FAILED", "changed my mind")))
                .isInstanceOf(OperationCloseConflictException.class);

        verify(repository, never()).closeUnknownOperation(any(), any(), any(), any());
    }

    @Test
    void losingAConcurrentCloseToTheSameVerdictIsIdempotent() {
        when(repository.findOperation("op-1")).thenReturn(
                Optional.of(operation(OperationStatus.UNKNOWN)),
                Optional.of(operation(OperationStatus.RESTORED, "verified")));
        when(repository.closeUnknownOperation("op-1", OperationStatus.RESTORED, "verified", NOW))
                .thenReturn(0);

        var view = operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "verified"));

        assertThat(view.status()).isEqualTo("RESTORED");
    }

    @Test
    void losingAConcurrentCloseToTheOtherVerdictIsAConflict() {
        when(repository.findOperation("op-1")).thenReturn(
                Optional.of(operation(OperationStatus.UNKNOWN)),
                Optional.of(operation(OperationStatus.FAILED, "other operator")));
        when(repository.closeUnknownOperation("op-1", OperationStatus.RESTORED, "verified", NOW))
                .thenReturn(0);

        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "verified")))
                .isInstanceOf(OperationCloseConflictException.class)
                .hasMessageContaining("FAILED");
    }

    @Test
    void onlyRestoredAndFailedAreCloseVerdicts() {
        for (String status : new String[]{null, " ", "APPLIED", "UNKNOWN", "REQUESTED", "done"}) {
            assertThatThrownBy(() -> operations.closeUnknown("op-1",
                    new OperationCloseRequest(status, null)))
                    .as("status %s", status)
                    .isInstanceOf(InvalidLedgerRequestException.class);
        }
        verify(repository, never()).findOperation(anyString());
    }

    @Test
    void noteLengthAndControlCharactersAreRejectedBeforeAnyWrite() {
        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "x".repeat(OperationService.MAX_CLOSE_NOTE_LENGTH + 1))))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("at most 512");
        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", "line one\nline two")))
                .isInstanceOf(InvalidLedgerRequestException.class)
                .hasMessageContaining("control characters");
        verify(repository, never()).findOperation(anyString());
    }

    @Test
    void anOmittedNoteKeepsTheRecoveryReason() {
        when(repository.findOperation("op-1")).thenReturn(
                Optional.of(operation(OperationStatus.UNKNOWN)),
                Optional.of(operation(OperationStatus.RESTORED,
                        "unconfirmed after controller restart")));
        when(repository.closeUnknownOperation(eq("op-1"), eq(OperationStatus.RESTORED),
                isNull(), eq(NOW))).thenReturn(1);

        var view = operations.closeUnknown("op-1", new OperationCloseRequest("RESTORED", "  "));

        assertThat(view.lastError()).isEqualTo("unconfirmed after controller restart");
        verify(repository).closeUnknownOperation("op-1", OperationStatus.RESTORED, null, NOW);
    }

    @Test
    void aDegradedLedgerIs503AndTouchesNothing() {
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.DEGRADED, "2", "connection refused", NOW));

        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", null)))
                .isInstanceOf(LedgerUnavailableException.class);

        verify(repository, never()).findOperation(anyString());
    }

    @Test
    void aQueryFailureIsReportedAsLedgerUnavailable() {
        when(repository.findOperation("op-1"))
                .thenThrow(new DataAccessResourceFailureException("mysql is down"));

        assertThatThrownBy(() -> operations.closeUnknown("op-1",
                new OperationCloseRequest("RESTORED", null)))
                .isInstanceOf(LedgerUnavailableException.class);
    }

    private static OperationRecord operation(OperationStatus status) {
        return operation(status, "unconfirmed after controller restart");
    }

    private static OperationRecord operation(OperationStatus status, String lastError) {
        return new OperationRecord("op-1", "run-20261028-001", "container", "jl-app-3", "kill",
                status, 30, NOW, null, null, lastError, "local", null, null);
    }
}
