package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.error.InvalidLedgerRequestException;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
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
import com.javalearn.lab.chaos.toxiproxy.ProxyEnabledView;
import com.javalearn.lab.chaos.toxiproxy.ProxyResetView;
import com.javalearn.lab.chaos.toxiproxy.ProxyStateView;
import com.javalearn.lab.chaos.toxiproxy.ToxicRequest;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.chaos.web.ToxiproxyController;
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

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B2-04b2: every Toxiproxy write is one operation. The tests stub
 * {@link OperationService#submit} to run the work inline and return a settled
 * execution - exactly what the service does inside its sync budget - so the
 * original view shapes, the operation id header, the timeline events and the
 * error mapping are all exercised without a database.
 */
@WebMvcTest(controllers = ToxiproxyController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class ToxiproxyControllerTest {

    private static final String OP_HEADER = "X-Operation-Id";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToxiproxyService toxiproxyService;

    @MockitoBean
    private OperationService operations;

    @MockitoBean
    private FaultEventService faultEvents;

    private static final ToxicView LATENCY = new ToxicView("latency", "latency",
            Map.of("latency", 500, "jitter", 50), 1.0);

    @BeforeEach
    void setUp() {
        // doAnswer never invokes the stubbed method while recording, so a later
        // override (pending/replay) cannot accidentally run a null work.
        Answer<OperationExecution> settle = invocation -> {
            OperationWork work = invocation.getArgument(1);
            OperationOutcome outcome = OperationContext.withOperation("op-1", work::run);
            return new OperationExecution(operation(OperationStatus.APPLIED), false, false,
                    outcome.payload());
        };
        doAnswer(settle).when(operations).submit(any(), any());
    }

    // ------------------------------------------------------------ add toxic

    @Test
    void addToxicSubmitsOneOperationAndReturns202WithTheViewAndOperationIdHeader()
            throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any(ToxicRequest.class))).thenReturn(LATENCY);

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .header("Idempotency-Key", "toxic-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\",\"name\":\"latency\","
                                + "\"params\":{\"latencyMs\":500,\"jitterMs\":50}}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("latency"))
                .andExpect(jsonPath("$.data.type").value("latency"))
                .andExpect(jsonPath("$.data.attributes.latency").value(500))
                .andExpect(jsonPath("$.data.toxicity").value(1.0));

        ArgumentCaptor<ToxicRequest> toxic = ArgumentCaptor.forClass(ToxicRequest.class);
        verify(toxiproxyService).addToxic(eq("redis"), toxic.capture());
        assertThat(toxic.getValue().type()).isEqualTo("latency");
        assertThat(toxic.getValue().params()).containsEntry("latencyMs", 500);

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        assertThat(request.getValue().runId()).isNull();
        assertThat(request.getValue().targetKind()).isEqualTo("toxiproxy");
        assertThat(request.getValue().targetName()).isEqualTo("redis");
        assertThat(request.getValue().action()).isEqualTo("toxiproxy-add");
        assertThat(request.getValue().ttlSeconds()).isNull();
        assertThat(request.getValue().idempotencyKey()).isEqualTo("toxic-key-1");
        assertThat(request.getValue().strictLedger()).isFalse();
    }

    @Test
    void aReplayReturns200WithTheStoredOperationAndDoesNotCallTheService() throws Exception {
        OperationExecution replay = new OperationExecution(operation(OperationStatus.APPLIED),
                true, false, null);
        doReturn(replay).when(operations).submit(any(), any());
        when(operations.view(replay)).thenReturn(operationView(OperationStatus.APPLIED, true));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .header("Idempotency-Key", "toxic-key-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isOk())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.idempotentReplay").value(true));

        verify(toxiproxyService, never()).addToxic(any(), any());
    }

    @Test
    void aPendingOperationAnswers202WithTheOperationView() throws Exception {
        OperationExecution pending = new OperationExecution(
                operation(OperationStatus.PENDING_CONFIRMATION), false, true, null);
        doReturn(pending).when(operations).submit(any(), any());
        when(operations.view(pending)).thenReturn(operationView(
                OperationStatus.PENDING_CONFIRMATION, false));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.operationId").value("op-1"))
                .andExpect(jsonPath("$.data.status").value("PENDING_CONFIRMATION"));

        verify(toxiproxyService, never()).addToxic(any(), any());
    }

    @Test
    void aRunIdJoinsTheTimelineWithTheDocumentedEvent() throws Exception {
        when(faultEvents.validateRun("run-20261028-001")).thenReturn("RUNNING");
        when(toxiproxyService.addToxic(eq("redis"), any(ToxicRequest.class))).thenReturn(LATENCY);

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .param("runId", "run-20261028-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"));

        verify(faultEvents).recordEvent(eq("run-20261028-001"), eq("op-1"),
                eq("toxiproxy"), eq("redis"), eq("toxiproxy-add"), anyMap(), eq("RUNNING"));
    }

    @Test
    void withoutARunIdNothingIsValidatedOrRecorded() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any(ToxicRequest.class))).thenReturn(LATENCY);

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isAccepted());

        verify(faultEvents, never()).validateRun(any());
        verify(faultEvents, never()).recordEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void anUnknownRunIdAnswers404BeforeTheActionRuns() throws Exception {
        when(faultEvents.validateRun("run-missing")).thenThrow(
                new LedgerRunNotFoundException("run not found: run-missing"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .param("runId", "run-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));

        verify(operations, never()).submit(any(), any());
        verify(toxiproxyService, never()).addToxic(any(), any());
    }

    @Test
    void aMalformedRunIdAnswers400BeforeTheActionRuns() throws Exception {
        when(faultEvents.validateRun("run/../etc")).thenThrow(
                new InvalidLedgerRequestException("runId must match ..."));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .param("runId", "run/../etc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(operations, never()).submit(any(), any());
    }

    @Test
    void aSkippedTimelineWriteKeepsTheAppliedActionResult() throws Exception {
        when(faultEvents.validateRun("run-20261028-001")).thenReturn("RUNNING");
        when(faultEvents.recordEvent(any(), any(), any(), any(), any(), any(), any()))
                .thenReturn(false);
        when(toxiproxyService.addToxic(eq("redis"), any(ToxicRequest.class))).thenReturn(LATENCY);

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .param("runId", "run-20261028-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.name").value("latency"))
                .andExpect(header().string(OP_HEADER, "op-1"));
    }

    // --------------------------------------------------------- remove toxic

    @Test
    void removeToxicKeepsThePathAndAnswers202WithResultVoidPlusOperationId() throws Exception {
        mockMvc.perform(delete("/chaos/toxiproxy/redis/toxics/latency"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(toxiproxyService).removeToxic("redis", "latency");

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        assertThat(request.getValue().targetKind()).isEqualTo("toxiproxy");
        assertThat(request.getValue().targetName()).isEqualTo("redis");
        assertThat(request.getValue().action()).isEqualTo("toxiproxy-remove");
    }

    @Test
    void removeToxicJoinsTheTimeline() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");

        mockMvc.perform(delete("/chaos/toxiproxy/redis/toxics/latency")
                        .param("runId", "run-1"))
                .andExpect(status().isAccepted());

        verify(faultEvents).recordEvent(eq("run-1"), eq("op-1"), eq("toxiproxy"),
                eq("redis"), eq("toxiproxy-remove"), anyMap(), eq("RUNNING"));
    }

    // -------------------------------------------------------- reset / enable

    @Test
    void resetSubmitsOneOperationAndReturnsTheOriginalView() throws Exception {
        when(toxiproxyService.reset("redis")).thenReturn(new ProxyResetView("redis", 2, true));

        mockMvc.perform(post("/chaos/toxiproxy/redis/reset"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.proxy").value("redis"))
                .andExpect(jsonPath("$.data.toxicsRemoved").value(2))
                .andExpect(jsonPath("$.data.enabled").value(true));

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        assertThat(request.getValue().action()).isEqualTo("toxiproxy-reset");
        assertThat(request.getValue().targetName()).isEqualTo("redis");
    }

    @Test
    void disableAndEnableKeepTheirViewsUnderTheOperationContract() throws Exception {
        when(toxiproxyService.setEnabled("mysql", false))
                .thenReturn(new ProxyEnabledView("mysql", false));
        when(toxiproxyService.setEnabled("mysql", true))
                .thenReturn(new ProxyEnabledView("mysql", true));

        mockMvc.perform(post("/chaos/toxiproxy/mysql/disable"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.proxy").value("mysql"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(post("/chaos/toxiproxy/mysql/enable"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.enabled").value(true));

        verify(toxiproxyService).setEnabled("mysql", false);
        verify(toxiproxyService).setEnabled("mysql", true);

        ArgumentCaptor<OperationRequest> requests =
                ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations, org.mockito.Mockito.times(2)).submit(requests.capture(), any());
        assertThat(requests.getAllValues()).extracting(OperationRequest::action)
                .containsExactly("toxiproxy-disable", "toxiproxy-enable");
    }

    // ------------------------------------------------------------ reset-all

    @Test
    void resetAllUsesTheAllTargetAndReturnsTheNetworkReport() throws Exception {
        when(toxiproxyService.resetAll())
                .thenReturn(new NetworkResetReport(NetworkResetReport.STATUS_RESET, 2, 2, List.of()));

        mockMvc.perform(post("/chaos/toxiproxy/reset-all"))
                .andExpect(status().isAccepted())
                .andExpect(header().string(OP_HEADER, "op-1"))
                .andExpect(jsonPath("$.data.status").value("reset"))
                .andExpect(jsonPath("$.data.proxiesReset").value(2))
                .andExpect(jsonPath("$.data.proxiesTotal").value(2));

        ArgumentCaptor<OperationRequest> request = ArgumentCaptor.forClass(OperationRequest.class);
        verify(operations).submit(request.capture(), any());
        assertThat(request.getValue().targetName()).isEqualTo("all");
        assertThat(request.getValue().action()).isEqualTo("toxiproxy-reset-all");
    }

    @Test
    void resetAllWritesTheTimelineOnlyWhenSomethingWasRestored() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(toxiproxyService.resetAll())
                .thenReturn(new NetworkResetReport(NetworkResetReport.STATUS_FAILED, 0, 2,
                        List.of("redis: unreachable")));

        mockMvc.perform(post("/chaos/toxiproxy/reset-all").param("runId", "run-1"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.status").value("failed"));

        verify(faultEvents, never()).recordEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void aPartiallyRestoredResetAllIsRecorded() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(toxiproxyService.resetAll())
                .thenReturn(new NetworkResetReport(NetworkResetReport.STATUS_PARTIAL, 1, 2,
                        List.of("mysql: boom")));

        mockMvc.perform(post("/chaos/toxiproxy/reset-all").param("runId", "run-1"))
                .andExpect(status().isAccepted());

        verify(faultEvents).recordEvent(eq("run-1"), eq("op-1"), eq("toxiproxy"),
                eq("all"), eq("toxiproxy-reset-all"), anyMap(), eq("RUNNING"));
    }

    // ---------------------------------------------------------------- state

    @Test
    void stateIsStillReadOnly() throws Exception {
        when(toxiproxyService.state()).thenReturn(new ToxiproxyStateView(true,
                List.of("redis", "mysql"),
                List.of(new ProxyStateView("redis", true, true, List.of(LATENCY)),
                        ProxyStateView.missing("mysql"))));

        mockMvc.perform(get("/chaos/toxiproxy/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.allowedProxies[0]").value("redis"))
                .andExpect(jsonPath("$.data.proxies[0].present").value(true))
                .andExpect(jsonPath("$.data.proxies[0].toxics[0].name").value("latency"))
                .andExpect(jsonPath("$.data.proxies[1].present").value(false));

        verify(operations, never()).submit(any(), any());
    }

    // ---------------------------------------------------------- error paths

    @Test
    void unknownProxyReturns404AndRecordsNoEvent() throws Exception {
        when(faultEvents.validateRun("run-1")).thenReturn("RUNNING");
        when(toxiproxyService.addToxic(eq("postgres"), any()))
                .thenThrow(new ToxiproxyNotFoundException("Unknown toxiproxy proxy 'postgres'"));

        mockMvc.perform(post("/chaos/toxiproxy/postgres/toxics")
                        .param("runId", "run-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));

        verify(faultEvents, never()).recordEvent(any(), any(), any(), any(), any(), any(),
                any());
    }

    @Test
    void disabledLayerReturns503() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new ToxiproxyUnavailableException("Toxiproxy support is disabled"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    void invalidToxicReturns400() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new InvalidToxiproxyRequestException("Unsupported toxic type: explode"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"explode\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void duplicateToxicReturns409() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new ToxiproxyConflictException("Toxiproxy conflict for toxic 'latency'"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"));
    }

    @Test
    void malformedBodyReturns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(toxiproxyService, never()).addToxic(any(), any());
        verify(operations, never()).submit(any(), any());
    }

    private static OperationRecord operation(OperationStatus status) {
        return new OperationRecord("op-1", null, "toxiproxy", "redis", "toxiproxy-add", status,
                null, Instant.parse("2026-10-26T10:00:00Z"),
                Instant.parse("2026-10-26T10:00:01Z"), null, null, "local", null, null);
    }

    private static LedgerViews.OperationView operationView(OperationStatus status, boolean replay) {
        return new LedgerViews.OperationView("op-1", null, "toxiproxy", "redis",
                "toxiproxy-add", status.name(), null, "2026-10-26T10:00:00Z", null, null, null,
                "local", null, null, replay);
    }
}
