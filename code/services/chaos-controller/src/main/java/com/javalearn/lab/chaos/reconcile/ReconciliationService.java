package com.javalearn.lab.chaos.reconcile;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.error.ReconciliationNotFoundException;
import com.javalearn.lab.chaos.ledger.AuditSanitizer;
import com.javalearn.lab.chaos.ledger.RunStartedHook;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

/**
 * B3-04 run-scoped business reconciliation.
 *
 * <p><b>Baseline.</b> {@link #onStarted(String)} runs after READY→RUNNING. It
 * records compact aggregate rows ({@code baseline.orders}, {@code baseline.stock},
 * {@code baseline.mq}) in {@code lab_run_check}. Any failure is logged and
 * written as a DEGRADED baseline row; the run itself is never failed or
 * delayed by a reconciliation problem.</p>
 *
 * <p><b>Manual reconcile.</b> {@code POST /lab/runs/{id}/reconcile} compares the
 * current business snapshot with that baseline, probes the oversold counter and
 * drains {@code seckill.order.queue}. Results are persisted item-by-item so
 * {@code GET /lab/runs/{id}} stays readable and
 * {@code GET /lab/runs/{id}/reconciliation} can replay the last answer.</p>
 *
 * <p><b>Verdict.</b> PASS only when every item passes; unwired/unreachable
 * probes are DEGRADED (never 0); failed invariants are FAIL. Performance
 * thresholds are not part of this verdict.</p>
 */
public class ReconciliationService implements RunStartedHook {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    static final String OVERSOLD_PROMQL =
            "sum(stock_oversold_total{job=\"seckill-app\"})";

    private final ReconcileProperties properties;
    private final BusinessSnapshotReader reader;
    private final RabbitQueueGateway rabbit;
    private final PrometheusGateway prometheus;
    private final ReconciliationStore store;
    private final ReconciliationEvaluator evaluator;
    private final ObjectMapper objectMapper;
    private final Clock clock;

    public ReconciliationService(ReconcileProperties properties, BusinessSnapshotReader reader,
                                 RabbitQueueGateway rabbit, PrometheusGateway prometheus,
                                 ReconciliationStore store, ReconciliationEvaluator evaluator,
                                 ObjectMapper objectMapper, Clock clock) {
        this.properties = properties;
        this.reader = reader;
        this.rabbit = rabbit;
        this.prometheus = prometheus;
        this.store = store;
        this.evaluator = evaluator;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    // ---------------------------------------------------------------- baseline

    @Override
    public void onStarted(String runId) {
        captureBaseline(runId);
    }

    /** Best effort: never throws, never changes run status. */
    public void captureBaseline(String runId) {
        if (!properties.isEnabled()) {
            log.debug("Reconciliation is disabled; no baseline captured for run {}", runId);
            return;
        }
        Instant at = now();
        try {
            BusinessSnapshot snapshot = reader.capture(properties.getMaxSampleRows());
            safeInsert(runId, ReconciliationStore.BASELINE_ORDERS, null,
                    ordersBaselineJson(snapshot), "BASELINE", at);
            safeInsert(runId, ReconciliationStore.BASELINE_STOCK, null,
                    stockBaselineJson(snapshot), "BASELINE", at);
        } catch (RuntimeException e) {
            String error = AuditSanitizer.describe(e);
            log.warn("Baseline business snapshot for run {} failed: {}", runId, error);
            safeInsert(runId, ReconciliationStore.BASELINE_ORDERS, null,
                    failureJson("business snapshot", error), "DEGRADED", at);
            safeInsert(runId, ReconciliationStore.BASELINE_STOCK, null,
                    failureJson("business snapshot", error), "DEGRADED", at);
        }
        try {
            RabbitQueueState queue = rabbit.snapshot();
            safeInsert(runId, ReconciliationStore.BASELINE_MQ, null,
                    mqBaselineJson(queue), "BASELINE", at);
        } catch (RuntimeException e) {
            String error = AuditSanitizer.describe(e);
            log.warn("Baseline MQ probe for run {} failed: {}", runId, error);
            safeInsert(runId, ReconciliationStore.BASELINE_MQ, null,
                    failureJson("rabbitmq", error), "DEGRADED", at);
        }
    }

    // ---------------------------------------------------------------- reconcile

    public ReconcileResult reconcile(String runId) {
        Instant at = now();
        String checkedAt = iso(at);
        try {
            if (!store.runExists(runId)) {
                throw new LedgerRunNotFoundException("run not found: " + runId);
            }
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("ledger unavailable while reading run "
                    + runId + ": " + AuditSanitizer.describe(e));
        }
        if (!properties.isEnabled()) {
            return evaluator.unavailable(runId, checkedAt,
                    "lab.reconcile.enabled=false; reconciliation is not wired");
        }

        BusinessSnapshot current;
        try {
            current = reader.capture(properties.getMaxSampleRows());
        } catch (RuntimeException e) {
            String reason = "business snapshot failed: " + AuditSanitizer.describe(e);
            log.warn("Reconciliation for run {} could not read the business schema: {}",
                    runId, reason);
            ReconcileResult result = evaluator.unavailable(runId, checkedAt, reason);
            persist(runId, result, at);
            return result;
        }

        ReconciliationBaseline baseline = loadBaseline(runId);
        ReconciliationEvaluator.MqProbe mq = drainQueue();
        ReconciliationEvaluator.OversoldProbe oversold = probeOversold();
        ReconcileResult result = evaluator.evaluate(runId, checkedAt, baseline, current, mq, oversold);
        persist(runId, result, at);
        log.info("Reconciliation for run {} finished {} ({} items)", runId, result.verdict(),
                result.items().size());
        return result;
    }

    public ReconcileResult latest(String runId) {
        boolean exists;
        try {
            exists = store.runExists(runId);
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("ledger unavailable while reading run "
                    + runId + ": " + AuditSanitizer.describe(e));
        }
        if (!exists) {
            throw new LedgerRunNotFoundException("run not found: " + runId);
        }
        try {
            Optional<ReconciliationStore.PersistedRow> summary = store.findLatestSummary(runId);
            if (summary.isEmpty()) {
                throw new ReconciliationNotFoundException(
                        "NO_DATA: no reconciliation has been run for " + runId
                                + "; call POST /lab/runs/{runId}/reconcile");
            }
            ReconciliationStore.PersistedRow row = summary.get();
            Instant checkedAt = row.checkedAt() == null ? now() : row.checkedAt();
            List<ReconcileItem> items = new ArrayList<>();
            for (ReconciliationStore.PersistedRow item : store.findItemsAt(runId, checkedAt)) {
                items.add(toItem(item));
            }
            return new ReconcileResult(runId, iso(checkedAt), row.verdict(), items);
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("ledger unavailable while reading reconciliation "
                    + "for " + runId + ": " + AuditSanitizer.describe(e));
        }
    }

    // ------------------------------------------------------------------ probes

    private ReconciliationEvaluator.MqProbe drainQueue() {
        long startNanos = System.nanoTime();
        long budgetNanos = TimeUnit.SECONDS.toNanos(properties.getMqDrainTimeoutSeconds());
        long deadline = startNanos + budgetNanos;
        long pollMillis = properties.getMqPollIntervalMillis();
        RabbitQueueState last = null;
        RuntimeException lastError = null;
        while (true) {
            try {
                last = rabbit.snapshot();
                lastError = null;
                if (last.messagesReady() == 0) {
                    return new ReconciliationEvaluator.MqProbe(true, 0,
                            last.messagesUnacknowledged(), elapsedMillis(startNanos),
                            queueDetail(last));
                }
            } catch (RuntimeException e) {
                lastError = e;
            }
            long remainingNanos = deadline - System.nanoTime();
            if (remainingNanos <= 0) {
                if (lastError != null || last == null) {
                    return new ReconciliationEvaluator.MqProbe(false,
                            last == null ? 0 : last.messagesReady(),
                            last == null ? 0 : last.messagesUnacknowledged(),
                            elapsedMillis(startNanos),
                            "rabbitmq management unavailable: "
                                    + AuditSanitizer.describe(lastError));
                }
                return new ReconciliationEvaluator.MqProbe(true, last.messagesReady(),
                        last.messagesUnacknowledged(), elapsedMillis(startNanos),
                        queueDetail(last) + "; drain budget exhausted");
            }
            try {
                TimeUnit.MILLISECONDS.sleep(Math.min(pollMillis,
                        Math.max(1, TimeUnit.NANOSECONDS.toMillis(remainingNanos))));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return new ReconciliationEvaluator.MqProbe(false,
                        last == null ? 0 : last.messagesReady(),
                        last == null ? 0 : last.messagesUnacknowledged(),
                        elapsedMillis(startNanos), "interrupted while waiting for queue drain");
            }
        }
    }

    private ReconciliationEvaluator.OversoldProbe probeOversold() {
        try {
            long atEpochSeconds = clock.instant().getEpochSecond();
            PrometheusMatrix matrix = prometheus.queryInstant(OVERSOLD_PROMQL, atEpochSeconds);
            double total = 0d;
            boolean any = false;
            for (PrometheusSeries series : matrix.series()) {
                List<PrometheusPoint> points = series.points();
                if (points.isEmpty()) {
                    continue;
                }
                double value = points.get(points.size() - 1).value();
                if (!Double.isFinite(value)) {
                    continue;
                }
                total += value;
                any = true;
            }
            if (!any) {
                return new ReconciliationEvaluator.OversoldProbe(false, 0d,
                        "no stock_oversold_total series for job=\"seckill-app\"");
            }
            return new ReconciliationEvaluator.OversoldProbe(true, total,
                    "prometheus queryInstant " + OVERSOLD_PROMQL
                            + " at " + atEpochSeconds + "s = " + total);
        } catch (RuntimeException e) {
            return new ReconciliationEvaluator.OversoldProbe(false, 0d,
                    "prometheus oversold probe unavailable: " + AuditSanitizer.describe(e));
        }
    }

    private static String queueDetail(RabbitQueueState state) {
        return "queue messages_ready=" + state.messagesReady()
                + ", consumers=" + state.consumers();
    }

    private static long elapsedMillis(long startNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
    }

    // -------------------------------------------------------------- persistence

    private void persist(String runId, ReconcileResult result, Instant at) {
        try {
            for (ReconcileItem item : result.items()) {
                store.insertCheck(runId, ReconciliationStore.RECONCILE_PREFIX + item.key(),
                        item.expected(), encodeItem(item), item.verdict(), at);
            }
            ObjectNode summary = objectMapper.createObjectNode();
            summary.put("checkedAt", result.checkedAt());
            summary.put("verdict", result.verdict());
            summary.put("itemCount", result.items().size());
            store.insertCheck(runId, ReconciliationStore.RECONCILE_SUMMARY,
                    "run-scoped business reconciliation", write(summary), result.verdict(), at);
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("ledger unavailable while persisting "
                    + "reconciliation for " + runId + ": " + AuditSanitizer.describe(e));
        }
    }

    private ReconciliationBaseline loadBaseline(String runId) {
        Map<String, ReconciliationStore.PersistedRow> rows;
        try {
            rows = store.findBaselineRows(runId);
        } catch (DataAccessException e) {
            throw new LedgerUnavailableException("ledger unavailable while reading baseline for "
                    + runId + ": " + AuditSanitizer.describe(e));
        }
        ReconciliationBaseline.Orders orders = parseOrders(rows.get(ReconciliationStore.BASELINE_ORDERS));
        ReconciliationBaseline.Stock stock = parseStock(rows.get(ReconciliationStore.BASELINE_STOCK));
        ReconciliationBaseline.Mq mq = parseMq(rows.get(ReconciliationStore.BASELINE_MQ));
        return new ReconciliationBaseline(orders, stock, mq);
    }

    private ReconciliationBaseline.Orders parseOrders(ReconciliationStore.PersistedRow row) {
        JsonNode root = parseJson(row);
        if (root == null || !root.path("captured").asBoolean(true)
                || !root.has("ordersCount")) {
            return null;
        }
        return new ReconciliationBaseline.Orders(
                root.path("ordersCount").asLong(),
                root.path("ordersQuantity").asLong(),
                root.path("distinctOrderNo").asLong(),
                root.path("duplicateOrderNo").asLong(),
                root.path("duplicateIdempotency").asLong());
    }

    private ReconciliationBaseline.Stock parseStock(ReconciliationStore.PersistedRow row) {
        JsonNode root = parseJson(row);
        if (root == null || !root.path("captured").asBoolean(true)
                || !root.has("sumAvailable")) {
            return null;
        }
        return new ReconciliationBaseline.Stock(
                root.path("inventoryRows").asLong(),
                root.path("minAvailable").asLong(),
                root.path("sumAvailable").asLong());
    }

    private ReconciliationBaseline.Mq parseMq(ReconciliationStore.PersistedRow row) {
        JsonNode root = parseJson(row);
        if (root == null) {
            return null;
        }
        if (!root.path("reachable").asBoolean(false)) {
            return new ReconciliationBaseline.Mq(false, 0, 0,
                    root.path("error").asText("baseline MQ probe unreachable"));
        }
        return new ReconciliationBaseline.Mq(true,
                root.path("messagesReady").asLong(),
                root.path("messagesUnacknowledged").asLong(), "baseline captured");
    }

    private JsonNode parseJson(ReconciliationStore.PersistedRow row) {
        if (row == null || row.actual() == null || row.actual().isBlank()) {
            return null;
        }
        try {
            return objectMapper.readTree(row.actual());
        } catch (JsonProcessingException e) {
            log.debug("Unparseable reconciliation row {}: {}", row.checkKey(), e.getMessage());
            return null;
        }
    }

    private ReconcileItem toItem(ReconciliationStore.PersistedRow row) {
        String key = row.checkKey() != null && row.checkKey().startsWith(ReconciliationStore.RECONCILE_PREFIX)
                ? row.checkKey().substring(ReconciliationStore.RECONCILE_PREFIX.length())
                : row.checkKey();
        String actual = row.actual();
        String evidence = "";
        List<String> sample = List.of();
        JsonNode root = parseJson(row);
        if (root != null && root.isObject() && root.has("actual")) {
            actual = root.path("actual").asText(actual);
            evidence = root.path("evidence").asText("");
            if (root.path("sample").isArray()) {
                List<String> values = new ArrayList<>();
                for (JsonNode value : root.path("sample")) {
                    values.add(value.asText());
                }
                sample = values;
            }
        }
        return new ReconcileItem(key, row.expected(), actual, row.verdict(), evidence, sample);
    }

    // ---------------------------------------------------------------- encoders

    private String ordersBaselineJson(BusinessSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("captured", true);
        node.put("ordersCount", snapshot.ordersCount());
        node.put("ordersQuantity", snapshot.ordersQuantity());
        node.put("distinctOrderNo", snapshot.distinctOrderNo());
        node.put("duplicateOrderNo", snapshot.duplicateOrderNo());
        node.put("duplicateIdempotency", snapshot.duplicateIdempotency());
        node.put("capturedAt", iso(snapshot.capturedAt()));
        return write(node);
    }

    private String stockBaselineJson(BusinessSnapshot snapshot) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("captured", true);
        node.put("inventoryRows", snapshot.inventoryRows());
        node.put("minAvailable", snapshot.minAvailable());
        node.put("sumAvailable", snapshot.sumAvailable());
        node.put("capturedAt", iso(snapshot.capturedAt()));
        return write(node);
    }

    private String mqBaselineJson(RabbitQueueState queue) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("reachable", true);
        node.put("messagesReady", queue.messagesReady());
        node.put("messagesUnacknowledged", queue.messagesUnacknowledged());
        node.put("consumers", queue.consumers());
        node.put("queue", properties.getMqQueue());
        return write(node);
    }

    private String failureJson(String probe, String error) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("captured", false);
        node.put("reachable", false);
        node.put("probe", probe);
        node.put("error", error);
        return write(node);
    }

    /**
     * Compact persisted item. {@code actual}/{@code evidence}/{@code sample} are
     * packed as JSON so the generic check row stays informative; the loop drops
     * trailing samples when the VARCHAR(1024) budget would be exceeded.
     */
    private String encodeItem(ReconcileItem item) {
        for (int limit = item.sample().size(); limit >= 0; limit--) {
            String json = itemJson(item, limit, 320, 600, 40);
            if (json.length() <= ReconciliationStore.MAX_VALUE_LENGTH) {
                return json;
            }
        }
        return itemJson(item, 0, 200, 300, 40);
    }

    private String itemJson(ReconcileItem item, int sampleLimit, int actualLimit,
                            int evidenceLimit, int sampleLength) {
        ObjectNode node = objectMapper.createObjectNode();
        node.put("actual", AuditSanitizer.clip(item.actual(), actualLimit));
        node.put("evidence", AuditSanitizer.clip(item.evidence(), evidenceLimit));
        ArrayNode sample = node.putArray("sample");
        for (int i = 0; i < Math.min(sampleLimit, item.sample().size()); i++) {
            sample.add(AuditSanitizer.clip(item.sample().get(i), sampleLength));
        }
        return write(node);
    }

    private String write(JsonNode node) {
        try {
            return objectMapper.writeValueAsString(node);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("cannot serialize reconciliation payload", e);
        }
    }

    private void safeInsert(String runId, String key, String expected, String actual,
                            String verdict, Instant at) {
        try {
            store.insertCheck(runId, key, expected, actual, verdict, at);
        } catch (RuntimeException e) {
            // Baseline capture is best effort; the run must not fail because
            // the ledger is unavailable. Reconcile will report DEGRADED later.
            log.warn("Cannot persist {} for run {}: {}", key, runId,
                    AuditSanitizer.describe(e));
        }
    }

    private Instant now() {
        return clock.instant().truncatedTo(ChronoUnit.MILLIS);
    }

    private static String iso(Instant instant) {
        return instant == null ? null : DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
