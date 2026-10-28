package com.javalearn.lab.chaos.ledger;

import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerRecoveryView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.LedgerStatusView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.OperationView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunDetailView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunPageView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.RunView;
import static com.javalearn.lab.chaos.ledger.LedgerViews.StepView;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract: 201 create, paged list, detail, 404, and 503/50302 when the
 * ledger is DEGRADED. The service is stubbed here because this test owns the
 * routing/envelope/status mapping; the database behaviour lives in
 * LedgerSchemaIT/LedgerStashTest/LedgerRecoveryTest.
 */
class LedgerControllerTest {

    private RunLedgerService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RunLedgerService.class);
        mvc = MockMvcBuilders.standaloneSetup(new LedgerController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void createReturns201WithTheDraftRun() throws Exception {
        when(service.create(any())).thenReturn(new RunCreateView("run-20261028-001", "DRAFT",
                "2026-10-28T10:15:30.123Z"));

        mvc.perform(post("/lab/runs")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenario\":\"spike\",\"params\":{\"vus\":10}}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.runId").value("run-20261028-001"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-10-28T10:15:30.123Z"));
    }

    @Test
    void listPassesFiltersAndPagingAndReturnsTheTotal() throws Exception {
        RunView run = new RunView("run-20261028-001", "spike", "DRAFT", "abc", "local",
                "2026-10-28T10:15:30.123Z", "2026-10-28T10:15:30.123Z", null, null);
        when(service.list("spike", "DRAFT", 10, 5))
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

        verify(service).list("spike", "DRAFT", 10, 5);
    }

    @Test
    void detailReturnsRunStepsEnvironmentParamsAndChecks() throws Exception {
        RunView run = new RunView("run-20261028-001", "spike", "DRAFT", "abc", "local",
                "2026-10-28T10:15:30.123Z", "2026-10-28T10:15:30.123Z", null, null);
        when(service.get("run-20261028-001")).thenReturn(new RunDetailView(run,
                List.of(new StepView("create", null, "DRAFT", "2026-10-28T10:15:30.123Z",
                        null, null)),
                List.of(), List.of(), List.of()));

        mvc.perform(get("/lab/runs/run-20261028-001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.run.runId").value("run-20261028-001"))
                .andExpect(jsonPath("$.data.steps[0].stepKey").value("create"))
                .andExpect(jsonPath("$.data.steps[0].toStatus").value("DRAFT"));
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
        when(service.status()).thenReturn(new LedgerStatusView("DEGRADED", "1", 2,
                "2026-10-28T10:00:00Z", "connection refused", "standalone"));

        mvc.perform(get("/lab/ledger/status"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.state").value("DEGRADED"))
                .andExpect(jsonPath("$.data.pendingStash").value(2))
                .andExpect(jsonPath("$.data.mode").value("standalone"));
    }

    @Test
    void degradedListAlsoAnswers503() throws Exception {
        when(service.list(any(), any(), any(), any())).thenThrow(
                new LedgerUnavailableException("ledger state is DEGRADED: connection refused"));

        mvc.perform(get("/lab/runs"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }

    @Test
    void recoveryListsUnknownRunsOperationsAndCleanupSuggestions() throws Exception {
        RunView run = new RunView("run-20261028-001", "spike", "UNKNOWN", "abc", "local",
                "2026-10-28T10:15:30.123Z", "2026-10-28T10:16:00Z", null, null);
        OperationView operation = new OperationView("op-1", "run-20261028-001", "container",
                "jl-app-1", "kill", "UNKNOWN", 60, "2026-10-28T10:15:31Z", null, null,
                "unconfirmed after controller restart");
        when(service.recovery()).thenReturn(new LedgerRecoveryView(List.of(run),
                List.of(operation), List.of("run run-20261028-001: inspect leftovers")));

        mvc.perform(get("/lab/ledger/recovery"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.unknownRuns[0].status").value("UNKNOWN"))
                .andExpect(jsonPath("$.data.unconfirmedOperations[0].operationId").value("op-1"))
                .andExpect(jsonPath("$.data.cleanupSuggestions[0]").value(
                        "run run-20261028-001: inspect leftovers"));
    }
}
