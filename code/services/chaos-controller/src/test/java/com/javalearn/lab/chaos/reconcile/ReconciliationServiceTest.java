package com.javalearn.lab.chaos.reconcile;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.ReconciliationNotFoundException;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Baseline capture, persistence and no-data behaviour of the B3-04 service. */
class ReconciliationServiceTest {

    private static final String RUN = "run-20261030-001";
    private static final Instant NOW = Instant.parse("2026-10-30T12:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private ReconcileProperties properties;
    private BusinessSnapshotReader reader;
    private RabbitQueueGateway rabbit;
    private PrometheusGateway prometheus;
    private ReconciliationStore store;
    private ReconciliationService service;

    @BeforeEach
    void setUp() {
        properties = new ReconcileProperties();
        properties.setMqDrainTimeoutSeconds(1);
        properties.setMqRequestTimeoutSeconds(1);
        properties.setMqPollIntervalMillis(50);
        reader = mock(BusinessSnapshotReader.class);
        rabbit = mock(RabbitQueueGateway.class);
        prometheus = mock(PrometheusGateway.class);
        store = mock(ReconciliationStore.class);
        service = new ReconciliationService(properties, reader, rabbit, prometheus, store,
                new ReconciliationEvaluator(), MAPPER, Clock.fixed(NOW, ZoneOffset.UTC));
    }

    @Test
    void capturesThreeBaselineRowsOnRunStart() {
        when(reader.capture(20)).thenReturn(snapshot());
        when(rabbit.snapshot()).thenReturn(new RabbitQueueState(0, 0, 3));

        service.onStarted(RUN);

        verify(store).insertCheck(eq(RUN), eq("baseline.orders"), eq(null), anyString(),
                eq("BASELINE"), eq(NOW));
        verify(store).insertCheck(eq(RUN), eq("baseline.stock"), eq(null), anyString(),
                eq("BASELINE"), eq(NOW));
        verify(store).insertCheck(eq(RUN), eq("baseline.mq"), eq(null), anyString(),
                eq("BASELINE"), eq(NOW));
    }

    @Test
    void baselineFailureIsRecordedButNeverThrows() {
        when(reader.capture(20)).thenThrow(new IllegalStateException("mysql down"));
        when(rabbit.snapshot()).thenThrow(new IllegalStateException("rabbit down"));

        assertThatCode(() -> service.onStarted(RUN)).doesNotThrowAnyException();

        verify(store).insertCheck(eq(RUN), eq("baseline.orders"), eq(null), anyString(),
                eq("DEGRADED"), eq(NOW));
        verify(store).insertCheck(eq(RUN), eq("baseline.stock"), eq(null), anyString(),
                eq("DEGRADED"), eq(NOW));
        verify(store).insertCheck(eq(RUN), eq("baseline.mq"), eq(null), anyString(),
                eq("DEGRADED"), eq(NOW));
    }

    @Test
    void ledgerWriteFailureDuringCaptureNeverThrows() {
        when(reader.capture(20)).thenReturn(snapshot());
        when(rabbit.snapshot()).thenReturn(new RabbitQueueState(0, 0, 3));
        org.mockito.Mockito.doThrow(new org.springframework.dao.DataAccessResourceFailureException("down"))
                .when(store).insertCheck(anyString(), anyString(), any(), anyString(),
                        anyString(), any(Instant.class));

        assertThatCode(() -> service.onStarted(RUN)).doesNotThrowAnyException();
    }

    @Test
    void reconcileComparesAgainstPersistedBaselineAndPersistsEightRows() {
        when(store.runExists(RUN)).thenReturn(true);
        when(reader.capture(20)).thenReturn(snapshot());
        when(store.findBaselineRows(RUN)).thenReturn(baselineRows());
        when(rabbit.snapshot()).thenReturn(new RabbitQueueState(0, 0, 3));
        when(prometheus.queryInstant(anyString(), anyLong())).thenReturn(
                new PrometheusMatrix(List.of(new PrometheusSeries(Map.of(),
                        List.of(new PrometheusPoint(1793235164.0, 0d))))));

        ReconcileResult result = service.reconcile(RUN);

        assertThat(result.verdict()).isEqualTo("PASS");
        assertThat(result.items()).hasSize(7);
        // Seven item rows plus one summary row, all at the same checked_at.
        verify(store, org.mockito.Mockito.times(8)).insertCheck(eq(RUN), anyString(),
                any(), anyString(), anyString(), eq(NOW));
        verify(store).insertCheck(eq(RUN), eq("reconcile.summary"), anyString(), anyString(),
                eq("PASS"), eq(NOW));
    }

    @Test
    void reconcileUnknownRunIs404AndWritesNothing() {
        when(store.runExists(RUN)).thenReturn(false);

        assertThatThrownBy(() -> service.reconcile(RUN))
                .isInstanceOf(LedgerRunNotFoundException.class);
        verify(store, never()).insertCheck(anyString(), anyString(), any(), anyString(),
                anyString(), any(Instant.class));
    }

    @Test
    void latestWithoutPersistedResultIsNoData() {
        when(store.runExists(RUN)).thenReturn(true);
        when(store.findLatestSummary(RUN)).thenReturn(java.util.Optional.empty());

        assertThatThrownBy(() -> service.latest(RUN))
                .isInstanceOf(ReconciliationNotFoundException.class)
                .hasMessageContaining("NO_DATA");
    }

    @Test
    void latestParsesPersistedItems() {
        when(store.runExists(RUN)).thenReturn(true);
        when(store.findLatestSummary(RUN)).thenReturn(java.util.Optional.of(
                new ReconciliationStore.PersistedRow("reconcile.summary", "run-scoped",
                        "{\"verdict\":\"FAIL\"}", "FAIL", NOW)));
        when(store.findItemsAt(RUN, NOW)).thenReturn(List.of(
                new ReconciliationStore.PersistedRow("reconcile.stock.delta",
                        "sum(new order.quantity) == stock decrement",
                        "{\"actual\":\"quantityDelta=5, stockDecrement=7\","
                                + "\"evidence\":\"baseline.quantity=10\",\"sample\":[\"p1=89\"]}",
                        "FAIL", NOW)));

        ReconcileResult result = service.latest(RUN);

        assertThat(result.checkedAt()).isEqualTo("2026-10-30T12:00:00Z");
        assertThat(result.verdict()).isEqualTo("FAIL");
        assertThat(result.items()).singleElement().satisfies(item -> {
            assertThat(item.key()).isEqualTo("stock.delta");
            assertThat(item.actual()).isEqualTo("quantityDelta=5, stockDecrement=7");
            assertThat(item.evidence()).isEqualTo("baseline.quantity=10");
            assertThat(item.sample()).containsExactly("p1=89");
        });
    }

    @Test
    void disabledReconciliationNeverTouchesBusinessData() {
        properties.setEnabled(false);
        when(store.runExists(RUN)).thenReturn(true);

        ReconcileResult result = service.reconcile(RUN);

        assertThat(result.verdict()).isEqualTo("DEGRADED");
        assertThat(result.items()).hasSize(7);
        verify(reader, never()).capture(org.mockito.ArgumentMatchers.anyInt());
    }

    private static BusinessSnapshot snapshot() {
        return new BusinessSnapshot(NOW, 15, 15, 15, 0, 0, 10, 89, 995,
                List.of(new BusinessSnapshot.ProductSample(1, 89)), List.of(), List.of());
    }

    private static Map<String, ReconciliationStore.PersistedRow> baselineRows() {
        return Map.of(
                "baseline.orders", new ReconciliationStore.PersistedRow("baseline.orders", null,
                        "{\"captured\":true,\"ordersCount\":10,\"ordersQuantity\":10,"
                                + "\"distinctOrderNo\":10,\"duplicateOrderNo\":0,"
                                + "\"duplicateIdempotency\":0}", "BASELINE", NOW),
                "baseline.stock", new ReconciliationStore.PersistedRow("baseline.stock", null,
                        "{\"captured\":true,\"inventoryRows\":10,\"minAvailable\":100,"
                                + "\"sumAvailable\":1000}", "BASELINE", NOW),
                "baseline.mq", new ReconciliationStore.PersistedRow("baseline.mq", null,
                        "{\"reachable\":true,\"messagesReady\":0,"
                                + "\"messagesUnacknowledged\":0}", "BASELINE", NOW));
    }
}
