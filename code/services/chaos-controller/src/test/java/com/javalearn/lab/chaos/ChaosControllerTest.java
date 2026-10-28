package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.EnvironmentStopAllService;
import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.ProbeTargetProtectedException;
import com.javalearn.lab.chaos.ledger.FaultEventService;
import com.javalearn.lab.chaos.ledger.LedgerViews;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationExecution;
import com.javalearn.lab.chaos.ledger.OperationOutcome;
import com.javalearn.lab.chaos.ledger.OperationRecord;
import com.javalearn.lab.chaos.ledger.OperationRequest;
import com.javalearn.lab.chaos.ledger.OperationService;
import com.javalearn.lab.chaos.ledger.OperationStatus;
import com.javalearn.lab.chaos.ledger.OperationWork;
import com.javalearn.lab.chaos.web.ChaosController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Chaos action API. B1-02 wraps the two mutating endpoints in the operation
 * contract, so the tests stub {@link OperationService#submit} to run the work
 * inline and return a settled execution - exactly what the real service does
 * when the work fits inside the sync budget. Exceptions still travel through the
 * work, which proves the existing 400/403/404/409 mapping is unchanged.
 */
@WebMvcTest(controllers = ChaosController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class ChaosControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChaosActionService chaosActionService;

    @MockitoBean
    private EnvironmentStopAllService environmentStopAllService;

    @MockitoBean
    private OperationService operations;

    @MockitoBean
    private FaultEventService faultEvents;

    @BeforeEach
    void setUp() {
        // doAnswer/doReturn never invoke the stubbed method while recording, so
        // a later override cannot accidentally run a null work.
        Answer<OperationExecution> settle = invocation -> {
            OperationWork work = invocation.getArgument(1);
            // Bind the operation id like the real OperationService worker does, so
            // a fault event recorded inside the work sees it (B2-04a).
            OperationOutcome outcome = OperationContext.withOperation("op-1", work::run);
            return new OperationExecution(operation(OperationStatus.APPLIED), false, false,
                    outcome.payload());
        };
        doAnswer(settle).when(operations).submit(any(), any());
        doAnswer(settle).when(operations).submitBlocking(any(), any());
    }

    @Test
    void validActionReturns202WithViewAndOperationId() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active", null)
                        .withRuntime(false, 30_000L));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value("id-1"))
                .andExpect(jsonPath("$.data.target").value("jl-app-3"))
                .andExpect(jsonPath("$.data.action").value("kill"))
                .andExpect(jsonPath("$.data.ttlSeconds").value(30))
                .andExpect(jsonPath("$.data.revertAction").value("start"))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.paused").value(false))
                .andExpect(jsonPath("$.data.remainingMs").value(30_000))
                .andExpect(jsonPath("$.data.operationId").value("op-1"));

        verify(chaosActionService).execute(eq("jl-app-3"), any(ChaosActionRequest.class));
    }

    @Test
    void aRunIdIsForwardedAndASuccessfulActionJoinsTheRunTimeline() throws Exception {
        when(faultEvents.validateRun("run-20261028-001")).thenReturn("DRAFT");
        when(chaosActionService.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active", null));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30,"
                                + "\"runId\":\"run-20261028-001\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.operationId").value("op-1"));

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        org.assertj.core.api.Assertions.assertThat(request.getValue().runId())
                .isEqualTo("run-20261028-001");
        verify(faultEvents).recordContainerAction("run-20261028-001", "op-1", "jl-app-3", "kill",
                30, "active", Instant.parse("2026-10-26T10:00:30Z"), "DRAFT");
    }

    @Test
    void withoutARunIdNothingIsValidatedOrWrittenToTheTimeline() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active", null));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isAccepted());

        verify(faultEvents, never()).validateRun(any());
        verify(faultEvents, never()).recordContainerAction(any(), any(), any(), any(), any(),
                any(), any(), any());
    }

    @Test
    void anUnknownRunIdIsRejectedWith404BeforeAnyActionRuns() throws Exception {
        when(faultEvents.validateRun("run-missing")).thenThrow(
                new LedgerRunNotFoundException("run not found: run-missing"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30,\"runId\":\"run-missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("run not found: run-missing"));

        verify(operations, never()).submit(any(), any());
        verify(chaosActionService, never()).execute(any(), any());
    }

    @Test
    void aSkippedFaultEventKeepsTheAppliedActionResult() throws Exception {
        when(faultEvents.validateRun("run-20261028-001")).thenReturn("RUNNING");
        when(faultEvents.recordContainerAction(any(), any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(chaosActionService.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active", null));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30,"
                                + "\"runId\":\"run-20261028-001\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"));
    }

    @Test
    void oneShotActionWithoutBodyTtlReturns202() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenReturn(new ChaosActionView("id-2", "jl-app-3", "restart", null, null, null, "completed", null));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"restart\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.action").value("restart"))
                .andExpect(jsonPath("$.data.status").value("completed"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"));
    }

    @Test
    void invalidActionReturns400() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenThrow(new InvalidChaosActionException("Unsupported action: explode (allowed: kill|start|restart|freeze|resume)"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"explode\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void malformedBodyReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(chaosActionService, never()).execute(any(), any());
    }

    @Test
    void unknownContainerReturns404() throws Exception {
        when(chaosActionService.execute(eq("jl-missing"), any()))
                .thenThrow(new ContainerNotFoundException("Container not found: jl-missing"));

        mockMvc.perform(post("/chaos/instances/jl-missing/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void nonLabContainerReturns403() throws Exception {
        when(chaosActionService.execute(eq("redis"), any()))
                .thenThrow(new ContainerNotAllowedException("Container is not part of the lab: redis"));

        mockMvc.perform(post("/chaos/instances/redis/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("40301"));
    }

    @Test
    void probeTargetActionIsRefusedWith403AndProbeIsolationCode() throws Exception {
        when(chaosActionService.execute(eq("jl-mysql-3"), any()))
                .thenThrow(new ProbeTargetProtectedException(
                        "target \"jl-mysql-3\" is the MySQL cluster probe container "
                                + "(chaos.mysql-cluster.container / env CHAOS_MYSQL_CLUSTER_CONTAINER)"));

        mockMvc.perform(post("/chaos/instances/jl-mysql-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("40302"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("MySQL cluster probe container")));
    }

    @Test
    void duplicateActiveActionReturns409() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenThrow(new ChaosConflictException("Target jl-app-3 already has active chaos action id-1"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"freeze\",\"ttlSeconds\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("id-1")));
    }

    @Test
    void aSlowActionAnswers202PendingConfirmationWithTheOperationView() throws Exception {
        OperationExecution pending = new OperationExecution(
                operation(OperationStatus.PENDING_CONFIRMATION), false, true, null);
        doReturn(pending).when(operations).submit(any(), any());
        when(operations.view(pending)).thenReturn(operationView(OperationStatus.PENDING_CONFIRMATION, false));

        mockMvc.perform(post("/chaos/instances/jl-mysql-2/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));
    }

    @Test
    void anIdempotentReplayReturns200WithTheStoredOperation() throws Exception {
        OperationExecution replay = new OperationExecution(operation(OperationStatus.APPLIED),
                true, false, null);
        doReturn(replay).when(operations).submit(any(), any());
        when(operations.view(replay)).thenReturn(operationView(OperationStatus.APPLIED, true));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .header("Idempotency-Key", "kill-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        verify(chaosActionService, never()).execute(any(), any());
    }

    @Test
    void anInvalidIdempotencyKeyIsRejectedBeforeTheActionRuns() throws Exception {
        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .header("Idempotency-Key", "has\tcontrol")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(chaosActionService, never()).execute(any(), any());
    }

    @Test
    void listReturnsActiveActionsWithPauseAndRemainingTtl() throws Exception {
        when(chaosActionService.activeActions()).thenReturn(List.of(
                new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "failed",
                        "IllegalStateException: docker start failed"),
                new ChaosActionView("id-2", "jl-app-2", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active", null)
                        .withRuntime(true, 12_000L)));

        mockMvc.perform(get("/chaos/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].target").value("jl-app-3"))
                .andExpect(jsonPath("$.data[0].revertAction").value("start"))
                .andExpect(jsonPath("$.data[0].paused").value(false))
                .andExpect(jsonPath("$.data[0].remainingMs").doesNotExist())
                .andExpect(jsonPath("$.data[0].lastError").value("IllegalStateException: docker start failed"))
                .andExpect(jsonPath("$.data[1].paused").value(true))
                .andExpect(jsonPath("$.data[1].remainingMs").value(12_000));
    }

    @Test
    void stopAllReturnsRevertedCountAndTheOperationId() throws Exception {
        when(environmentStopAllService.stopAll()).thenReturn(new StopAllResult(2, 0, 1));

        mockMvc.perform(post("/chaos/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.reverted").value(2))
                .andExpect(jsonPath("$.data.failed").value(0))
                .andExpect(jsonPath("$.data.pending").value(1))
                .andExpect(jsonPath("$.data.operationId").value("op-1"));
    }

    @Test
    void stopAllAlsoReportsNetworkReset() throws Exception {
        when(environmentStopAllService.stopAll()).thenReturn(new StopAllResult(1, 0, 0,
                new NetworkResetReport(NetworkResetReport.STATUS_RESET, 2, 2, List.of())));

        mockMvc.perform(post("/chaos/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.toxiproxy.status").value("reset"))
                .andExpect(jsonPath("$.data.toxiproxy.proxiesReset").value(2))
                .andExpect(jsonPath("$.data.toxiproxy.proxiesTotal").value(2))
                .andExpect(jsonPath("$.data.toxiproxy.errors.length()").value(0));
    }

    @Test
    void stopAllReportsTrafficThreadsAndK6InTheResponseAndAfterSummary() throws Exception {
        NetworkResetReport network = new NetworkResetReport(
                NetworkResetReport.STATUS_RESET, 2, 2, List.of());
        when(environmentStopAllService.stopAll()).thenReturn(new StopAllResult(2, 0, 0,
                network, null, StopAllResult.TRAFFIC_STOPPED,
                Map.of("app-1", "ok", "app-2", "failed"), 1, 2, 1));

        AtomicReference<OperationOutcome> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            OperationWork work = invocation.getArgument(1);
            OperationOutcome outcome = OperationContext.withOperation("op-1", work::run);
            captured.set(outcome);
            return new OperationExecution(operation(OperationStatus.APPLIED), false, false,
                    outcome.payload());
        }).when(operations).submitBlocking(any(), any());

        mockMvc.perform(post("/chaos/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reverted").value(2))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.traffic").value("stopped"))
                .andExpect(jsonPath("$.data.threads.app-1").value("ok"))
                .andExpect(jsonPath("$.data.threads.app-2").value("failed"))
                .andExpect(jsonPath("$.data.k6Stopped").value(1))
                .andExpect(jsonPath("$.data.k6Cleaned").value(2))
                .andExpect(jsonPath("$.data.k6Failed").value(1));

        assertThat(captured.get().afterSummary())
                .containsEntry("reverted", 2)
                .containsEntry("traffic", StopAllResult.TRAFFIC_STOPPED)
                .containsEntry("threads", Map.of("app-1", "ok", "app-2", "failed"))
                .containsEntry("k6Stopped", 1)
                .containsEntry("k6Cleaned", 2)
                .containsEntry("k6Failed", 1);
        verify(environmentStopAllService).stopAll();
    }

    @Test
    void anIdempotentStopAllReplayDoesNotCleanTheEnvironmentAgain() throws Exception {
        OperationExecution replay = new OperationExecution(operation(OperationStatus.APPLIED),
                true, false, null);
        doReturn(replay).when(operations).submitBlocking(any(), any());
        when(operations.view(replay)).thenReturn(operationView(OperationStatus.APPLIED, true));

        mockMvc.perform(post("/chaos/stop-all").header("Idempotency-Key", "stop-all-key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        verify(environmentStopAllService, never()).stopAll();
    }

    private static OperationRecord operation(OperationStatus status) {
        return new OperationRecord("op-1", null, "container", "jl-app-3", "kill", status, 30,
                Instant.parse("2026-10-26T10:00:00Z"), Instant.parse("2026-10-26T10:00:01Z"), null,
                null, "local", null, null);
    }

    private static LedgerViews.OperationView operationView(OperationStatus status, boolean replay) {
        return new LedgerViews.OperationView("op-1", null, "container", "jl-app-3", "kill",
                status.name(), 30, "2026-10-26T10:00:00Z", null, null, null, "local", null, null,
                replay);
    }
}
