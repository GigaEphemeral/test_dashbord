package com.javalearn.lab.chaos.topology;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.gateway.ContainerFacts;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.instance.InstanceConfig;
import com.javalearn.lab.chaos.instance.InstanceConfigProperties;
import com.javalearn.lab.chaos.instance.InstanceConfigRepository;
import com.javalearn.lab.chaos.instance.StoredInstanceConfig;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.precheck.PrecheckProperties;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyEdgeView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyMetricsView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyNodeView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyView;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * B5-01a topology contract: the console heat view asks once per poll and gets
 * every design §16.3 node, its enabled/hidden flags, Docker health, the JDK
 * observability class and a Prometheus instant-query metric snapshot.
 *
 * <p>Rules pinned here:</p>
 * <ul>
 *   <li>the full topology is static; a missing container becomes
 *       {@code NOT_RUNNING} and is never dropped from {@code nodes[]};</li>
 *   <li>only {@link PrometheusGateway#queryInstant} is used (no second
 *       datasource) and a failed/empty series stays {@code null};</li>
 *   <li>{@code dataQuality} is OK / PARTIAL / NO_DATA / UNWIRED and a node
 *       without any metric source (router, sentinel, toxiproxy, sidecar) is
 *       always UNWIRED;</li>
 *   <li>the enabled set is the query parameter, else the newest ledger run
 *       with non-null {@code nodes}, else all configured app names;</li>
 *   <li>answers are cached for {@code lab.topology.cache-seconds} so the 5s
 *       UI poll cannot fan out an unbounded number of Prometheus queries.</li>
 * </ul>
 */
public class TopologyService {

    public static final String HEALTH_UP = "UP";
    public static final String HEALTH_DOWN = "DOWN";
    public static final String HEALTH_UNKNOWN = "UNKNOWN";
    public static final String HEALTH_NOT_RUNNING = "NOT_RUNNING";

    public static final String OBSERVABILITY_FULL = "FULL-OBSERVABILITY";
    public static final String OBSERVABILITY_LIMITED = "LIMITED-OBSERVABILITY";
    public static final String OBSERVABILITY_UNKNOWN = "UNKNOWN";

    public static final String QUALITY_OK = "OK";
    public static final String QUALITY_PARTIAL = "PARTIAL";
    public static final String QUALITY_NO_DATA = "NO_DATA";
    public static final String QUALITY_UNWIRED = "UNWIRED";

    private static final String TIER_JAVA = "java";
    private static final String TIER_EDGE = "edge";
    private static final String TIER_REDIS = "redis";
    private static final String TIER_SENTINEL = "sentinel";
    private static final String TIER_MYSQL = "mysql";
    private static final String TIER_ROUTER = "router";
    private static final String TIER_MQ = "mq";
    private static final String TIER_PROXY = "proxy";
    private static final String KIND_HTTP = "http";
    private static final String KIND_REDIS = "redis";
    private static final String KIND_MYSQL = "mysql";
    private static final String KIND_AMQP = "amqp";
    private static final String KIND_PROXY = "proxy";
    private static final String KIND_SENTINEL = "sentinel";

    private static final String SERVICE_NAME = "[a-z0-9][a-z0-9-]{0,62}";
    private static final int MAX_NOTE_LENGTH = 200;
    private static final int MAX_LEDGER_RUNS_SCANNED = 20;

    private static final Set<String> APP_REQUIRED =
            Set.of("cpu", "heapUsed", "heapMax", "threads", "active", "requests");

    private final TopologyProperties properties;
    private final PrecheckProperties precheckProperties;
    private final InstanceConfigProperties instanceProperties;
    private final ContainerGateway containerGateway;
    private final PrometheusGateway prometheusGateway;
    private final InstanceConfigRepository instanceConfigRepository;
    private final RunLedgerRepository runLedgerRepository;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Object cacheLock = new Object();

    private volatile CacheEntry cache;

    public TopologyService(TopologyProperties properties, PrecheckProperties precheckProperties,
                           InstanceConfigProperties instanceProperties,
                           ContainerGateway containerGateway, PrometheusGateway prometheusGateway,
                           InstanceConfigRepository instanceConfigRepository,
                           RunLedgerRepository runLedgerRepository, ObjectMapper objectMapper,
                           Clock clock) {
        this.properties = properties;
        this.precheckProperties = precheckProperties;
        this.instanceProperties = instanceProperties;
        this.containerGateway = containerGateway;
        this.prometheusGateway = prometheusGateway;
        this.instanceConfigRepository = instanceConfigRepository;
        this.runLedgerRepository = runLedgerRepository;
        this.objectMapper = objectMapper;
        this.clock = clock;
    }

    /**
     * Topology snapshot. {@code enabledRaw} is the optional comma-separated
     * service list; the returned {@code enabledNodes} is always the
     * authoritative, normalized selection.
     */
    public TopologyView snapshot(String enabledRaw, boolean includeSidecars) {
        String key = (includeSidecars ? "sidecars" : "main") + "|"
                + (enabledRaw == null ? "" : enabledRaw.trim());
        int ttl = properties.getCacheSeconds();
        if (ttl <= 0) {
            return build(enabledRaw, includeSidecars);
        }
        long now = clock.millis();
        CacheEntry current = cache;
        if (current != null && current.key().equals(key) && now < current.expiresAtMillis()) {
            return current.view();
        }
        synchronized (cacheLock) {
            current = cache;
            now = clock.millis();
            if (current != null && current.key().equals(key) && now < current.expiresAtMillis()) {
                return current.view();
            }
            TopologyView view = build(enabledRaw, includeSidecars);
            cache = new CacheEntry(key, now + ttl * 1000L, view);
            return view;
        }
    }

    private TopologyView build(String enabledRaw, boolean includeSidecars) {
        Instant now = clock.instant();
        long nowEpochSeconds = now.getEpochSecond();

        DockerSnapshot docker = readContainers();
        Map<String, Integer> storedJdk = readStoredJdk();

        List<NodeSpec> specs = mainSpecs();
        List<String> appServices = specs.stream()
                .filter(spec -> TIER_JAVA.equals(spec.tier()))
                .map(NodeSpec::service)
                .toList();
        Set<String> knownServices = new LinkedHashSet<>();
        specs.forEach(spec -> knownServices.add(spec.service()));

        List<String> enabled = resolveEnabled(enabledRaw, appServices, knownServices);
        Set<String> enabledSet = new LinkedHashSet<>(enabled);

        List<TopologyNodeView> nodes = new ArrayList<>(specs.size()
                + (properties.getSidecars() == null ? 0 : properties.getSidecars().size()));
        for (NodeSpec spec : specs) {
            nodes.add(toNode(spec, includeSidecars, enabledSet, docker, storedJdk, nowEpochSeconds));
        }
        for (String sidecar : properties.getSidecars()) {
            NodeSpec spec = new NodeSpec(sidecar, sidecar, sidecar, sidecarTier(sidecar),
                    true, NodeKind.SIDECAR);
            nodes.add(toNode(spec, includeSidecars, enabledSet, docker, storedJdk, nowEpochSeconds));
        }

        return new TopologyView(now.toString(), enabled, includeSidecars,
                List.copyOf(nodes), edges(specs));
    }

    // ---------------------------------------------------------------- topology

    private List<NodeSpec> mainSpecs() {
        int clusterNodes = properties.getClusterNodes();
        List<NodeSpec> specs = new ArrayList<>();
        specs.add(new NodeSpec(container("nginx"), "nginx", "Nginx", TIER_EDGE, false,
                NodeKind.EDGE));

        int appCount = Math.max(clusterNodes, configuredAppCount());
        for (int index = 1; index <= appCount; index++) {
            String service = "app-" + index;
            specs.add(new NodeSpec(container(service), service, service, TIER_JAVA, false,
                    NodeKind.APP));
        }
        for (int index = 1; index <= clusterNodes; index++) {
            String service = "redis-" + index;
            specs.add(new NodeSpec(container(service), service, service, TIER_REDIS, false,
                    NodeKind.REDIS));
        }
        for (int index = 1; index <= clusterNodes; index++) {
            String service = "sentinel-" + index;
            specs.add(new NodeSpec(container(service), service, service, TIER_SENTINEL, false,
                    NodeKind.UNWIRED));
        }
        for (int index = 1; index <= clusterNodes; index++) {
            String service = "mysql-" + index;
            specs.add(new NodeSpec(container(service), service, service, TIER_MYSQL, false,
                    NodeKind.MYSQL));
        }
        specs.add(new NodeSpec(container("mysql-router"), "mysql-router", "MySQL Router",
                TIER_ROUTER, false, NodeKind.UNWIRED));
        specs.add(new NodeSpec(container("rabbitmq"), "rabbitmq", "RabbitMQ", TIER_MQ, false,
                NodeKind.MQ));
        specs.add(new NodeSpec(container("toxiproxy"), "toxiproxy", "Toxiproxy", TIER_PROXY,
                false, NodeKind.UNWIRED));
        return List.copyOf(specs);
    }

    private String container(String service) {
        return properties.getContainerPrefix() + service;
    }

    /**
     * App nodes follow the B4 instance list when it is wider than the default
     * three-node topology; {@code lab.precheck.app-containers} is the fallback
     * when the instance list has been narrowed.
     */
    private int configuredAppCount() {
        int fromInstance = 0;
        for (String name : instanceProperties.getNames()) {
            if (name != null && name.matches("app-[0-9]+")) {
                fromInstance++;
            }
        }
        return Math.max(fromInstance, precheckProperties.appContainerNames().size());
    }

    private static String sidecarTier(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        if (lower.contains("redis")) {
            return TIER_REDIS;
        }
        if (lower.contains("mysql")) {
            return TIER_MYSQL;
        }
        return TIER_EDGE;
    }

    private List<TopologyEdgeView> edges(List<NodeSpec> specs) {
        List<String> apps = specs.stream()
                .filter(spec -> TIER_JAVA.equals(spec.tier()))
                .map(NodeSpec::id)
                .toList();
        String nginx = specs.get(0).id();
        String router = container("mysql-router");
        String rabbit = container("rabbitmq");
        String toxiproxy = container("toxiproxy");

        List<TopologyEdgeView> edges = new ArrayList<>();
        for (String app : apps) {
            edges.add(new TopologyEdgeView(nginx, app, KIND_HTTP));
        }
        for (String app : apps) {
            for (int index = 1; index <= properties.getClusterNodes(); index++) {
                edges.add(new TopologyEdgeView(app, container("redis-" + index), KIND_REDIS));
            }
            for (int index = 1; index <= properties.getClusterNodes(); index++) {
                edges.add(new TopologyEdgeView(app, container("sentinel-" + index),
                        KIND_SENTINEL));
            }
            edges.add(new TopologyEdgeView(app, router, KIND_MYSQL));
            edges.add(new TopologyEdgeView(app, rabbit, KIND_AMQP));
            edges.add(new TopologyEdgeView(app, toxiproxy, KIND_PROXY));
        }
        return List.copyOf(edges);
    }

    // ------------------------------------------------------------------ nodes

    private TopologyNodeView toNode(NodeSpec spec, boolean includeSidecars,
                                    Set<String> enabledSet, DockerSnapshot docker,
                                    Map<String, Integer> storedJdk, long nowEpochSeconds) {
        List<String> notes = new ArrayList<>();
        ContainerView container = docker.containers().get(spec.container());
        String health = health(spec, container, docker.available(), notes);
        String dataQuality;
        TopologyMetricsView metrics;
        String jdkVersion = null;
        String observability = OBSERVABILITY_UNKNOWN;
        String imageTag = null;
        String imageId = null;

        if (spec.kind() == NodeKind.SIDECAR) {
            dataQuality = QUALITY_UNWIRED;
            metrics = TopologyMetricsView.empty(null);
            notes.add("exporter/sidecar container: no topology metric source");
        } else if (!docker.available()) {
            dataQuality = QUALITY_NO_DATA;
            metrics = TopologyMetricsView.empty(null);
        } else if (spec.kind() == NodeKind.UNWIRED) {
            // Router/Sentinel/Toxiproxy have no series even while running; keep
            // the verdict UNWIRED so the UI never mistakes it for a failed poll.
            dataQuality = QUALITY_UNWIRED;
            metrics = TopologyMetricsView.empty(null);
            notes.add(unwiredNote(spec.tier()));
        } else if (!isRunning(container)) {
            dataQuality = QUALITY_NO_DATA;
            metrics = TopologyMetricsView.empty(null);
        } else if (spec.kind() == NodeKind.APP) {
            Facts facts = readFacts(spec.container(), notes);
            if (facts != null) {
                imageTag = tagOf(facts.facts().image());
                imageId = facts.facts().imageId();
            }
            Integer jdk = storedJdk.get(spec.service());
            if (jdk == null && imageTag != null) {
                jdk = jdkOfTag(imageTag);
            }
            if (jdk != null) {
                jdkVersion = String.valueOf(jdk);
                observability = jdk == 8 ? OBSERVABILITY_LIMITED : OBSERVABILITY_FULL;
                if (jdk == 8) {
                    notes.add("JDK 8 shell: JAVA_TOOL_OPTIONS-based readback is limited "
                            + "(LIMITED-OBSERVABILITY)");
                }
            } else {
                notes.add("JDK version unknown (no stored config and no jdk tag on the image); "
                        + "observability class is UNKNOWN");
            }
            MetricResult result = appMetrics(spec.service(), nowEpochSeconds);
            metrics = result.metrics();
            dataQuality = result.quality();
            notes.addAll(result.notes());
            if (metrics.memUsedBytes() == null) {
                notes.add("container memUsed/memLimit unwired (cAdvisor has no per-container "
                        + "series); heap values are JVM-side");
            }
        } else if (spec.kind() == NodeKind.REDIS) {
            MetricResult result = redisMetrics(spec.service(), nowEpochSeconds);
            metrics = result.metrics();
            dataQuality = result.quality();
            notes.addAll(result.notes());
        } else if (spec.kind() == NodeKind.MYSQL) {
            MetricResult result = mysqlMetrics(spec.service(), nowEpochSeconds);
            metrics = result.metrics();
            dataQuality = result.quality();
            notes.addAll(result.notes());
        } else if (spec.kind() == NodeKind.EDGE) {
            MetricResult result = nginxMetrics(nowEpochSeconds);
            metrics = result.metrics();
            dataQuality = result.quality();
            notes.addAll(result.notes());
        } else if (spec.kind() == NodeKind.MQ) {
            MetricResult result = rabbitMetrics(nowEpochSeconds);
            metrics = result.metrics();
            dataQuality = result.quality();
            notes.addAll(result.notes());
        } else {
            dataQuality = QUALITY_NO_DATA;
            metrics = TopologyMetricsView.empty(null);
        }

        boolean enabled = spec.sidecar() || !toggleable(spec.tier())
                || enabledSet.contains(spec.service());
        boolean hidden = spec.sidecar() && !includeSidecars;
        return new TopologyNodeView(spec.id(), spec.service(), spec.label(), spec.tier(),
                enabled, hidden, health, observability, jdkVersion, imageTag, imageId,
                dataQuality, metrics, List.copyOf(notes));
    }

    private static boolean toggleable(String tier) {
        return TIER_JAVA.equals(tier) || TIER_REDIS.equals(tier) || TIER_MYSQL.equals(tier);
    }

    private String health(NodeSpec spec, ContainerView container, boolean dockerAvailable,
                          List<String> notes) {
        if (!dockerAvailable) {
            notes.add("Docker is unavailable: container state could not be read");
            return HEALTH_UNKNOWN;
        }
        if (container == null) {
            notes.add("container " + spec.container() + " is missing (never started or removed)");
            return HEALTH_NOT_RUNNING;
        }
        String state = container.state() == null ? "" : container.state().toLowerCase(Locale.ROOT);
        if (!"running".equals(state)) {
            if ("restarting".equals(state)) {
                notes.add("container " + spec.container() + " is restarting");
                return HEALTH_UNKNOWN;
            }
            notes.add("container " + spec.container() + " is not running (state="
                    + container.state() + ")");
            return HEALTH_NOT_RUNNING;
        }
        String health = container.health() == null ? "none"
                : container.health().toLowerCase(Locale.ROOT);
        return switch (health) {
            case "healthy" -> HEALTH_UP;
            case "unhealthy" -> HEALTH_DOWN;
            case "starting" -> HEALTH_UNKNOWN;
            default -> HEALTH_UP;
        };
    }

    private static boolean isRunning(ContainerView container) {
        return container != null && container.state() != null
                && "running".equalsIgnoreCase(container.state());
    }

    private String unwiredNote(String tier) {
        return switch (tier) {
            case TIER_ROUTER -> "MySQL Router exposes no Prometheus series; add a scrape/exporter "
                    + "in B5-04";
            case TIER_SENTINEL -> "Redis Sentinel speaks RESP and has no Prometheus exporter "
                    + "(B5-04)";
            case TIER_PROXY -> "Toxiproxy has no Prometheus exporter (B5-04)";
            default -> "no metric source is wired for this tier (B5-04)";
        };
    }

    // -------------------------------------------------------------- metrics

    private MetricResult appMetrics(String service, long nowEpochSeconds) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        String selector = "job=\"" + properties.getAppJob() + "\",instance=\""
                + service + properties.getAppInstanceSuffix() + "\"";
        String pool = "name=\"" + properties.getPoolName() + "\"";
        String window = properties.getRateWindow();

        query("process_cpu_usage{" + selector + "}", "cpu", nowEpochSeconds, samples, failures);
        query("sum(jvm_memory_used_bytes{area=\"" + properties.getAppHeapArea() + "\","
                + selector + "})", "heapUsed", nowEpochSeconds, samples, failures);
        query("sum(jvm_memory_max_bytes{area=\"" + properties.getAppHeapArea() + "\","
                + selector + "})", "heapMax", nowEpochSeconds, samples, failures);
        query("jvm_threads_live_threads{" + selector + "}", "threads", nowEpochSeconds,
                samples, failures);
        query("executor_active_threads{" + selector + "," + pool + "}", "active",
                nowEpochSeconds, samples, failures);
        query("executor_pool_core_threads{" + selector + "," + pool + "}", "poolCore",
                nowEpochSeconds, samples, failures);
        query("executor_pool_size_threads{" + selector + "," + pool + "}", "poolSize",
                nowEpochSeconds, samples, failures);
        query("executor_pool_max_threads{" + selector + "," + pool + "}", "poolMax",
                nowEpochSeconds, samples, failures);
        query("executor_queued_tasks{" + selector + "," + pool + "}", "poolQueue",
                nowEpochSeconds, samples, failures);
        query("executor_rejected_total{" + selector + "," + pool + "}", "poolRejected",
                nowEpochSeconds, samples, failures);
        query("sum(rate(http_server_requests_seconds_count{" + selector + "}[" + window + "]))",
                "requests", nowEpochSeconds, samples, failures);
        query("sum(rate(http_server_requests_seconds_sum{" + selector + "}[" + window + "]))"
                + "/sum(rate(http_server_requests_seconds_count{" + selector + "}["
                + window + "]))", "avgSeconds", nowEpochSeconds, samples, failures);
        query("histogram_quantile(0.95, sum by (le)(rate(http_server_requests_seconds_bucket{"
                + selector + "}[" + window + "])))", "p95Seconds", nowEpochSeconds, samples,
                failures);

        Double heapLimit = value(samples, "heapMax");
        if (heapLimit != null && heapLimit <= 0) {
            heapLimit = null;
        }
        Double poolCore = value(samples, "poolCore");
        if (poolCore == null) {
            poolCore = value(samples, "poolSize");
        }
        Double avgSeconds = value(samples, "avgSeconds");
        Double p95Seconds = value(samples, "p95Seconds");
        TopologyMetricsView metrics = new TopologyMetricsView(
                scaled(value(samples, "cpu"), 100.0),
                value(samples, "heapUsed"),
                heapLimit,
                null,
                null,
                value(samples, "threads"),
                value(samples, "active"),
                poolCore,
                value(samples, "poolMax"),
                value(samples, "poolQueue"),
                value(samples, "poolRejected"),
                value(samples, "requests"),
                scaled(avgSeconds, 1000.0),
                scaled(p95Seconds, 1000.0),
                updatedAt(samples));

        String quality = quality(samples, APP_REQUIRED, failures);
        List<String> notes = new ArrayList<>(failureNotes(failures));
        return new MetricResult(metrics, quality, notes);
    }

    private MetricResult redisMetrics(String service, long nowEpochSeconds) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        String selector = "job=\"" + properties.getRedisJob() + "\","
                + properties.getRedisNodeLabel() + "=\"" + service + "\"";
        query("redis_up{" + selector + "}", "up", nowEpochSeconds, samples, failures);
        query("redis_memory_used_bytes{" + selector + "}", "memory", nowEpochSeconds, samples,
                failures);

        Double up = value(samples, "up");
        List<String> notes = new ArrayList<>(failureNotes(failures));
        if (up != null) {
            notes.add("redis_up=" + format(up));
        }
        notes.add("container memLimit/CPU unwired; memUsedBytes is redis_memory_used_bytes");
        TopologyMetricsView metrics = new TopologyMetricsView(
                null, null, null, value(samples, "memory"), null,
                null, null, null, null, null, null, null, null, null, updatedAt(samples));
        String quality = quality(samples, Set.of("up", "memory"), failures);
        return new MetricResult(metrics, quality, notes);
    }

    private MetricResult mysqlMetrics(String service, long nowEpochSeconds) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        query("mysql_up{job=\"" + properties.getMysqlJob() + "\","
                        + properties.getMysqlMemberLabel() + "=\"" + service + "\"}",
                "up", nowEpochSeconds, samples, failures);
        Double up = value(samples, "up");
        List<String> notes = new ArrayList<>(failureNotes(failures));
        if (up != null) {
            notes.add("mysql_up=" + format(up) + "; only reachability is exported (B5-04 adds "
                    + "node-level metrics)");
        }
        return new MetricResult(TopologyMetricsView.empty(updatedAt(samples)),
                quality(samples, Set.of("up"), failures), notes);
    }

    private MetricResult nginxMetrics(long nowEpochSeconds) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        query("nginx_connections_active{job=\"" + properties.getNginxJob() + "\"}",
                "connections", nowEpochSeconds, samples, failures);
        Double connections = value(samples, "connections");
        List<String> notes = new ArrayList<>(failureNotes(failures));
        if (connections != null) {
            notes.add("nginx_connections_active=" + format(connections));
        }
        return new MetricResult(TopologyMetricsView.empty(updatedAt(samples)),
                quality(samples, Set.of("connections"), failures), notes);
    }

    private MetricResult rabbitMetrics(long nowEpochSeconds) {
        Map<String, Sample> samples = new LinkedHashMap<>();
        List<String> failures = new ArrayList<>();
        query("sum(rabbitmq_queue_messages_ready{job=\"" + properties.getRabbitJob() + "\"})",
                "ready", nowEpochSeconds, samples, failures);
        Double ready = value(samples, "ready");
        List<String> notes = new ArrayList<>(failureNotes(failures));
        if (ready != null) {
            notes.add("rabbitmq_queue_messages_ready=" + format(ready));
        }
        return new MetricResult(TopologyMetricsView.empty(updatedAt(samples)),
                quality(samples, Set.of("ready"), failures), notes);
    }

    private void query(String promql, String key, long atEpochSeconds,
                       Map<String, Sample> samples, List<String> failures) {
        try {
            PrometheusMatrix matrix = prometheusGateway.queryInstant(promql, atEpochSeconds);
            Sample sample = lastSample(matrix);
            if (sample != null) {
                samples.put(key, sample);
            }
        } catch (RuntimeException e) {
            failures.add(key + ": " + safeMessage(e));
        }
    }

    private static Sample lastSample(PrometheusMatrix matrix) {
        if (matrix == null || matrix.series() == null) {
            return null;
        }
        Sample latest = null;
        for (PrometheusSeries series : matrix.series()) {
            if (series == null || series.points() == null) {
                continue;
            }
            for (PrometheusPoint point : series.points()) {
                if (Double.isFinite(point.value())
                        && (latest == null || point.timestamp() >= latest.timestamp())) {
                    latest = new Sample(point.value(), point.timestamp());
                }
            }
        }
        return latest;
    }

    private static String quality(Map<String, Sample> samples, Set<String> required,
                                  List<String> failures) {
        long present = required.stream().filter(samples::containsKey).count();
        if (present == 0) {
            return QUALITY_NO_DATA;
        }
        if (present < required.size() || !failures.isEmpty()) {
            return QUALITY_PARTIAL;
        }
        return QUALITY_OK;
    }

    private static List<String> failureNotes(List<String> failures) {
        if (failures.isEmpty()) {
            return List.of();
        }
        if (failures.size() <= 3) {
            return List.copyOf(failures);
        }
        return List.of(String.join("; ", failures.subList(0, 3))
                + "; +" + (failures.size() - 3) + " more");
    }

    private static Long updatedAt(Map<String, Sample> samples) {
        double latest = Double.NEGATIVE_INFINITY;
        boolean found = false;
        for (Sample sample : samples.values()) {
            if (Double.isFinite(sample.timestamp())) {
                latest = found ? Math.max(latest, sample.timestamp()) : sample.timestamp();
                found = true;
            }
        }
        return found ? (long) latest : null;
    }

    private static Double value(Map<String, Sample> samples, String key) {
        Sample sample = samples.get(key);
        return sample == null ? null : sample.value();
    }

    private static Double scaled(Double value, double factor) {
        return value == null ? null : value * factor;
    }

    private static String format(double value) {
        if (value == Math.rint(value)) {
            return String.valueOf((long) value);
        }
        return String.valueOf(value);
    }

    private static String safeMessage(RuntimeException e) {
        String message = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
        String collapsed = message.replaceAll("\\s+", " ").trim();
        return collapsed.length() <= MAX_NOTE_LENGTH
                ? collapsed : collapsed.substring(0, MAX_NOTE_LENGTH) + "...";
    }

    // -------------------------------------------------------------- selection

    private List<String> resolveEnabled(String enabledRaw, List<String> appServices,
                                        Set<String> knownServices) {
        if (enabledRaw != null && !enabledRaw.isBlank()) {
            return normalize(enabledRaw, knownServices);
        }
        try {
            List<RunRecord> runs = runLedgerRepository.listRuns(null, null, true,
                    MAX_LEDGER_RUNS_SCANNED, 0);
            for (RunRecord run : runs) {
                if (run.nodes() == null || run.nodes().isBlank()) {
                    continue;
                }
                List<String> selected = normalize(run.nodes(), knownServices);
                if (!selected.isEmpty()) {
                    return selected;
                }
            }
        } catch (RuntimeException ignored) {
            // A degraded ledger must not take the topology view down; the
            // documented fallback is "all configured app names".
        }
        return List.copyOf(appServices);
    }

    private List<String> normalize(String raw, Set<String> knownServices) {
        LinkedHashSet<String> selected = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String value = part.trim();
            if (value.isEmpty()) {
                continue;
            }
            if (value.startsWith(properties.getContainerPrefix())) {
                value = value.substring(properties.getContainerPrefix().length());
            }
            if (!value.matches(SERVICE_NAME) || !knownServices.contains(value)) {
                continue;
            }
            selected.add(value);
        }
        return List.copyOf(selected);
    }

    // ---------------------------------------------------------------- docker

    private DockerSnapshot readContainers() {
        if (containerGateway == null) {
            return new DockerSnapshot(false, Map.of());
        }
        try {
            Map<String, ContainerView> byName = new HashMap<>();
            for (ContainerView container : containerGateway.listLabContainers()) {
                if (container != null && container.name() != null) {
                    byName.put(container.name(), container);
                }
            }
            return new DockerSnapshot(true, Map.copyOf(byName));
        } catch (RuntimeException e) {
            return new DockerSnapshot(false, Map.of());
        }
    }

    private Map<String, Integer> readStoredJdk() {
        Map<String, Integer> jdk = new HashMap<>();
        if (instanceConfigRepository == null) {
            return jdk;
        }
        try {
            for (StoredInstanceConfig stored : instanceConfigRepository.list()) {
                if (stored == null || stored.configJson() == null) {
                    continue;
                }
                try {
                    InstanceConfig config = objectMapper.readValue(stored.configJson(),
                            InstanceConfig.class);
                    if (config != null && config.jdkVersion() != null) {
                        jdk.put(stored.instance(), config.jdkVersion());
                    }
                } catch (JsonProcessingException ignored) {
                    // One unreadable row must not blank every other instance.
                }
            }
        } catch (RuntimeException ignored) {
            // Ledger down: fall back to the image tag for the JDK readback.
        }
        return jdk;
    }

    private Facts readFacts(String container, List<String> notes) {
        if (containerGateway == null) {
            notes.add("Docker gateway is unavailable: image tag/id readback is unknown");
            return null;
        }
        try {
            Optional<ContainerFacts> facts = containerGateway.inspectLabContainerFacts(container);
            if (facts == null || facts.isEmpty()) {
                notes.add("container facts unavailable for " + container
                        + "; image tag/id readback is unknown");
                return null;
            }
            return new Facts(facts.get());
        } catch (RuntimeException e) {
            notes.add("container facts unavailable for " + container + " ("
                    + e.getClass().getSimpleName() + ")");
            return null;
        }
    }

    private static String tagOf(String image) {
        if (image == null || image.isBlank() || image.contains("@")) {
            return null;
        }
        int slash = image.lastIndexOf('/');
        int colon = image.lastIndexOf(':');
        return colon > slash ? image.substring(colon + 1) : null;
    }

    private static Integer jdkOfTag(String tag) {
        String lower = tag.toLowerCase(Locale.ROOT);
        if (lower.contains("jdk8") || lower.contains("jdk-8")) {
            return 8;
        }
        if (lower.contains("jdk21") || lower.contains("jdk-21")) {
            return 21;
        }
        return null;
    }

    // ----------------------------------------------------------------- types

    private enum NodeKind {
        EDGE, APP, REDIS, MYSQL, UNWIRED, MQ, SIDECAR
    }

    private record NodeSpec(String id, String service, String label, String tier,
                            boolean sidecar, NodeKind kind) {

        String container() {
            return id;
        }
    }

    private record DockerSnapshot(boolean available, Map<String, ContainerView> containers) {
    }

    private record Facts(ContainerFacts facts) {
    }

    private record Sample(double value, double timestamp) {
    }

    private record MetricResult(TopologyMetricsView metrics, String quality, List<String> notes) {
    }

    private record CacheEntry(String key, long expiresAtMillis, TopologyView view) {
    }
}
