package com.javalearn.lab.chaos.report;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.ledger.LedgerViews.RunCreateView;
import com.javalearn.lab.chaos.ledger.RunLedgerService;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class RunReportControllerTest {

    private static final String RUN_ID = "run-1";

    private RunReportService reports;
    private RunLedgerService ledger;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        reports = mock(RunReportService.class);
        ledger = mock(RunLedgerService.class);
        mvc = MockMvcBuilders.standaloneSetup(
                        new RunReportController(reports, ledger, new ObjectMapper()))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    private static RunReportViews.RunReport sample(String runId) {
        return new RunReportViews.RunReport(1, "2026-10-29T10:00:00Z", false,
                "单次结果为演示性数据，未重复验证", null, List.of(), null, null, null, null);
    }

    @Test
    void reportIsTheInlineJson() throws Exception {
        when(reports.report(RUN_ID)).thenReturn(sample(RUN_ID));

        mvc.perform(get("/lab/runs/{runId}/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.reportVersion").value(1))
                .andExpect(jsonPath("$.data.repeatVerified").value(false));
    }

    @Test
    void exportIsAnAttachmentWithTheRunInTheFilename() throws Exception {
        when(reports.report(RUN_ID)).thenReturn(sample(RUN_ID));

        mvc.perform(get("/lab/runs/{runId}/export", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(result -> {
                    String disposition = result.getResponse().getHeader("Content-Disposition");
                    if (disposition == null || !disposition.contains("run-run-1-report.json")) {
                        throw new AssertionError("unexpected Content-Disposition: " + disposition);
                    }
                });
    }

    @Test
    void cloneCreatesANewDraft() throws Exception {
        when(ledger.clone(RUN_ID)).thenReturn(new RunCreateView("run-2", "DRAFT",
                "2026-10-29T10:01:00Z"));

        mvc.perform(post("/lab/runs/{runId}/clone", RUN_ID))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.runId").value("run-2"));
    }

    @Test
    void cloneOfAMissingRunIs404() throws Exception {
        when(ledger.clone(any())).thenThrow(new LedgerRunNotFoundException("run not found"));

        mvc.perform(post("/lab/runs/{runId}/clone", RUN_ID))
                .andExpect(status().isNotFound());
    }
}
