package com.javalearn.lab.chaos.precheck;

import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.error.PrecheckBlockedException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerReadiness;
import com.javalearn.lab.chaos.ledger.OperationContext;
import com.javalearn.lab.chaos.ledger.OperationRecord;
import com.javalearn.lab.chaos.ledger.OperationStatus;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusQueryException;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import com.javalearn.lab.chaos.precheck.PrecheckDockerFacts.LabContainerFacts;
import com.javalearn.lab.chaos.toxiproxy.ProxyStateView;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.chaos.traffic.TrafficService;
import com.javalearn.lab.chaos.traffic.TrafficStateView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * B1-04 environment pre-check: the read-only gate a run must pass before the
 * lifecycle takes the write-run lock and moves DRAFT → VALIDATING.
 *
 * <p><b>Never mutates anything.</b> Every collaborator is used only through a
 * read path ({@code activeActions}, traffic state, Toxiproxy state,
 * {@code lab_operation} rows, Prometheus instant queries, Docker inspect).
 * Re-running a report is safe and is exactly what the operator does after
 * cleaning the environment.</p>
 *
 * <p><b>Fail closed.</b> Missing monitoring data, unverifiable background
 * traffic, a Docker daemon that cannot answer the resource check - each either
 * blocks or warns by the rule documented in the B1-04 handoff. There is no
 * force/skip switch; {@code lab.precheck.enabled=false} disables the whole
 * gate (for ledger-only stacks), not one finding.</p>
 *
 * <p><b>Own start operation.</b> The start path inserts its {@code lab_operation}
 * row before the work runs, so the operations check would otherwise always see
 * itself. {@link #enforce(String)} therefore ignores the exact operation id
 * bound by {@code OperationContext} (the worker's own row), whatever its status -
 * a slow pre-check can outlive the sync budget and turn its own row into
 * PENDING_CONFIRMATION before this check runs. A REQUESTED start for the same
 * run is also ignored as a fallback for calls outside an operation; a different
 * PENDING_CONFIRMATION or UNKNOWN sibling still blocks.</p>
 */
public class RunPrecheckService implements RunPrecheckGate {

    public static final String KEY_TRAFFIC = "traffic.stopped";
    public static final String KEY_CHAOS = "chaos.clean";
    public static final String KEY_TOXIPROXY = "toxiproxy.clean";
    public static final String KEY_OPERATIONS = "operations.settled";
    public static final String KEY_MONITORING = "monitoring.ready";
    public static final String KEY_RESOURCES = "resources.budget";
    public static final String KEY_IMAGES = "images.consistent";
    /** B5-00b: a controlled rebuild's stable period blocks formal runs. */
    public static final String KEY_STABLE = "instances.stable";

    /**
     * Job names are topology identifiers from {@code code/lab/docker/prometheus
     * /prometheus.yml}; {@code mysql_cluster_probe_up} is the gauge exported by
     * {@code MysqlClusterMonitor}. Keeping them here (not in the report) makes
     * a renamed job a one-line change with a failing live drill, not silent
     * "monitoring is fine".
     */
    static final String PROMQL_APP_UP = "sum(up{job=\"seckill-app\"})";
    static final String PROMQL_REDIS_EXPORTER_UP = "sum(up{job=\"redis-exporter\"})";
    static final String PROMQL_MYSQL_PROBE_UP = "sum(mysql_cluster_probe_up)";

    private static final Logger log = LoggerFactory.getLogger(RunPrecheckService.class);

    private final PrecheckProperties properties;
    private final LedgerProperties ledgerProperties;
    private final LedgerBootstrap bootstrap;
    private final RunLedgerRepository repository;
    private final PrecheckDockerFacts dockerFacts;
    private final TrafficService trafficService;
    private final ChaosActionService chaosActions;
    private final ToxiproxyService toxiproxy;
    private final ChaosProperties chaosProperties;
    private final PrometheusGateway prometheus;
    private final com.javalearn.lab.chaos.instance.InstanceConfigRepository instanceConfigs;
    private final Clock clock;

    public RunPrecheckService(PrecheckProperties properties, LedgerProperties ledgerProperties,
                              LedgerBootstrap bootstrap, RunLedgerRepository repository,
                              PrecheckDockerFacts dockerFacts, TrafficService trafficService,
                              ChaosActionService chaosActions, ToxiproxyService toxiproxy,
                              ChaosProperties chaosProperties, PrometheusGateway prometheus,
                              com.javalearn.lab.chaos.instance.InstanceConfigRepository instanceConfigs,
                              Clock clock) {
        this.properties = properties;
        this.ledgerProperties = ledgerProperties;
        this.bootstrap = bootstrap;
        this.repository = repository;
        this.dockerFacts = dockerFacts;
        this.trafficService = trafficService;
        this.chaosActions = chaosActions;
        this.toxiproxy = toxiproxy;
        this.chaosProperties = chaosProperties;
        this.prometheus = prometheus;
        this.instanceConfigs = instanceConfigs;
        this.clock = clock;
    }

    /** Report for {@code GET /lab/runs/{runId}/precheck}; blocking is just data. */
    public PrecheckReport inspectReport(String runId) {
        return inspect(runId, false);
    }

    /**
     * Start gate: call before any transition and before acquiring the write-run
     * lock. Returns the report on success; throws {@link PrecheckBlockedException}
     * (409/40903, report attached) when a blocking item exists.
     */
    @Override
    public PrecheckReport enforce(String runId) {
        // The operation worker binds its own id; excluding that exact row is
        // what keeps a slow pre-check from blocking on its own
        // PENDING_CONFIRMATION after the sync budget expires. Calls outside an
        // operation (tests, direct use) fall back to the REQUESTED heuristic.
        PrecheckReport report = inspect(runId, true,
                OperationContext.currentOperationId().orElse(null));
        if (report.blocked()) {
            throw new PrecheckBlockedException(report);
        }
        return report;
    }

    private PrecheckReport inspect(String runId, boolean ignoreOwnStartOperation) {
        return inspect(runId, ignoreOwnStartOperation, null);
    }

    private PrecheckReport inspect(String runId, boolean ignoreOwnStartOperation,
                                   String ignoreOperationId) {
        LedgerReadiness.requireReady(ledgerProperties, bootstrap);
        RunRecord run = requireRun(runId);
        List<String> nodes = run.nodeList();
        Instant checkedAt = clock.instant();
        if (!properties.isEnabled()) {
            return PrecheckReport.disabled(runId, checkedAt.toString());
        }
        Instant deadline = checkedAt.plusSeconds(properties.getCheckTimeoutSeconds());
        List<PrecheckItem> items = new ArrayList<>();
        items.add(trafficCheck(deadline));
        items.add(chaosCheck(deadline));
        items.add(toxiproxyCheck(deadline));
        items.add(operationsCheck(runId, ignoreOwnStartOperation, ignoreOperationId, deadline));
        items.add(stableCheck(deadline));
        items.add(monitoringCheck(deadline, nodes));
        items.add(resourcesCheck(deadline));
        items.add(imagesCheck(deadline, nodes));
        PrecheckReport report = PrecheckReport.of(runId, checkedAt.toString(), items,
                properties.getMaxListedItems());
        log.info("Precheck for run {}: verdict={} blocking={} warnings={}", runId,
                report.verdict(), report.blocking().size(), report.warnings().size());
        return report;
    }

    // ------------------------------------------------------------- individual checks

    private PrecheckItem trafficCheck(Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_TRAFFIC, deadline);
        }
        String container = properties.getTrafficContainer();
        try {
            TrafficStateView state = trafficService.state(container);
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("container", container);
            detail.put("state", state.state());
            if (state.running()) {
                return PrecheckItem.block(KEY_TRAFFIC,
                        "background traffic " + container + " is running and would pollute the run",
                        detail);
            }
            return PrecheckItem.pass(KEY_TRAFFIC,
                    state.state().equals(TrafficStateView.MISSING)
                            ? "background traffic container " + container + " is not installed"
                            : "background traffic " + container + " is stopped (" + state.state() + ")",
                    detail);
        } catch (ContainerNotFoundException e) {
            return PrecheckItem.pass(KEY_TRAFFIC,
                    "background traffic container " + container + " is not installed",
                    Map.of("container", container, "state", TrafficStateView.MISSING));
        } catch (RuntimeException e) {
            // Fail closed: "cannot prove the load generator is stopped" is not
            // the same as "it is stopped".
            return PrecheckItem.block(KEY_TRAFFIC,
                    "could not verify background traffic " + container + ": " + describe(e),
                    Map.of("container", container));
        }
    }

    private PrecheckItem chaosCheck(Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_CHAOS, deadline);
        }
        try {
            List<ChaosActionView> active = chaosActions.activeActions();
            if (active.isEmpty()) {
                return PrecheckItem.pass(KEY_CHAOS, "no chaos action is tracked with a pending revert",
                        Map.of("activeActions", 0));
            }
            List<Map<String, Object>> listed = new ArrayList<>();
            Instant now = clock.instant();
            for (ChaosActionView action : active.subList(0,
                    Math.min(active.size(), properties.getMaxListedItems()))) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("id", action.id());
                entry.put("target", action.target());
                entry.put("action", action.action());
                entry.put("status", action.status());
                Long remaining = remainingSeconds(action.expiresAt(), now);
                entry.put("remainingTtlSeconds", remaining);
                listed.add(entry);
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("activeActions", active.size());
            detail.put("actions", listed);
            ChaosActionView first = active.get(0);
            String example = first.target() + " " + first.action()
                    + (remainingSeconds(first.expiresAt(), now) == null ? ""
                    : " (" + remainingSeconds(first.expiresAt(), now) + "s left)");
            return PrecheckItem.block(KEY_CHAOS,
                    active.size() + " chaos action(s) still active (e.g. " + example
                            + "); revert them before a run",
                    detail);
        } catch (RuntimeException e) {
            return PrecheckItem.block(KEY_CHAOS,
                    "could not read the active chaos actions: " + describe(e), Map.of());
        }
    }

    private PrecheckItem toxiproxyCheck(Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_TOXIPROXY, deadline);
        }
        if (chaosProperties.getToxiproxy() == null || !chaosProperties.getToxiproxy().isEnabled()) {
            // The dev stack has no proxy tier at all; that must not block a run.
            return PrecheckItem.warn(KEY_TOXIPROXY,
                    "Toxiproxy layer is disabled (chaos.toxiproxy.enabled=false); no toxic can exist",
                    Map.of("enabled", false));
        }
        try {
            ToxiproxyStateView state = toxiproxy.state();
            List<Map<String, Object>> toxics = new ArrayList<>();
            List<String> disabled = new ArrayList<>();
            for (ProxyStateView proxy : state.proxies()) {
                if (!proxy.present()) {
                    continue;
                }
                if (!proxy.enabled()) {
                    disabled.add(proxy.name());
                }
                for (ToxicView toxic : proxy.toxics()) {
                    if (toxics.size() >= properties.getMaxListedItems()) {
                        continue;
                    }
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("proxy", proxy.name());
                    entry.put("toxic", toxic.name());
                    entry.put("type", toxic.type());
                    toxics.add(entry);
                }
            }
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("enabled", state.enabled());
            detail.put("proxies", state.allowedProxies());
            detail.put("toxics", toxics);
            detail.put("disabledProxies", disabled);
            if (!toxics.isEmpty() || !disabled.isEmpty()) {
                return PrecheckItem.block(KEY_TOXIPROXY,
                        "network fault residue: " + toxics.size() + " toxic(s), "
                                + disabled.size() + " disabled proxy(ies); reset them before a run",
                        detail);
            }
            return PrecheckItem.pass(KEY_TOXIPROXY, "Toxiproxy is reachable and clean", detail);
        } catch (ToxiproxyUnavailableException e) {
            // Enabled but the overlay is not up: the historical dev stack.
            return PrecheckItem.warn(KEY_TOXIPROXY,
                    "Toxiproxy is enabled but unreachable: " + describe(e), Map.of("enabled", true));
        } catch (RuntimeException e) {
            return PrecheckItem.warn(KEY_TOXIPROXY,
                    "Toxiproxy state could not be read: " + describe(e), Map.of("enabled", true));
        }
    }

    private PrecheckItem operationsCheck(String runId, boolean ignoreOwnStart,
                                         String ignoreOperationId, Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_OPERATIONS, deadline);
        }
        List<OperationStatus> unsettledStatuses = List.of(OperationStatus.REQUESTED,
                OperationStatus.PENDING_CONFIRMATION, OperationStatus.UNKNOWN);
        List<OperationRecord> unsettled;
        try {
            unsettled = repository.findRecentOperationsByStatuses(unsettledStatuses,
                    ledgerProperties.getQueryMaxLimit());
        } catch (org.springframework.dao.DataAccessException e) {
            // The ledger is required for the whole gate (run row, operation row).
            throw LedgerReadiness.unavailable(bootstrap, e, "precheck operation query");
        }
        List<OperationRecord> relevant = new ArrayList<>();
        for (OperationRecord operation : unsettled) {
            if (ignoreOperationId != null && ignoreOperationId.equals(operation.operationId())) {
                continue;
            }
            if (ignoreOwnStart && isOwnStartOperation(operation, runId)) {
                continue;
            }
            relevant.add(operation);
        }
        if (relevant.isEmpty()) {
            return PrecheckItem.pass(KEY_OPERATIONS,
                    "no operation is REQUESTED, PENDING_CONFIRMATION or UNKNOWN",
                    Map.of("unsettledOperations", 0));
        }
        List<Map<String, Object>> listed = new ArrayList<>();
        for (OperationRecord operation : relevant.subList(0,
                Math.min(relevant.size(), properties.getMaxListedItems()))) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("operationId", operation.operationId());
            entry.put("runId", operation.runId());
            entry.put("action", operation.action());
            entry.put("target", operation.targetName());
            entry.put("status", operation.status().name());
            entry.put("requestedAt", operation.requestedAt() == null
                    ? null : operation.requestedAt().toString());
            listed.add(entry);
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("unsettledOperations", relevant.size());
        detail.put("operations", listed);
        OperationRecord first = relevant.get(0);
        return PrecheckItem.block(KEY_OPERATIONS,
                relevant.size() + " unsettled operation(s) (e.g. " + first.operationId() + " "
                        + first.action() + " " + first.targetName() + " " + first.status().name()
                        + "); confirm or close them first",
                detail);
    }

    /** B5-00b: any instance still inside its post-rebuild stable period blocks. */
    private PrecheckItem stableCheck(Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_STABLE, deadline);
        }
        try {
            var active = instanceConfigs.findStableAfter(clock.instant());
            if (active.isEmpty()) {
                return PrecheckItem.pass(KEY_STABLE, "no instance is inside a stable period", Map.of());
            }
            List<Map<String, Object>> detailList = new ArrayList<>();
            for (var row : active) {
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("instance", row.instance());
                entry.put("status", row.status().name());
                entry.put("stableUntil", row.stableUntil() == null ? null
                        : row.stableUntil().toString());
                detailList.add(entry);
            }
            return PrecheckItem.block(KEY_STABLE,
                    "post-rebuild stable period is still active for "
                            + active.stream().map(row -> row.instance()).toList()
                            + "; wait until stable_until before starting a formal comparison",
                    Map.of("instances", detailList));
        } catch (org.springframework.dao.DataAccessException e) {
            return PrecheckItem.block(KEY_STABLE,
                    "cannot verify instance stable periods (ledger query failed): " + describe(e),
                    Map.of("query", "lab_instance_config.stable_until"));
        }
    }

    private PrecheckItem monitoringCheck(Instant deadline, List<String> nodes) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_MONITORING, deadline);
        }
        long at = clock.instant().getEpochSecond();
        int requiredApps = nodes.isEmpty() ? properties.getRequiredAppTargets() : nodes.size();
        String appQuery = nodes.isEmpty() ? PROMQL_APP_UP : appUpQuery(nodes);
        Map<String, Object> detail = new LinkedHashMap<>();
        List<String> failures = new java.util.ArrayList<>();
        try {
            detail.put("requiredAppTargets", requiredApps);
            if (!nodes.isEmpty()) {
                detail.put("enabledNodes", nodes);
            }
            Double appUp = scalar(appQuery, at);
            detail.put("appUp", appUp);
            if (appUp == null || Math.round(appUp) != requiredApps) {
                failures.add("app tier is not fully scraped: up{job=\"seckill-app\"}="
                        + text(appUp) + ", expected " + requiredApps);
            }
            if (properties.isRequireRedisExporter()) {
                Double redisUp = scalar(PROMQL_REDIS_EXPORTER_UP, at);
                detail.put("redisExporterUp", redisUp);
                if (redisUp == null || Math.round(redisUp) < 1) {
                    failures.add("redis-exporter is not up (up{job=\"redis-exporter\"}="
                            + text(redisUp) + ")");
                }
            } else {
                detail.put("redisExporterUp", "not-required");
            }
            if (properties.isRequireMysqlProbe()) {
                Double probeUp = scalar(PROMQL_MYSQL_PROBE_UP, at);
                detail.put("mysqlClusterProbeUp", probeUp);
                if (probeUp == null || Math.round(probeUp) != 1) {
                    failures.add("mysql_cluster_probe_up=" + text(probeUp)
                            + " (expected 1); the MySQL cluster probe is not reporting");
                }
            } else {
                detail.put("mysqlClusterProbeUp", "not-required");
            }
            for (PrecheckProperties.RequiredQuery query : properties.extraQueryList()) {
                Double value = scalar(query.promql(), at);
                detail.put("extra." + query.name(), value);
                if (value == null) {
                    failures.add("extra query '" + query.name() + "' returned no series");
                }
            }
        } catch (PrometheusQueryException e) {
            return PrecheckItem.block(KEY_MONITORING,
                    "Prometheus is unreachable, so the run could not be verified: " + describe(e),
                    Map.of("prometheusReachable", false));
        } catch (RuntimeException e) {
            return PrecheckItem.block(KEY_MONITORING,
                    "Prometheus query failed: " + describe(e), Map.of("prometheusReachable", false));
        }
        if (!failures.isEmpty()) {
            return PrecheckItem.block(KEY_MONITORING,
                    "monitoring is not ready: " + String.join("; ", failures), detail);
        }
        return PrecheckItem.pass(KEY_MONITORING,
                "required monitoring series are present", detail);
    }

    private PrecheckItem resourcesCheck(Instant deadline) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_RESOURCES, deadline);
        }
        try {
            List<LabContainerFacts> running = dockerFacts.runningLabContainers();
            long hostTotal = dockerFacts.memoryTotalBytes();
            if (hostTotal <= 0) {
                return PrecheckItem.warn(KEY_RESOURCES,
                        "docker info reports no host memory total; the budget cannot be evaluated",
                        Map.of("hostTotalMb", 0));
            }
            if (running.isEmpty()) {
                return PrecheckItem.warn(KEY_RESOURCES,
                        "no running lab container was observed; the budget cannot be evaluated",
                        Map.of("containers", 0));
            }
            long limitedBytes = 0L;
            int unbounded = 0;
            List<Map<String, Object>> listed = new ArrayList<>();
            for (LabContainerFacts container : running) {
                if (container.memoryLimitBytes() <= 0) {
                    unbounded++;
                    continue;
                }
                limitedBytes += container.memoryLimitBytes();
                if (listed.size() < properties.getMaxListedItems()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("container", container.name());
                    entry.put("limitMb", toMb(container.memoryLimitBytes()));
                    listed.add(entry);
                }
            }
            long reserveBytes = toBytes(properties.getMemoryReserveMb());
            Map<String, Object> detail = new LinkedHashMap<>();
            detail.put("runningContainers", running.size());
            detail.put("containers", listed);
            detail.put("limitTotalMb", toMb(limitedBytes));
            detail.put("reserveMb", properties.getMemoryReserveMb());
            detail.put("hostTotalMb", toMb(hostTotal));
            detail.put("overByMb", Math.max(0L, toMb(limitedBytes + reserveBytes - hostTotal)));
            if (unbounded > 0) {
                // Without a limit the sum is not a bound; reporting PASS would be
                // a fabricated budget. Still visible, never silent.
                detail.put("unboundedContainers", unbounded);
                return PrecheckItem.warn(KEY_RESOURCES,
                        unbounded + " running lab container(s) have no mem_limit, so the total is"
                                + " not bounded", detail);
            }
            if (limitedBytes + reserveBytes > hostTotal) {
                return PrecheckItem.block(KEY_RESOURCES,
                        "memory budget exceeded: mem_limit total " + toMb(limitedBytes)
                                + "MB + reserve " + properties.getMemoryReserveMb()
                                + "MB > host " + toMb(hostTotal) + "MB", detail);
            }
            return PrecheckItem.pass(KEY_RESOURCES,
                    "memory budget fits: " + toMb(limitedBytes) + "MB of limits + "
                            + properties.getMemoryReserveMb() + "MB reserve <= "
                            + toMb(hostTotal) + "MB", detail);
        } catch (RuntimeException e) {
            return PrecheckItem.warn(KEY_RESOURCES,
                    "Docker memory data is unavailable: " + describe(e), Map.of());
        }
    }

    /**
     * B3-00a: when the draft enabled a subset, only those scrape targets count.
     * Labels are service names ({@code app-1:8080}), so the match is on the
     * instance prefix and does not hardcode the port.
     */
    static String appUpQuery(List<String> nodes) {
        String pattern = nodes.stream()
                .map(node -> node.replace("\\", "\\\\").replace("\"", "\\\""))
                .collect(java.util.stream.Collectors.joining("|"));
        return "sum(up{job=\"seckill-app\",instance=~\"(" + pattern + "):.*\"})";
    }

    private PrecheckItem imagesCheck(Instant deadline, List<String> nodes) {
        if (budgetExhausted(deadline)) {
            return skipped(KEY_IMAGES, deadline);
        }
        List<String> names = properties.appContainerNames().stream()
                .filter(name -> nodes.isEmpty()
                        || nodes.stream().anyMatch(node -> name.endsWith("-" + node)))
                .toList();
        if (names.isEmpty() && !nodes.isEmpty()) {
            return PrecheckItem.warn(KEY_IMAGES,
                    "no app container matches the enabled nodes " + nodes
                            + "; image consistency is not verifiable",
                    Map.of("enabledNodes", nodes));
        }
        List<Map<String, Object>> listed = new ArrayList<>();
        List<String> runningIds = new ArrayList<>();
        Map<String, String> imageByContainer = new LinkedHashMap<>();
        int missing = 0;
        for (String name : names) {
            try {
                LabContainerFacts facts = dockerFacts.inspect(name);
                Map<String, Object> entry = new LinkedHashMap<>();
                entry.put("container", facts.name());
                entry.put("state", facts.state());
                entry.put("imageId", facts.imageId());
                if (listed.size() < properties.getMaxListedItems()) {
                    listed.add(entry);
                }
                if ("running".equals(facts.state()) && facts.imageId() != null
                        && !facts.imageId().isBlank()) {
                    runningIds.add(facts.imageId());
                    imageByContainer.put(facts.name(), facts.imageId());
                }
            } catch (ContainerNotFoundException e) {
                missing++;
            } catch (RuntimeException e) {
                return PrecheckItem.warn(KEY_IMAGES,
                        "app container images could not be inspected: " + describe(e),
                        Map.of("containers", names));
            }
        }
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("containers", listed);
        detail.put("missingContainers", missing);
        if (!nodes.isEmpty()) {
            detail.put("enabledNodes", nodes);
        }
        detail.put("expectedImageId", blankToNull(properties.getExpectedAppImageId()));
        detail.put("mixedImageContainer", blankToNull(properties.getMixedImageContainer()));
        Set<String> distinct = new LinkedHashSet<>(runningIds);
        if (runningIds.isEmpty()) {
            return PrecheckItem.warn(KEY_IMAGES,
                    "no running app container was found; image consistency is not verifiable", detail);
        }
        if (distinct.size() > 1) {
            String mixedContainer = blankToNull(properties.getMixedImageContainer());
            String mixedImage = mixedContainer == null ? null : imageByContainer.get(mixedContainer);
            if (mixedImage != null) {
                Set<String> otherImages = new LinkedHashSet<>(runningIds);
                otherImages.remove(mixedImage);
                long mixedCount = runningIds.stream().filter(mixedImage::equals).count();
                boolean mixedByDesign = mixedCount == 1 && otherImages.size() == 1
                        && !otherImages.contains(mixedImage);
                if (mixedByDesign) {
                    String majority = otherImages.iterator().next();
                    String expectedMajority = blankToNull(properties.getExpectedAppImageId());
                    if (expectedMajority != null && !imageMatches(majority, expectedMajority)) {
                        detail.put("majorityImageId", majority);
                        return PrecheckItem.block(KEY_IMAGES,
                                "mixed app tier: the non-" + mixedContainer + " image " + majority
                                        + " does not match LAB_PRECHECK_EXPECTED_APP_IMAGE_ID "
                                        + expectedMajority, detail);
                    }
                    detail.put("imageByContainer", imageByContainer);
                    return PrecheckItem.pass(KEY_IMAGES,
                            "mixed app tier by design: " + mixedContainer + " runs jdk8 ("
                                    + mixedImage + "); the other app containers share "
                                    + majority, detail);
                }
            }
            return PrecheckItem.block(KEY_IMAGES,
                    "app containers run different images (" + distinct.size() + " distinct ids);"
                            + " rebuild or recreate the tier before a run", detail);
        }
        int requiredContainers = nodes.isEmpty() ? properties.getRequiredAppTargets() : nodes.size();
        if (runningIds.size() < requiredContainers) {
            detail.put("runningContainers", runningIds.size());
            return PrecheckItem.warn(KEY_IMAGES,
                    "only " + runningIds.size() + " of " + requiredContainers
                            + " enabled app containers are running; the comparison is incomplete", detail);
        }
        String expected = blankToNull(properties.getExpectedAppImageId());
        if (expected != null) {
            String expectedId = expected.toLowerCase(Locale.ROOT);
            boolean matched = distinct.stream()
                    .allMatch(id -> id.toLowerCase(Locale.ROOT).equals(expectedId)
                            || id.toLowerCase(Locale.ROOT).startsWith(expectedId));
            if (!matched) {
                return PrecheckItem.block(KEY_IMAGES,
                        "running app image " + distinct.iterator().next()
                                + " does not match LAB_PRECHECK_EXPECTED_APP_IMAGE_ID " + expected,
                        detail);
            }
        }
        return PrecheckItem.pass(KEY_IMAGES,
                "all running app containers use the same image " + distinct.iterator().next(), detail);
    }

    // ------------------------------------------------------------------- helpers

    private boolean budgetExhausted(Instant deadline) {
        return clock.instant().isAfter(deadline);
    }

    private PrecheckItem skipped(String key, Instant deadline) {
        return PrecheckItem.block(key,
                "not evaluated: the precheck budget of " + properties.getCheckTimeoutSeconds()
                        + "s (lab.precheck.check-timeout-seconds) was exhausted; retry the precheck",
                Map.of("skipped", true));
    }

    /** Last finite sample of an instant query; null when Prometheus has no data. */
    private Double scalar(String promql, long atEpochSeconds) {
        PrometheusMatrix matrix = prometheus.queryInstant(promql, atEpochSeconds);
        Double value = null;
        for (PrometheusSeries series : matrix.series()) {
            List<PrometheusPoint> points = series.points();
            if (points.isEmpty()) {
                continue;
            }
            PrometheusPoint last = points.get(points.size() - 1);
            if (!Double.isFinite(last.value())) {
                continue;
            }
            value = value == null ? last.value() : value + last.value();
        }
        return value;
    }

    private static boolean isOwnStartOperation(OperationRecord operation, String runId) {
        return operation.status() == OperationStatus.REQUESTED
                && "start".equals(operation.action())
                && "run".equals(operation.targetKind())
                && runId.equals(operation.targetName());
    }

    private static Long remainingSeconds(Instant expiresAt, Instant now) {
        if (expiresAt == null) {
            return null;
        }
        return Math.max(0L, Duration.between(now, expiresAt).getSeconds());
    }

    private RunRecord requireRun(String runId) {
        try {
            return repository.findRun(runId)
                    .orElseThrow(() -> new LedgerRunNotFoundException("run not found: " + runId));
        } catch (org.springframework.dao.DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "run query");
        }
    }

    private static String describe(Throwable error) {
        String message = error.getMessage();
        return message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    }

    private static String text(Double value) {
        if (value == null) {
            return "missing";
        }
        return value == Math.rint(value) ? Long.toString(Math.round(value)) : value.toString();
    }

    private static long toMb(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0));
    }

    private static long toBytes(int megabytes) {
        return (long) megabytes * 1024L * 1024L;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    private static boolean imageMatches(String imageId, String expected) {
        if (imageId == null || expected == null) {
            return false;
        }
        String expectedId = expected.toLowerCase(Locale.ROOT);
        String actual = imageId.toLowerCase(Locale.ROOT);
        return actual.equals(expectedId) || actual.startsWith(expectedId);
    }
}
