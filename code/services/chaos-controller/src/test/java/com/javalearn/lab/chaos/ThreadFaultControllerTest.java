package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
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
import com.javalearn.lab.chaos.web.ThreadFaultController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.stubbing.Answer;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2-04b2: the unified thread-fault entry forwards to the fixed app paths under
 * one operation per call. The app proxy and operation service are mocked so the
 * forwarded method/path/body, the whitelist refusals, the raw relay and the
 * timeline events are asserted without Docker or a database.
 */
@WebMvcTest(controllers = ThreadFaultController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class ThreadFaultControllerTest {

    private static final String OP_HEADER = "X-Operation-Id";
    private static final String APP_STATE =
            "{\"code\":\"0\",\"message\":\"OK\",\"data\":[{\"mode\":\"cpu-burn\"}]}";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private AppProxyService appProxyService;

    @MockitoBean
    private ChaosProperties properties;

    @MockitoBean
    private OperationService operations;

    @MockitoBean
    private FaultEventService faultEvents;

    @BeforeEach
    void setUp() {
        ChaosProperties.AppProxy appProxy = new ChaosProperties.AppProxy();
        appProxy.setInstances(List.of("app-1", "app-2", "app-3"));
        when(properties.getAppProxy()).thenReturn(appProxy);
        when(appProxyService.maxRequestBytes()).thenReturn(8192);

        Answer<OperationExecution> settle = invocation -> {
            OperationWork work = invocation.getArgument(1);
            OperationOutcome outcome = OperationContext.withOperation("op-1", work::run);
            return new OperationExecution(operation(OperationStatus.APPLIED), false, false,
                    outcome.payload());
        };
        doAnswer(settle).when(operations).submit(any(), any());
    }

    // ----------------------------------------------------------- /fault body

    @Test
    void faultRelaysTheRawBodyToTheAppFaultPath() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));
        String body = "{\"mode\":\"cpu-burn\",\"hold\":\"auto\",\"ttlSeconds\":5}";

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault")
                        .header("Idempotency-Key", "thread-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(content().string(APP_STATE));

        ArgumentCaptor<byte[]> forwarded = ArgumentCaptor.forClass(byte[].class);
        verify(appProxyService).forward(eq("app-1"), eq("POST"),
                eq("/lab/thread/fault"), eq((String) null), forwarded.capture());
        assertThat(new String(forwarded.getValue(), StandardCharsets.UTF_8)).isEqualTo(body);

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        assertThat(request.getValue().runId()).isNull();
        assertThat(request.getValue().targetKind()).isEqualTo("thread");
        assertThat(request.getValue().targetName()).isEqualTo("app-1");
        assertThat(request.getValue().action()).isEqualTo("thread-fault");
        assertThat(request.getValue().idempotencyKey()).isEqualTo("thread-key-1");
        assertThat(request.getValue().strictLedger()).isFalse();
    }

    // ------------------------------------------------------ /fault/{mode}/…

    @Test
    void modeActionForwardsToTheAppPathWithoutABody() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-2/thread/fault/deadlock/stop"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(content().string(APP_STATE));

        verify(appProxyService).forward(eq("app-2"), eq("POST"),
                eq("/lab/thread/fault/deadlock/stop"), eq((String) null), eq((byte[]) null));
    }

    @Test
    void pauseAndResumeAlsoForward() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-3/thread/fault/starvation/pause"))
                .andExpect(status().isAccepted());
        mockMvc.perform(post("/chaos/instances/app-3/thread/fault/starvation/resume"))
                .andExpect(status().isAccepted());

        verify(appProxyService).forward(eq("app-3"), eq("POST"),
                eq("/lab/thread/fault/starvation/pause"), eq((String) null), eq((byte[]) null));
        verify(appProxyService).forward(eq("app-3"), eq("POST"),
                eq("/lab/thread/fault/starvation/resume"), eq((String) null), eq((byte[]) null));
    }

    // ---------------------------------------------------------- /stop-all

    @Test
    void stopAllForwardsAndSettlesRestored() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        AtomicReference<OperationOutcome> captured = new AtomicReference<>();
        doAnswer(invocation -> {
            OperationWork work = invocation.getArgument(1);
            OperationOutcome outcome = OperationContext.withOperation("op-1", work::run);
            captured.set(outcome);
            return new OperationExecution(operation(OperationStatus.RESTORED), false, false,
                    outcome.payload());
        }).when(operations).submit(any(), any());

        mockMvc.perform(post("/chaos/instances/app-1/thread/stop-all"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(content().string(APP_STATE));

        verify(appProxyService).forward(eq("app-1"), eq("POST"),
                eq("/lab/thread/stop-all"), eq((String) null), eq((byte[]) null));
        assertThat(captured.get().restored()).isTrue();
    }

    // -------------------------------------------------------- whitelist

    @Test
    void anUnknownInstanceIs404BeforeAnyOperation() throws Exception {
        mockMvc.perform(post("/chaos/instances/app-9/thread/stop-all"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("app-9")));

        verify(operations, never()).submit(any(), any());
        verify(appProxyService, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void anUnknownModeIs400BeforeAnyOperation() throws Exception {
        mockMvc.perform(post("/chaos/instances/app-1/thread/fault/explode/stop"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("mode")));

        verify(operations, never()).submit(any(), any());
    }

    @Test
    void anUnknownActionIs400BeforeAnyOperation() throws Exception {
        mockMvc.perform(post("/chaos/instances/app-1/thread/fault/deadlock/restart"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("action")));

        verify(operations, never()).submit(any(), any());
    }

    // ------------------------------------------------------------ timeline

    @Test
    void aSuccessfulModeActionWritesTheDocumentedEvent() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault/deadlock/stop")
                        .param("runId", "run-1"))
                .andExpect(status().isAccepted());

        verify(faultEvents).recordEvent(eq("run-1"), eq("op-1"), eq("thread"),
                eq("app-1"), eq("thread-fault-deadlock-stop"), anyMap(), eq("RUNNING"));
    }

    @Test
    void stopAllWritesItsOwnEvent() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-2/thread/stop-all")
                        .param("runId", "run-1"))
                .andExpect(status().isAccepted());

        verify(faultEvents).recordEvent(eq("run-1"), eq("op-1"), eq("thread"),
                eq("app-2"), eq("thread-stop-all"), anyMap(), eq("RUNNING"));
    }

    @Test
    void withoutARunIdNothingIsValidatedOrRecorded() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault"))
                .andExpect(status().isAccepted());

        verify(faultEvents, never()).validateRun(any());
        verify(faultEvents, never()).recordEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void anUnknownRunIdIs404BeforeForwarding() throws Exception {
        when(faultEvents.validateRun("run-missing")).thenThrow(
                new LedgerRunNotFoundException("run not found: run-missing"));

        mockMvc.perform(post("/chaos/instances/app-1/thread/stop-all")
                        .param("runId", "run-missing"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));

        verify(operations, never()).submit(any(), any());
        verify(appProxyService, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void aSkippedTimelineWriteKeepsTheForwardedAnswer() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(faultEvents.recordEvent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(200, APP_STATE));

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault/deadlock/pause")
                        .param("runId", "run-1"))
                .andExpect(status().isAccepted())
                .andExpect(content().string(APP_STATE));
    }

    // ------------------------------------------------------ app failure

    @Test
    void aNon2xxAppAnswerFailsTheOperationAndRecordsNothing() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenReturn(new ProxyResponse(500,
                        "{\"code\":\"50000\",\"message\":\"boom\"}"));

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault/deadlock/stop")
                        .param("runId", "run-1"))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.code").value("50201"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("app-1")))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("500")));

        // The work threw, so the real OperationService settles the row FAILED and
        // rethrows; no injection event may be written.
        verify(faultEvents, never()).recordEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void anUnreachableAppIsReportedAsAServiceError() throws Exception {
        when(appProxyService.forward(any(), any(), any(), any(), any()))
                .thenThrow(new com.javalearn.lab.chaos.error.AppUnreachableException(
                        "app-1 is unreachable at http://app-1:8080"));

        mockMvc.perform(post("/chaos/instances/app-1/thread/fault"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"));
    }

    // ---------------------------------------------------- pending / replay

    @Test
    void aPendingOperationAnswers202WithTheOperationView() throws Exception {
        OperationExecution pending = new OperationExecution(
                operation(OperationStatus.PENDING_CONFIRMATION), false, true, null);
        doReturn(pending).when(operations).submit(any(), any());
        when(operations.view(pending)).thenReturn(operationView(
                OperationStatus.PENDING_CONFIRMATION, false));

        mockMvc.perform(post("/chaos/instances/app-1/thread/stop-all"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));

        verify(appProxyService, never()).forward(any(), any(), any(), any(), any());
    }

    @Test
    void anIdempotentReplayReturns200WithoutForwarding() throws Exception {
        OperationExecution replay = new OperationExecution(operation(OperationStatus.APPLIED),
                true, false, null);
        doReturn(replay).when(operations).submit(any(), any());
        when(operations.view(replay)).thenReturn(operationView(OperationStatus.APPLIED, true));

        mockMvc.perform(post("/chaos/instances/app-1/thread/stop-all")
                        .header("Idempotency-Key", "thread-key-1"))
                .andExpect(status().isOk())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        verify(appProxyService, never()).forward(any(), any(), any(), any(), any());
    }

    private static OperationRecord operation(OperationStatus status) {
        return new OperationRecord("op-1", null, "thread", "app-1", "thread-fault", status,
                null, Instant.parse("2026-10-26T10:00:00Z"),
                Instant.parse("2026-10-26T10:00:01Z"), null, null, "local", null, null);
    }

    private static LedgerViews.OperationView operationView(OperationStatus status, boolean replay) {
        return new LedgerViews.OperationView("op-1", null, "thread", "app-1",
                "thread-fault", status.name(), null, "2026-10-26T10:00:00Z", null, null, null,
                "local", null, null, replay);
    }
}
