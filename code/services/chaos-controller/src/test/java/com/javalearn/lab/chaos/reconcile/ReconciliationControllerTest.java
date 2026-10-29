package com.javalearn.lab.chaos.reconcile;

import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.ReconciliationNotFoundException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing and 404 contract for POST/GET reconciliation. */
class ReconciliationControllerTest {

    private static final String RUN = "run-20261030-001";
    private ReconciliationService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(ReconciliationService.class);
        mvc = MockMvcBuilders.standaloneSetup(new ReconciliationController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void postReconcileReturnsItemsAndVerdict() throws Exception {
        when(service.reconcile(RUN)).thenReturn(result("PASS"));

        mvc.perform(post("/lab/runs/{runId}/reconcile", RUN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.runId").value(RUN))
                .andExpect(jsonPath("$.data.verdict").value("PASS"))
                .andExpect(jsonPath("$.data.items[0].key").value("orders.delta"))
                .andExpect(jsonPath("$.data.items[0].sample[0]").value("p1=89"));
    }

    @Test
    void getReconciliationReturnsTheLastPersistedResult() throws Exception {
        when(service.latest(RUN)).thenReturn(result("DEGRADED"));

        mvc.perform(get("/lab/runs/{runId}/reconciliation", RUN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.verdict").value("DEGRADED"))
                .andExpect(jsonPath("$.data.checkedAt").value("2026-10-30T12:00:00Z"));
    }

    @Test
    void getWithoutAnyResultIs404NoData() throws Exception {
        when(service.latest(RUN)).thenThrow(new ReconciliationNotFoundException(
                "NO_DATA: no reconciliation has been run for " + RUN));

        mvc.perform(get("/lab/runs/{runId}/reconciliation", RUN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(containsString("NO_DATA")));
    }

    @Test
    void reconcileUnknownRunIs404() throws Exception {
        when(service.reconcile("missing")).thenThrow(
                new LedgerRunNotFoundException("run not found: missing"));

        mvc.perform(post("/lab/runs/{runId}/reconcile", "missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(containsString("run not found")));
    }

    private static ReconcileResult result(String verdict) {
        return new ReconcileResult(RUN, "2026-10-30T12:00:00Z", verdict,
                List.of(new ReconcileItem("orders.delta", "delta >= 0", "ordersDelta=5",
                        verdict, "baseline.count=10", List.of("p1=89"))));
    }
}
