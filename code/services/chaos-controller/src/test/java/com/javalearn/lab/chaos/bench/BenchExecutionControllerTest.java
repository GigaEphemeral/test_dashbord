package com.javalearn.lab.chaos.bench;

import com.javalearn.lab.chaos.bench.exec.BenchExecutionState;
import com.javalearn.lab.chaos.bench.exec.BenchReportContent;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.K6ContainerGateway;
import com.javalearn.lab.chaos.bench.exec.BenchLaunchRejectedException;
import com.javalearn.lab.chaos.error.BenchReportNotFoundException;
import com.javalearn.lab.chaos.error.BenchReportTooLargeException;
import com.javalearn.lab.chaos.error.BenchRunConflictException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.nio.charset.StandardCharsets;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the read-only k6 run endpoints (Stage2 B2-02b-1): Result
 * envelope, absent state as 200, logs shape, raw report download, and the
 * 400/404/409/413/503 mappings.
 */
class BenchExecutionControllerTest {

    private static final String RUN_ID = "run-20261028-001";

    private BenchRunExecutor executor;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        executor = mock(BenchRunExecutor.class);
        mvc = MockMvcBuilders.standaloneSetup(new BenchExecutionController(executor))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void absentContainerIsANormalTwoHundredAnswer() throws Exception {
        when(executor.state(RUN_ID)).thenReturn(new BenchExecutionState("ABSENT",
                "jl-k6-" + RUN_ID, null, null, null, null, null, false, 0L,
                "no k6 container has been created for this run"));

        mvc.perform(get("/lab/runs/{runId}/k6", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.status").value("ABSENT"))
                .andExpect(jsonPath("$.data.containerName").value("jl-k6-" + RUN_ID))
                .andExpect(jsonPath("$.data.reportAvailable").value(false))
                .andExpect(jsonPath("$.data.reportBytes").value(0))
                .andExpect(jsonPath("$.data.note").value(containsString("no k6 container")));
    }

    @Test
    void stateReturnsTheFullContract() throws Exception {
        when(executor.state(RUN_ID)).thenReturn(new BenchExecutionState("EXITED",
                "jl-k6-" + RUN_ID, "abc123", "smoke", "target-instance", "app-2", 0,
                "2026-10-28T10:00:00Z", "2026-10-28T10:05:00Z", true, 42L, null));

        mvc.perform(get("/lab/runs/{runId}/k6", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("EXITED"))
                .andExpect(jsonPath("$.data.containerId").value("abc123"))
                .andExpect(jsonPath("$.data.scenario").value("smoke"))
                .andExpect(jsonPath("$.data.trafficMode").value("target-instance"))
                .andExpect(jsonPath("$.data.targetInstance").value("app-2"))
                .andExpect(jsonPath("$.data.exitCode").value(0))
                .andExpect(jsonPath("$.data.startedAt").value("2026-10-28T10:00:00Z"))
                .andExpect(jsonPath("$.data.finishedAt").value("2026-10-28T10:05:00Z"))
                .andExpect(jsonPath("$.data.reportAvailable").value(true))
                .andExpect(jsonPath("$.data.reportBytes").value(42));
    }

    @Test
    void logsForwardTheQueryAndReturnTextTruncatedBytes() throws Exception {
        when(executor.logs(RUN_ID, 50, 1024)).thenReturn(
                new K6ContainerGateway.LogChunk("hello", true, 5, 2048));

        mvc.perform(get("/lab/runs/{runId}/k6/logs", RUN_ID)
                        .param("tail", "50").param("maxBytes", "1024"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("hello"))
                .andExpect(jsonPath("$.data.truncated").value(true))
                .andExpect(jsonPath("$.data.bytes").value(5));
    }

    @Test
    void logsWithoutParametersPassNullsSoTheExecutorAppliesDefaults() throws Exception {
        when(executor.logs(RUN_ID, null, null)).thenReturn(
                new K6ContainerGateway.LogChunk("tail", false, 4, 4));

        mvc.perform(get("/lab/runs/{runId}/k6/logs", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.text").value("tail"));
    }

    @Test
    void reportIsRawJsonWithAnAttachmentHeader() throws Exception {
        byte[] body = "{\"metrics\":{}}".getBytes(StandardCharsets.UTF_8);
        when(executor.report(RUN_ID)).thenReturn(
                new BenchReportContent(RUN_ID + "-summary.json", body));

        mvc.perform(get("/lab/runs/{runId}/k6/report", RUN_ID))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string(HttpHeaders.CONTENT_DISPOSITION,
                        containsString("attachment")))
                .andExpect(content().bytes(body));
    }

    @Test
    void missingReportIs404() throws Exception {
        when(executor.report(RUN_ID)).thenThrow(
                new BenchReportNotFoundException("summary report for run is not available"));

        mvc.perform(get("/lab/runs/{runId}/k6/report", RUN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void oversizedReportIs413WithItsOwnCode() throws Exception {
        when(executor.report(RUN_ID)).thenThrow(
                new BenchReportTooLargeException("summary report exceeds the cap"));

        mvc.perform(get("/lab/runs/{runId}/k6/report", RUN_ID))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.code").value("41301"));
    }

    @Test
    void launchRejectionIs400WithItsOwnCode() throws Exception {
        when(executor.state(RUN_ID)).thenThrow(
                new BenchLaunchRejectedException("runId '../x' is not a safe segment"));

        mvc.perform(get("/lab/runs/{runId}/k6", RUN_ID))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40002"));
    }

    @Test
    void sameNameLiveContainerIs409WithItsOwnCode() throws Exception {
        when(executor.state(any())).thenThrow(
                new BenchRunConflictException("k6 container is still RUNNING"));

        mvc.perform(get("/lab/runs/{runId}/k6", RUN_ID))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40904"))
                .andExpect(jsonPath("$.message").value(containsString("RUNNING")));
    }

    @Test
    void unavailableExecutorIs503WithTheBenchCode() throws Exception {
        when(executor.state(any())).thenThrow(
                new BenchUnavailableException("k6 image is not present locally"));

        mvc.perform(get("/lab/runs/{runId}/k6", RUN_ID))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50303"));
    }

    @Test
    void missingLogsContainerIs404() throws Exception {
        when(executor.logs(eq(RUN_ID), any(), any())).thenThrow(
                new com.javalearn.lab.chaos.error.ContainerNotFoundException("not found"));

        mvc.perform(get("/lab/runs/{runId}/k6/logs", RUN_ID))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void nonNumericTailIs400() throws Exception {
        mvc.perform(get("/lab/runs/{runId}/k6/logs", RUN_ID).param("tail", "many"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }
}
