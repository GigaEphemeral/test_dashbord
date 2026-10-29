package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.precheck.PrecheckReport;
import com.javalearn.lab.chaos.precheck.RunPrecheckGate;
import com.javalearn.lab.chaos.schedule.FaultScheduler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B3-00b lifecycle wiring, with the operation contract stubbed to run the work
 * inline. Proves the ordering (launch → arm → RUNNING), the armed count in the
 * start summary, cancel-on-stop and the fail-closed path when arming throws.
 */
class RunLifecycleFaultScheduleTest {

    private static final String RUN = "run-20261028-001";
    private static final Instant NOW = Instant.parse("2026-10-28T10:15:30.123Z");

    private RunLedgerRepository repository;
    private LedgerWriter writer;
    private EnvLockService envLock;
    private OperationService operations;
    private RunPrecheckGate precheck;
    private RunLifecycleHook hook;
    private FaultScheduler faultSchedule;
    private RunLifecycleService lifecycle;

    @BeforeEach
    void setUp() {
        LedgerProperties properties = new LedgerProperties();
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "3", null, NOW));
        repository = mock(RunLedgerRepository.class);
        writer = mock(LedgerWriter.class);
        when(writer.transition(anyString(), any(), any(), anyString(), any(), any()))
                .thenReturn(true);
        envLock = mock(EnvLockService.class);
        when(envLock.acquire(RUN)).thenReturn(new EnvLockService.LockHandle(
                "env:write-run", RUN, "local", NOW, NOW.plusSeconds(1800)));
        when(envLock.release(RUN)).thenReturn(new EnvLockService.ReleaseReport(true, null, null));
        precheck = mock(RunPrecheckGate.class);
        when(precheck.enforce(RUN)).thenReturn(PrecheckReport.disabled(RUN, NOW.toString()));
        hook = mock(RunLifecycleHook.class);
        when(hook.launch(RUN)).thenReturn(RunLifecycleHook.LaunchResult.none());
        when(hook.stop(RUN)).thenReturn(RunLifecycleHook.LaunchResult.none());
        operations = mock(OperationService.class);
        when(operations.submit(any(OperationRequest.class), any(OperationWork.class)))
                .thenAnswer(invocation -> new OperationExecution(null, false, false,
                        invocation.<OperationWork>getArgument(1).run()));
        faultSchedule = mock(FaultScheduler.class);
        when(faultSchedule.arm(eq(RUN), any(Instant.class))).thenReturn(2);

        lifecycle = new RunLifecycleService(properties, bootstrap, repository, writer, envLock,
                operations, precheck, hook, faultSchedule, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void startArmsAfterLaunchAndReportsTheArmedCount() {
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.DRAFT)));

        OperationExecution execution = lifecycle.start(RUN, null);

        OperationOutcome outcome = (OperationOutcome) execution.payload();
        assertThat(outcome.afterSummary()).containsEntry("faultScheduleArmed", 2);
        assertThat(outcome.afterSummary()).containsEntry("runStatus", "RUNNING");
        verify(faultSchedule).arm(RUN, NOW);
        InOrder order = inOrder(hook, faultSchedule);
        order.verify(hook).launch(RUN);
        order.verify(faultSchedule).arm(RUN, NOW);
    }

    @Test
    void stopCancelsPendingFaultsBeforeStoppingK6() {
        when(repository.findRun(RUN)).thenReturn(Optional.of(run(RunStatus.RUNNING)));

        lifecycle.stop(RUN, null);

        verify(faultSchedule).cancel(RUN);
        InOrder order = inOrder(faultSchedule, hook);
        order.verify(faultSchedule).cancel(RUN);
        order.verify(hook).stop(RUN);
    }

    @Test
    void anArmingFailureFailsTheRunStopsK6AndCancelsThePartialSchedule() {
        when(repository.findRun(RUN)).thenReturn(
                Optional.of(run(RunStatus.DRAFT)), Optional.of(run(RunStatus.DRAFT)),
                Optional.of(run(RunStatus.READY)));
        when(faultSchedule.arm(eq(RUN), any(Instant.class)))
                .thenThrow(new IllegalStateException("fault schedule is unreadable"));

        assertThatThrownBy(() -> lifecycle.start(RUN, null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("unreadable");

        verify(hook).stop(RUN);
        verify(faultSchedule).cancel(RUN);
        verify(writer).transition(eq(RUN), eq(RunStatus.READY), eq(RunStatus.FAILED),
                eq("start-failed"), eq(NOW), anyString());
        verify(envLock).release(RUN);
    }

    private static RunRecord run(RunStatus status) {
        return new RunRecord(RUN, "spike", status, "test-commit", "local",
                NOW, NOW, NOW, null);
    }
}
