package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.EnvLockConflictException;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.OperationCloseConflictException;
import com.javalearn.lab.chaos.error.OperationNotFoundException;
import com.javalearn.lab.chaos.error.PrecheckBlockedException;
import com.javalearn.lab.chaos.precheck.PrecheckItem;
import com.javalearn.lab.chaos.precheck.PrecheckReport;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.List;

import static com.javalearn.lab.chaos.ledger.LedgerViews.FaultEventView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerRecoveryView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerStatusView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationCloseRequest;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDetailView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.StepView;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract for the ledger + operation API: 201 create, paged list, detail,
 * 404, 503/50302 when the ledger is DEGRADED, the B1-02 start/stop operation
 * answers (200 settled, 202 PENDING_CONFIRMATION, 200 idempotent replay), 40902
 * lock conflicts, header validation and the operation polling endpoints.
 *
 * <p>The services are stubbed here because this test owns routing, the envelope
 * and the status mapping; real database/executor behaviour lives in
 * OperationServiceIT and RunLifecycleIT.</p>
 */
class LedgerControllerTest {

    private static final String STARTED_AT = "2026-10-28T10:15:30.123Z";

    private RunLedgerService service;
    private RunLifecycleService lifecycle;
    private OperationService operations;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RunLedgerService.class);
        lifecycle = mock(RunLifecycleService.class);
        operations = mock(OperationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LedgerController(service, lifecycle, operations))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void createReturns201WithTheDraftRun() throws Exception {
        when(service.create(any())).thenReturn(new RunCreateView("run-20261028-001", "DRAFT",
                STARTED_AT));

        mvc.perform(post("/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"spike\",\"params\":{\"vus\":10}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.runId").value("run-20261028-001"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.createdAt").value(STARTED_AT));
    }

    @Test
    void listPassesFiltersAndPagingAndReturnsTheTotal() throws Exception {
        RunView run = run("DRAFT");
        when(service.list("spike", "DRAFT", 10, 5, false))
                .thenReturn(new RunPageView(List.of(run), 1, 10, 5));

        mvc.perform(get("/lab/runs")
                        .param("scenario", "spike")
                        .param("status", "DRAFT")
                        .param("limit", "10")
                        .param("offset", "5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.limit").value(10))
                .andExpect(jsonPath("$.data.offset").value(5))
                .andExpect(jsonPath("$.data.items[0].runId").value("run-20261028-001"));

        verify(service).list("spike", "DRAFT", 10, 5, false);
    }

    @Test
    void listIncludesArchivedOnlyWhenAsked() throws Exception {
        when(service.list(null, null, null, null, true))
                .thenReturn(new RunPageView(List.of(run("COMPLETED")), 1, 50, 0));

        mvc.perform(get("/lab/runs").param("includeArchived", "true"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.items[0].name").value("冒烟基线"))
                .andExpect(jsonPath("$.data.items[0].nodes[1]").value("app-2"));

        verify(service).list(null, null, null, null, true);
    }

    @Test
    void archiveAndUnarchiveReturnTheUpdatedRun() throws Exception {
        when(service.setArchived("run-20261028-001", true))
                .thenReturn(new RunView("run-20261028-001", "spike", "冒烟基线", List.of("app-1"),
                        "COMPLETED", "abc", "local", STARTED_AT, STARTED_AT, STARTED_AT, STARTED_AT,
                        true));
        when(service.setArchived("run-20261028-001", false))
                .thenReturn(run("COMPLETED"));

        mvc.perform(post("/lab/runs/run-20261028-001/archive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(true));

        mvc.perform(post("/lab/runs/run-20261028-001/unarchive"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.archived").value(false));
    }

    @Test
    void deleteReturnsTheArtifactSummary() throws Exception {
        when(lifecycle.delete("run-20261028-001"))
                .thenReturn(new LedgerViews.RunDeleteView("run-20261028-001", true,
                        "{k6ContainerRemoved=true, k6ReportRemoved=true}"));

        mvc.perform(delete("/lab/runs/run-20261028-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.runId").value("run-20261028-001"))
                .andExpect(jsonPath("$.data.deleted").value(true))
                .andExpect(jsonPath("$.data.artifacts").value(
                        org.hamcrest.Matchers.containsString("k6ReportRemoved")));
    }

    @Test
    void archivingALiveRunAnswers409() throws Exception {
        when(service.setArchived("run-20261028-001", true))
                .thenThrow(new com.javalearn.lab.chaos.error.LedgerConflictException(
                        "run run-20261028-001 is RUNNING; stop it (or close an UNKNOWN run) before "
                                + "you archive it"));

        mvc.perform(post("/lab/runs/run-20261028-001/archive"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("RUNNING")));
    }

    @Test
    void detailReturnsRunStepsEnvironmentParamsAndChecks() throws Exception {
        when(service.get("run-20261028-001")).thenReturn(new RunDetailView(run("DRAFT"),
                List.of(new StepView("create", null, "DRAFT", STARTED_AT, null, null)),
                List.of(), List.of(), List.of()));

        mvc.perform(get("/lab/runs/run-20261028-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.runId").value("run-20261028-001"))
                .andExpect(jsonPath("$.data.steps[0].stepKey").value("create"))
                .andExpect(jsonPath("$.data.steps[0].toStatus").value("DRAFT"))
                // B2-04a: no faults is an empty array, never null.
                .andExpect(jsonPath("$.data.faults").isArray())
                .andExpect(jsonPath("$.data.faults.length()").value(0));
    }

    @Test
    void detailReturnsTheFaultTimeline() throws Exception {
        var detail = new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode()
                .put("ttlSeconds", 30).put("status", "active").put("operationId", "op-1")
                .put("expiresAt", "2026-10-28T10:16:00Z").put("runStatus", "RUNNING");
        when(service.get("run-20261028-001")).thenReturn(new RunDetailView(run("RUNNING"),
                List.of(), List.of(), List.of(), List.of(),
                List.of(new FaultEventView("op-1", "container", "jl-app-3", "kill", STARTED_AT,
                        detail))));

        mvc.perform(get("/lab/runs/run-20261028-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.faults.length()").value(1))
                .andExpect(jsonPath("$.data.faults[0].operationId").value("op-1"))
                .andExpect(jsonPath("$.data.faults[0].source").value("container"))
                .andExpect(jsonPath("$.data.faults[0].target").value("jl-app-3"))
                .andExpect(jsonPath("$.data.faults[0].event").value("kill"))
                .andExpect(jsonPath("$.data.faults[0].occurredAt").value(STARTED_AT))
                .andExpect(jsonPath("$.data.faults[0].detail.status").value("active"));
    }

    @Test
    void unknownRunReturns404() throws Exception {
        when(service.get("missing")).thenThrow(new LedgerRunNotFoundException("run not found: missing"));

        mvc.perform(get("/lab/runs/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("run not found: missing"));
    }

    @Test
    void degradedLedgerAnswers503WithItsOwnCode() throws Exception {
        when(service.create(any())).thenThrow(
                new LedgerUnavailableException("ledger state is DEGRADED: connection refused"));

        mvc.perform(post("/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"spike\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"))
                .andExpect(jsonPath("$.message").value(
                        "ledger state is DEGRADED: connection refused"));
    }

    @Test
    void degradedStatusEndpointStillReportsTheState() throws Exception {
        when(service.status()).thenReturn(new LedgerStatusView("DEGRADED", "2", 2,
                "2026-10-28T10:00:00Z", "connection refused", "standalone"));

        mvc.perform(get("/lab/ledger/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DEGRADED"))
                .andExpect(jsonPath("$.data.pendingStash").value(2))
                .andExpect(jsonPath("$.data.mode").value("standalone"));
    }

    @Test
    void degradedListAlsoAnswers503() throws Exception {
        when(service.list(any(), any(), any(), any(), anyBoolean())).thenThrow(
                new LedgerUnavailableException("ledger state is DEGRADED: connection refused"));

        mvc.perform(get("/lab/runs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }

    @Test
    void recoveryListsUnknownRunsOperationsAndCleanupSuggestions() throws Exception {
        RunView run = run("UNKNOWN");
        OperationView operation = operation("op-1", OperationStatus.UNKNOWN, false);
        when(service.recovery()).thenReturn(new LedgerRecoveryView(List.of(run),
                List.of(operation), List.of("run run-20261028-001: inspect leftovers")));

        mvc.perform(get("/lab/ledger/recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unknownRuns[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.unconfirmedOperations[0].operationId").value("op-1"))
                .andExpect(jsonPath("$.data.cleanupSuggestions[0]").value(
                        "run run-20261028-001: inspect leftovers"));
    }

    // ------------------------------------------------------- B1-02 operations

    @Test
    void settledStartReturns200WithTheAppliedOperation() throws Exception {
        OperationExecution execution = execution(OperationStatus.APPLIED, false, false, null);
        when(lifecycle.start("run-20261028-001", null)).thenReturn(execution);
        when(operations.view(execution)).thenReturn(operation("op-1", OperationStatus.APPLIED, false));

        mvc.perform(post("/lab/runs/run-20261028-001/start"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("APPLIED"))
                .andExpect(jsonPath("$.data.action").value("start"))
                .andExpect(jsonPath("$.data.targetKind").value("run"));
    }

    @Test
    void slowStartAnswers202WithPendingConfirmationAndTheOperationId() throws Exception {
        OperationExecution execution = execution(OperationStatus.PENDING_CONFIRMATION, false, true, null);
        when(lifecycle.start("run-20261028-001", null)).thenReturn(execution);
        when(operations.view(execution))
                .thenReturn(operation("op-2", OperationStatus.PENDING_CONFIRMATION, false));

        mvc.perform(post("/lab/runs/run-20261028-001/start"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.operationId").value("op-2"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));
    }

    @Test
    void idempotentReplayOfStartReturns200AndTheOriginalOperation() throws Exception {
        OperationExecution execution = execution(OperationStatus.APPLIED, true, false, null);
        when(lifecycle.stop("run-20261028-001", "key-1")).thenReturn(execution);
        when(operations.view(execution)).thenReturn(operation("op-3", OperationStatus.APPLIED, true));

        mvc.perform(post("/lab/runs/run-20261028-001/stop").header("Idempotency-Key", "key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("op-3"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));
    }

    @Test
    void invalidIdempotencyKeyIsRejectedBeforeTheServiceRuns() throws Exception {
        mvc.perform(post("/lab/runs/run-20261028-001/start")
                        .header("Idempotency-Key", "bad key with spaces"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("Idempotency-Key")));

        verify(lifecycle, never()).start(any(), any());
    }

    @Test
    void lockConflictAnswers409With40902AndNamesTheHolder() throws Exception {
        when(lifecycle.start("run-20261028-002", null)).thenThrow(new EnvLockConflictException(
                "write-run lock env:write-run is held by run run-20261028-001 until 2026-10-28T10:45:30Z;"
                        + " release it with POST /lab/runs/run-20261028-001/stop, or wait for the TTL to expire"));

        mvc.perform(post("/lab/runs/run-20261028-002/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40902"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("run-20261028-001")));
    }

    @Test
    void illegalStartStateAnswers409WithTheGenericConflictCode() throws Exception {
        when(lifecycle.start("run-20261028-001", null))
                .thenThrow(new com.javalearn.lab.chaos.error.LedgerConflictException(
                        "run is RUNNING; cannot start (needs a transition to VALIDATING)"));

        mvc.perform(post("/lab/runs/run-20261028-001/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("RUNNING")));
    }

    @Test
    void aBlockedPrecheckAnswers409With40903AndTheBlockingReport() throws Exception {
        PrecheckReport report = PrecheckReport.of("run-20261028-001", STARTED_AT,
                List.of(PrecheckItem.block("traffic.stopped",
                        "background traffic jl-traffic is running",
                        java.util.Map.of("container", "jl-traffic"))), 10);
        when(lifecycle.start("run-20261028-001", null))
                .thenThrow(new PrecheckBlockedException(report));

        mvc.perform(post("/lab/runs/run-20261028-001/start"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40903"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers
                        .containsString("traffic.stopped")))
                .andExpect(jsonPath("$.data.verdict").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blocking[0].key").value("traffic.stopped"))
                .andExpect(jsonPath("$.data.blocking[0].detail.container").value("jl-traffic"));
    }

    @Test
    void operationDetailReturnsBeforeAndAfterSummaries() throws Exception {
        when(operations.view("op-9")).thenReturn(operation("op-9", OperationStatus.APPLIED, false));

        mvc.perform(get("/lab/operations/op-9"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.operationId").value("op-9"))
                .andExpect(jsonPath("$.data.operator").value("local"))
                .andExpect(jsonPath("$.data.beforeSummary.runStatus").value("DRAFT"))
                .andExpect(jsonPath("$.data.afterSummary.runStatus").value("RUNNING"));
    }

    @Test
    void unknownOperationReturns404() throws Exception {
        when(operations.view("missing"))
                .thenThrow(new OperationNotFoundException("operation not found: missing"));

        mvc.perform(get("/lab/operations/missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    // ------------------------------------------------ B2-04a UNKNOWN closure

    @Test
    void closingUnknownReturnsTheClosedOperationView() throws Exception {
        OperationCloseRequest request = new OperationCloseRequest("RESTORED", "target verified");
        when(operations.closeUnknown("op-1", request))
                .thenReturn(operation("op-1", OperationStatus.RESTORED, false));

        mvc.perform(post("/lab/operations/op-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESTORED\",\"note\":\"target verified\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("RESTORED"));

        verify(operations).closeUnknown("op-1", request);
    }

    @Test
    void closingASettledOperationAnswers409With40905() throws Exception {
        when(operations.closeUnknown("op-1", new OperationCloseRequest("RESTORED", null)))
                .thenThrow(new OperationCloseConflictException(
                        "operation op-1 is APPLIED; only UNKNOWN operations can be closed as RESTORED"));

        mvc.perform(post("/lab/operations/op-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"RESTORED\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40905"))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("APPLIED")));
    }

    @Test
    void closingAMissingOperationAnswers404() throws Exception {
        when(operations.closeUnknown("missing", new OperationCloseRequest("FAILED", null)))
                .thenThrow(new OperationNotFoundException("operation not found: missing"));

        mvc.perform(post("/lab/operations/missing/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FAILED\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void anInvalidCloseStatusOrNoteAnswers400() throws Exception {
        when(operations.closeUnknown(any(), any())).thenThrow(new InvalidLedgerRequestException(
                "status must be RESTORED or FAILED"));

        mvc.perform(post("/lab/operations/op-1/close")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"APPLIED\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void operationListPassesRunStatusAndPagingFilters() throws Exception {
        when(operations.page("run-20261028-001", "APPLIED", 10, 0))
                .thenReturn(new OperationPageView(
                        List.of(operation("op-1", OperationStatus.APPLIED, false)), 1, 10, 0));

        mvc.perform(get("/lab/operations")
                        .param("runId", "run-20261028-001")
                        .param("status", "APPLIED")
                        .param("limit", "10")
                        .param("offset", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].status").value("APPLIED"));
    }

    @Test
    void degradedOperationQueryAnswers503() throws Exception {
        when(operations.view("op-1")).thenThrow(
                new LedgerUnavailableException("ledger state is DEGRADED: connection refused"));

        mvc.perform(get("/lab/operations/op-1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }

    private static RunView run(String status) {
        return new RunView("run-20261028-001", "spike", "冒烟基线", List.of("app-1", "app-2"),
                status, "abc", "local", STARTED_AT, STARTED_AT, null, null, false);
    }

    private static OperationExecution execution(OperationStatus status, boolean replay, boolean pending,
                                                Object payload) {
        OperationRecord record = new OperationRecord("op-" + status.name().toLowerCase(),
                "run-20261028-001", "run", "run-20261028-001", "start", status, 1800,
                Instant.parse(STARTED_AT), null, null, null, "local", "{\"runStatus\":\"DRAFT\"}", null);
        return new OperationExecution(record, replay, pending, payload);
    }

    private static OperationView operation(String operationId, OperationStatus status, boolean replay) {
        return new OperationView(operationId, "run-20261028-001", "run", "run-20261028-001", "start",
                status.name(), 1800, STARTED_AT, "2026-10-28T10:15:31Z", null, null, "local",
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("runStatus", "DRAFT"),
                new com.fasterxml.jackson.databind.ObjectMapper().createObjectNode().put("runStatus", "RUNNING"),
                replay);
    }
}
