package com.javalearn.lab.chaos.schedule;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.OperationExecution;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.chaos.ledger.OperationWork;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3-00b scheduler contract with a controllable executor: delays are asserted
 * from the captured schedule arguments, tasks are fired by invoking the captured
 * runnable, and cancel is proved by running a captured task after cancel.
 * No wall-clock sleeping, no Docker.
 */
class FaultScheduleServiceTest {

    private static final String RUN = "run-20261028-001";
    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");
    private static final String TWO_ENTRIES = """
            [
              {"atSeconds":0,"target":"jl-app-1","action":"kill","recovery":"auto","ttlSeconds":30},
              {"atSeconds":30,"target":"jl-app-2","action":"freeze","recovery":"auto","ttlSeconds":15}
            ]
            """;

    private RunLedgerRepository repository;
    private ChaosActionService chaosActions;
    private FaultEventService faultEvents;
    private OperationService operations;
    private ScheduledExecutorService scheduler;
    private FaultScheduleService service;
    private ScheduledFuture<?> firstFuture;
    private ScheduledFuture<?> secondFuture;

    @BeforeEach
    void setUp() {
        repository = mock(RunLedgerRepository.class);
        chaosActions = mock(ChaosActionService.class);
        faultEvents = mock(FaultEventService.class);
        operations = mock(OperationService.class);
        when(operations.submit(any(OperationRequest.class), any(OperationWork.class)))
                .thenAnswer(invocation -> new OperationExecution(null, false, false,
                        invocation.<OperationWork>getArgument(1).run()));
        scheduler = mock(ScheduledExecutorService.class);
        firstFuture = mock(ScheduledFuture.class);
        secondFuture = mock(ScheduledFuture.class);
        FaultScheduleValidator validator = new FaultScheduleValidator(new LedgerProperties(),
                new ChaosProperties(), new FaultScheduleProperties());
        service = new FaultScheduleService(repository, new ObjectMapper(), chaosActions,
                faultEvents, operations, scheduler, validator, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void armsOneTaskPerEntryAtRunStartPlusOffset() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        assertThat(service.arm(RUN, NOW)).isEqualTo(2);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        ArgumentCaptor<Long> delays = ArgumentCaptor.forClass(Long.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), delays.capture(),
                eq(TimeUnit.MILLISECONDS));
        assertThat(delays.getAllValues()).containsExactly(0L, 30_000L);
        assertThat(tasks.getAllValues()).hasSize(2);
    }

    @Test
    void armingTheSameRunTwiceDoesNotDoubleSchedule() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));

        assertThat(service.arm(RUN, NOW)).isEqualTo(2);
        assertThat(service.arm(RUN, NOW)).isEqualTo(2);

        verify(scheduler, times(2)).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void aMissingGroupArmsNothing() {
        when(repository.findParams(RUN)).thenReturn(List.of());

        assertThat(service.arm(RUN, NOW)).isZero();

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void anUnreadableGroupFailsArmingSoTheStartCanFailClosed() {
        persist("{not json");

        assertThatThrownBy(() -> service.arm(RUN, NOW))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("not valid JSON");

        verify(scheduler, never()).schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
    }

    @Test
    void aManualHoldOnADestructiveActionArmsAndSendsMinusOne() {
        persist("""
                [{"atSeconds":0,"target":"jl-app-1","action":"kill","recovery":"manual","ttlSeconds":null}]
                """);
        doReturn(firstFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        when(chaosActions.execute(eq("jl-app-1"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("a-1", "jl-app-1", "kill", null, null,
                        "start", "active", null));

        // A manual kill is a manual hold, not an arming error.
        assertThat(service.arm(RUN, NOW)).isEqualTo(1);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(tasks.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
        tasks.getValue().run();

        ArgumentCaptor<OperationRequest> operation = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(operation.capture(), any(OperationWork.class));
        assertThat(operation.getValue().ttlSeconds()).isEqualTo(-1);
        ArgumentCaptor<ChaosActionRequest> action = ArgumentCaptor.forClass(ChaosActionRequest.class);
        verify(chaosActions).execute(eq("jl-app-1"), action.capture());
        assertThat(action.getValue().runId()).isEqualTo(RUN);
        assertThat(action.getValue().ttlSeconds()).isEqualTo(-1);
        // The timeline row carries no TTL/expiry for a manual hold.
        verify(faultEvents).recordContainerAction(RUN, null, "jl-app-1", "kill", null, "active",
                null, "RUNNING");
    }

    @Test
    void aManualOneShotEntryStaysTtlLess() {
        persist("""
                [{"atSeconds":0,"target":"jl-app-1","action":"start","recovery":"manual","ttlSeconds":null}]
                """);
        doReturn(firstFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        when(chaosActions.execute(eq("jl-app-1"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("a-1", "jl-app-1", "start", null, null,
                        null, "completed", null));

        assertThat(service.arm(RUN, NOW)).isEqualTo(1);

        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler).schedule(tasks.capture(), eq(0L), eq(TimeUnit.MILLISECONDS));
        tasks.getValue().run();

        ArgumentCaptor<ChaosActionRequest> action = ArgumentCaptor.forClass(ChaosActionRequest.class);
        verify(chaosActions).execute(eq("jl-app-1"), action.capture());
        // start has nothing to revert: a manual entry must not send -1.
        assertThat(action.getValue().ttlSeconds()).isNull();
    }

    @Test
    void aDueEntryCallsChaosWithTheRunIdAndRecordsAnInjectedEvent() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        Instant expires = NOW.plusSeconds(30);
        when(chaosActions.execute(eq("jl-app-1"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("a-1", "jl-app-1", "kill", 30, expires,
                        "start", "active", null));

        service.arm(RUN, NOW);
        fireFirstTask();

        ArgumentCaptor<OperationRequest> operation = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(operation.capture(), any(OperationWork.class));
        assertThat(operation.getValue().runId()).isEqualTo(RUN);
        assertThat(operation.getValue().action()).isEqualTo("kill");
        ArgumentCaptor<ChaosActionRequest> action = ArgumentCaptor.forClass(ChaosActionRequest.class);
        verify(chaosActions).execute(eq("jl-app-1"), action.capture());
        assertThat(action.getValue().runId()).isEqualTo(RUN);
        assertThat(action.getValue().ttlSeconds()).isEqualTo(30);
        verify(faultEvents).recordContainerAction(RUN, null, "jl-app-1", "kill", 30, "active",
                expires, "RUNNING");
    }

    @Test
    void aFailedActionWritesAFailureEventAndNeverAnInjectedOne() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        when(chaosActions.execute(eq("jl-app-1"), any(ChaosActionRequest.class)))
                .thenThrow(new IllegalStateException("container is gone"));

        service.arm(RUN, NOW);
        fireFirstTask();

        verify(faultEvents).recordEvent(eq(RUN), isNull(), eq("schedule"), eq("jl-app-1"),
                eq("kill-failed"), anyMap(), eq("RUNNING"));
        verify(faultEvents, never()).recordContainerAction(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void anOperationSubmissionFailureStillWritesAFailureEvent() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));
        when(operations.submit(any(OperationRequest.class), any(OperationWork.class)))
                .thenThrow(new IllegalStateException("operation pool is down"));

        service.arm(RUN, NOW);
        fireFirstTask();

        verify(faultEvents).recordEvent(eq(RUN), isNull(), eq("schedule"), eq("jl-app-1"),
                eq("kill-failed"), anyMap(), eq("RUNNING"));
        verify(chaosActions, never()).execute(any(), any());
    }

    @Test
    void aTaskThatFiresAfterStopIsSkipped() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.COMPLETED)));

        service.arm(RUN, NOW);
        fireFirstTask();

        verify(chaosActions, never()).execute(any(), any());
        verify(faultEvents, never()).recordContainerAction(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void cancelCancelsPendingFuturesAndPreventsALateAction() {
        persist(TWO_ENTRIES);
        doReturn(firstFuture, secondFuture).when(scheduler)
                .schedule(any(Runnable.class), anyLong(), any(TimeUnit.class));
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        service.arm(RUN, NOW);
        verify(scheduler, times(2)).schedule(tasks.capture(), anyLong(), any(TimeUnit.class));

        service.cancel(RUN);

        verify(firstFuture).cancel(false);
        verify(secondFuture).cancel(false);
        // Even a task already handed to a worker must not inject after cancel.
        tasks.getAllValues().forEach(Runnable::run);
        verify(chaosActions, never()).execute(any(), any());
        verify(operations, never()).submit(any(), any());
        verify(faultEvents, never()).recordContainerAction(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    private void persist(String payload) {
        when(repository.findParams(RUN)).thenReturn(List.of(
                new RunLedgerRepository.RunParamRow("faultSchedule", payload, NOW)));
    }

    private void fireFirstTask() {
        ArgumentCaptor<Runnable> tasks = ArgumentCaptor.forClass(Runnable.class);
        verify(scheduler, times(2)).schedule(tasks.capture(), anyLong(), any(TimeUnit.class));
        tasks.getAllValues().get(0).run();
    }

    private static RunRecord run(RunStatus status) {
        return new RunRecord(RUN, "spike", status, "test-commit", "local",
                NOW, NOW, NOW, null);
    }
}
