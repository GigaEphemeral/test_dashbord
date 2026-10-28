package com.javalearn.lab.chaos.precheck;

import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Map;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code GET /lab/runs/{runId}/precheck}: a BLOCKED verdict is
 * still 200 (the report is data), an unknown run is 404 and a degraded ledger
 * is 503/50302. Verdict computation is covered by {@link RunPrecheckServiceTest}.
 */
class PrecheckControllerTest {

    private RunPrecheckService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(RunPrecheckService.class);
        mvc = MockMvcBuilders.standaloneSetup(new PrecheckController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void aBlockedReportStillAnswers200WithTheBlockingEvidence() throws Exception {
        when(service.inspectReport("run-1")).thenReturn(PrecheckReport.of("run-1",
                "2026-10-28T10:15:30Z", List.of(
                        PrecheckItem.block("traffic.stopped",
                                "background traffic jl-traffic is running",
                                Map.of("container", "jl-traffic")),
                        PrecheckItem.pass("chaos.clean", "no chaos action is tracked", Map.of())), 10));

        mvc.perform(get("/lab/runs/run-1/precheck"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.verdict").value("BLOCKED"))
                .andExpect(jsonPath("$.data.blocking[0].key").value("traffic.stopped"))
                .andExpect(jsonPath("$.data.blocking[0].status").value("BLOCK"))
                .andExpect(jsonPath("$.data.blocking[0].detail.container").value("jl-traffic"))
                .andExpect(jsonPath("$.data.passed[0].key").value("chaos.clean"))
                .andExpect(jsonPath("$.data.items.length()").value(2));
    }

    @Test
    void anUnknownRunIs404() throws Exception {
        when(service.inspectReport("missing"))
                .thenThrow(new LedgerRunNotFoundException("run not found: missing"));

        mvc.perform(get("/lab/runs/missing/precheck"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value("run not found: missing"));
    }

    @Test
    void aDegradedLedgerIs503WithItsOwnCode() throws Exception {
        when(service.inspectReport("run-1")).thenThrow(
                new LedgerUnavailableException("ledger state is DEGRADED: connection refused"));

        mvc.perform(get("/lab/runs/run-1/precheck"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }
}
