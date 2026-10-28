package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Scenario;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Stage2 B2-02a launch planner: turns a manifest scenario + run id + user
 * parameters into an immutable {@link BenchLaunchPlan}. It is pure validation
 * and rendering — no Docker client, no filesystem probe, no shell — so the
 * whole allow-list contract is unit-testable.
 *
 * <p>Only {@code views.ui} scenarios may be launched (currently smoke/load/
 * spike). {@code stress}/{@code soak} are rejected as CLI-only, {@code traffic}
 * as service-only, and {@code d7}/{@code ha-failover}/{@code db-failover}/
 * {@code cache-lab} as CLI-only; unknown names are rejected with the UI
 * whitelist. There is no "lenient passthrough": a parameter is either a name
 * the scenario declares under {@code params} with a compatible type, or the
 * plan is refused.</p>
 *
 * <p>The image, argv prefix, mounts, network and upper bounds come from
 * {@link BenchExecProperties} only. {@code RUN_ID}, {@code BASE_URL} and the
 * three {@code K6_PROMETHEUS_RW_*} names are forced values and cannot appear in
 * the request parameters.</p>
 *
 * <p>Stage2 B2-03a adds two controlled <em>traffic selectors</em>:
 * {@code TRAFFIC_MODE} ({@code via-nginx} by default, or
 * {@code target-instance}) and {@code TARGET_INSTANCE}. They are runner keys,
 * not k6 scenario parameters: only the mode enum and a service name from the
 * configured fixed table are accepted, and both are always re-rendered into the
 * container environment. {@code BASE_URL} is resolved from the mode (the Nginx
 * entry or {@code http://<target>:<port>}) and still cannot be supplied by a
 * request; there is no request field for a URL, host, port or network target.</p>
 */
@Component
public class BenchLaunchPlanner {

    public static final String RUN_ID = "RUN_ID";
    public static final String BASE_URL = "BASE_URL";
    /** Traffic selector: {@code via-nginx} (default) or {@code target-instance}. */
    public static final String TRAFFIC_MODE = "TRAFFIC_MODE";
    /** Traffic selector: a service name from the configured fixed table. */
    public static final String TARGET_INSTANCE = "TARGET_INSTANCE";
    public static final String MODE_VIA_NGINX = "via-nginx";
    public static final String MODE_TARGET_INSTANCE = "target-instance";
    /** Instance tag used when the run goes through the Nginx upstream. */
    public static final String NGINX_INSTANCE = "nginx";
    public static final String PROMETHEUS_RW_SERVER_URL = "K6_PROMETHEUS_RW_SERVER_URL";
    public static final String PROMETHEUS_RW_TREND_STATS = "K6_PROMETHEUS_RW_TREND_STATS";
    public static final String PROMETHEUS_RW_PUSH_INTERVAL = "K6_PROMETHEUS_RW_PUSH_INTERVAL";

    /**
     * Mirrors {@code code/lab/k6/run.sh:112-113}; kept as constants instead of
     * configuration keys on purpose (they are runner invariants, not lab
     * knobs). Changing them means changing both places.
     */
    public static final String DEFAULT_TREND_STATS = "p(95),p(99),max";
    public static final String DEFAULT_PUSH_INTERVAL = "5s";

    private static final Set<String> FORCED_KEYS = Set.of(RUN_ID, BASE_URL, PROMETHEUS_RW_SERVER_URL,
            PROMETHEUS_RW_TREND_STATS, PROMETHEUS_RW_PUSH_INTERVAL);
    private static final Set<String> TRAFFIC_KEYS = Set.of(TRAFFIC_MODE, TARGET_INSTANCE);
    private static final Set<String> TRAFFIC_MODES = Set.of(MODE_VIA_NGINX, MODE_TARGET_INSTANCE);
    private static final int TRAFFIC_SELECTOR_MAX_LENGTH = 64;
    /** One fixed target: a plain Docker service name, never a URL/host/port. */
    private static final Pattern SERVICE_NAME = Pattern.compile("[a-z][a-z0-9-]{0,62}");
    private static final Pattern HTTP_URL = Pattern.compile("https?://\\S+");
    private static final int MIN_PORT = 1;
    private static final int MAX_PORT = 65535;
    /**
     * Run metadata, not k6 parameters: accepted on purpose, validated as a short
     * string and never rendered into the container environment. {@code note} is
     * the operator label the console/smoke use to find their own runs.
     */
    private static final Set<String> METADATA_KEYS = Set.of("note", "source");
    private static final int METADATA_MAX_LENGTH = 200;
    private static final String LABEL_APP = "lab";
    private static final String LABEL_ROLE = "lab.role";
    private static final String LABEL_RUN_ID = "lab.run-id";
    private static final String LABEL_SCENARIO = "lab.scenario";
    private static final Pattern DURATION_UNIT =
            Pattern.compile("(\\d+(?:\\.\\d+)?)(ns|us|µs|ms|s|m|h)");
    private static final Pattern BARE_SECONDS = Pattern.compile("\\d+");

    private final BenchScenarioCatalog catalog;
    private final BenchExecProperties properties;
    private final ObjectMapper objectMapper;

    public BenchLaunchPlanner(BenchScenarioCatalog catalog, BenchExecProperties properties,
                              ObjectMapper objectMapper) {
        this.catalog = catalog;
        this.properties = properties;
        this.objectMapper = objectMapper;
    }

    /** Loads the manifest through the catalog and plans from its snapshot. */
    public BenchLaunchPlan plan(String runId, String scenarioName, Map<String, Object> params) {
        return plan(catalog.load(), runId, scenarioName, params);
    }

    /**
     * Plans from an already loaded snapshot. This overload does not touch the
     * catalog, so it is the one unit tests exercise.
     *
     * <p>Validation order: scenario whitelist → run id → parameters (forced
     * keys, unknown keys, types, bounds) → configuration
     * ({@code scripts-host-dir}). A blank {@code scripts-host-dir} throws
     * {@link BenchUnavailableException} (503/50303), everything else throws
     * {@link BenchLaunchRejectedException}.</p>
     */
    public BenchLaunchPlan plan(Snapshot snapshot, String runId, String scenarioName,
                                Map<String, Object> params) {
        return plan(snapshot, runId, scenarioName, params, properties.getScriptsHostDir());
    }

    /**
     * Plans with an already resolved host scripts directory (B2-02b-2a). The
     * run lifecycle resolves the real host path first
     * ({@link BenchScriptsHostDirResolver}) and passes it here, so a launch
     * never depends on {@code scripts-host-dir} being filled in by an operator.
     *
     * <p>Validation semantics are unchanged: the scenario whitelist, run id and
     * parameter gates run in the same order, and a blank host directory is still
     * {@link BenchUnavailableException} (503/50303) - this overload only supplies
     * the value the resolver already found.</p>
     */
    public BenchLaunchPlan plan(Snapshot snapshot, String runId, String scenarioName,
                                Map<String, Object> params, String scriptsHostDir) {
        if (snapshot == null) {
            throw new BenchLaunchRejectedException("scenario snapshot is required to plan a k6 launch");
        }
        String scenario = requireScenarioName(scenarioName);
        Scenario definition = uiScenario(snapshot, scenario);
        String safeRunId = sanitizeRunId(runId);
        Map<String, Object> effective = effectiveParams(scenario, definition, params);
        BenchLaunchPlan.Traffic traffic = resolveTraffic(params);

        String hostDir = scriptsHostDir == null ? "" : scriptsHostDir.trim();
        if (hostDir.isEmpty()) {
            throw new BenchUnavailableException("lab.bench.exec.scripts-host-dir is blank: the host "
                    + "directory mounted at " + properties.getScriptsMount() + " is unknown, so the "
                    + "plan is refused instead of mounting an empty source; set env "
                    + "LAB_BENCH_EXEC_SCRIPTS_HOST_DIR (B2-02b resolves the real host path)");
        }

        Map<String, String> env = renderEnv(definition, effective, safeRunId, traffic);
        String reportPath = join(properties.getReportsMount(), safeRunId, properties.getSummaryFile());
        String scriptPath = properties.getScriptsMount() + "/" + definition.script();
        List<String> argv = List.of(
                "run",
                "--quiet",
                "-o", "experimental-prometheus-rw",
                "--summary-export", reportPath,
                scriptPath);
        List<BenchLaunchPlan.Bind> binds = List.of(
                new BenchLaunchPlan.Bind(hostDir, properties.getScriptsMount(), true),
                new BenchLaunchPlan.Bind(properties.getReportsVolume(), properties.getReportsMount(), false));
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put(LABEL_APP, "java-learn");
        labels.put(LABEL_ROLE, "k6");
        labels.put(LABEL_RUN_ID, safeRunId);
        labels.put(LABEL_SCENARIO, scenario);
        labels.put(K6ContainerGateway.LABEL_TRAFFIC_MODE, traffic.mode());
        labels.put(K6ContainerGateway.LABEL_TARGET_INSTANCE, traffic.targetInstance());
        return new BenchLaunchPlan(safeRunId, scenario, properties.getContainerPrefix() + safeRunId,
                properties.getK6Image(), argv, env, binds, labels, properties.getNetwork(),
                reportPath, reportPath, traffic);
    }

    /**
     * Parses a k6 duration ({@code 30s}, {@code 10m}, {@code 1h}, {@code 1m30s},
     * or a bare number of seconds) into seconds. Exposed package-private so the
     * test can pin the accepted grammar; rejects anything else.
     */
    static double parseDurationSeconds(String parameter, String text) {
        String label = "parameter '" + parameter + "'";
        if (text == null || text.isEmpty()) {
            throw new BenchLaunchRejectedException(label + " must be a k6 duration "
                    + "(e.g. 30s, 10m, 1h, 1m30s); got " + text);
        }
        if (BARE_SECONDS.matcher(text).matches()) {
            return Double.parseDouble(text);
        }
        Matcher matcher = DURATION_UNIT.matcher(text);
        double total = 0;
        int index = 0;
        while (matcher.find()) {
            if (matcher.start() != index) {
                break;
            }
            total += Double.parseDouble(matcher.group(1)) * unitSeconds(matcher.group(2));
            index = matcher.end();
        }
        if (index != text.length() || index == 0) {
            throw new BenchLaunchRejectedException(label + " must be a k6 duration "
                    + "(e.g. 30s, 10m, 1h, 1m30s); got \"" + text + "\"");
        }
        return total;
    }

    private static double unitSeconds(String unit) {
        return switch (unit) {
            case "ns" -> 1e-9;
            case "us", "µs" -> 1e-6;
            case "ms" -> 1e-3;
            case "s" -> 1;
            case "m" -> 60;
            case "h" -> 3600;
            default -> throw new IllegalArgumentException("unknown duration unit " + unit);
        };
    }

    // ----------------------------------------------------------- scenario gate

    private static String requireScenarioName(String scenarioName) {
        if (scenarioName == null || scenarioName.isBlank()) {
            throw new BenchLaunchRejectedException("scenario name must not be blank");
        }
        return scenarioName.trim();
    }

    private static Scenario uiScenario(Snapshot snapshot, String name) {
        Map<String, List<String>> views = snapshot.views() == null ? Map.of() : snapshot.views();
        List<String> ui = views.getOrDefault("ui", List.of());
        List<String> bench = views.getOrDefault("bench", List.of());
        List<String> run = views.getOrDefault("run", List.of());
        Map<String, Scenario> scenarios = snapshot.scenarios() == null ? Map.of() : snapshot.scenarios();
        Scenario definition = scenarios.get(name);
        String whitelist = "UI whitelist (views.ui): " + (ui.isEmpty() ? "(none)" : String.join(", ", ui));
        if (definition == null) {
            throw new BenchLaunchRejectedException("unknown scenario '" + name + "'; " + whitelist);
        }
        if (ui.contains(name)) {
            return definition;
        }
        if (definition.serviceOnly()) {
            throw new BenchLaunchRejectedException("scenario '" + name + "' is service-only: it runs "
                    + "as a compose service, not as a bench run; " + whitelist);
        }
        if (bench.contains(name)) {
            throw new BenchLaunchRejectedException("scenario '" + name + "' is CLI-only: it is in "
                    + "views.bench but not in views.ui (stress/soak stay CLI-only by the confirmed "
                    + "Stage2 decision); " + whitelist);
        }
        if (run.contains(name)) {
            throw new BenchLaunchRejectedException("scenario '" + name + "' is CLI-only: it is in "
                    + "views.run but not in views.ui (run it with code/lab/k6/run.sh); " + whitelist);
        }
        throw new BenchLaunchRejectedException("scenario '" + name + "' is not launchable from the "
                + "console; " + whitelist);
    }

    private static String sanitizeRunId(String runId) {
        String trimmed = runId == null ? "" : runId.trim();
        if (trimmed.isEmpty()) {
            throw new BenchLaunchRejectedException("runId must not be blank: it becomes RUN_ID, the "
                    + "container name suffix and the report directory");
        }
        StringBuilder safe = new StringBuilder(trimmed.length());
        for (int i = 0; i < trimmed.length(); i++) {
            char c = trimmed.charAt(i);
            boolean keep = (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z')
                    || (c >= '0' && c <= '9') || c == '.' || c == '_' || c == '-';
            safe.append(keep ? c : '-');
        }
        String result = safe.toString();
        if (!Character.isLetterOrDigit(result.charAt(0))) {
            throw new BenchLaunchRejectedException("runId '" + trimmed + "' does not start with a "
                    + "letter or digit after sanitising; refusing an unsafe container/path segment");
        }
        return result;
    }

    // ---------------------------------------------------------- parameter gate

    private Map<String, Object> effectiveParams(String scenario, Scenario definition,
                                                Map<String, Object> params) {
        Map<String, Object> effective = new LinkedHashMap<>(definition.params());
        if (params == null || params.isEmpty()) {
            return effective;
        }
        for (Map.Entry<String, Object> entry : params.entrySet()) {
            String key = entry.getKey();
            if (key == null || key.isBlank()) {
                throw new BenchLaunchRejectedException("parameter names must not be blank");
            }
            if (FORCED_KEYS.contains(key)) {
                throw new BenchLaunchRejectedException("parameter '" + key + "' is a forced value "
                        + "derived from the run and the configuration and cannot be overridden "
                        + "(forced: RUN_ID, BASE_URL, K6_PROMETHEUS_RW_*)");
            }
            if (METADATA_KEYS.contains(key)) {
                validateMetadata(key, entry.getValue());
                // Metadata stays in lab_run_param; it is never a k6 env value.
                continue;
            }
            if (TRAFFIC_KEYS.contains(key)) {
                // Validated by resolveTraffic: a controlled selector rendered
                // as forced env, never a scenario parameter.
                continue;
            }
            if (!definition.params().containsKey(key)) {
                throw new BenchLaunchRejectedException("unknown parameter '" + key + "' for scenario '"
                        + scenario + "'; accepted parameters: "
                        + String.join(", ", definition.params().keySet()));
            }
            validateOverride(scenario, key, definition.params().get(key), entry.getValue());
            effective.put(key, entry.getValue());
        }
        return effective;
    }

    private void validateMetadata(String key, Object value) {
        if (!(value instanceof String text)) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' is run metadata and "
                    + "must be a string; got " + describe(value));
        }
        if (text.isBlank()) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' is run metadata and "
                    + "must not be blank");
        }
        if (text.length() > METADATA_MAX_LENGTH) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' is run metadata and "
                    + "must be at most " + METADATA_MAX_LENGTH + " characters; got " + text.length());
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new BenchLaunchRejectedException("parameter '" + key + "' is run metadata and "
                        + "must not contain newlines or control characters");
            }
        }
    }

    private void validateOverride(String scenario, String key, Object fallback, Object value) {
        if (value == null) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' of scenario '" + scenario
                    + "' must not be null");
        }
        if (fallback instanceof Number) {
            if (!(value instanceof Number number)) {
                throw typeMismatch(scenario, key, "a number", fallback, value);
            }
            double numeric = number.doubleValue();
            if (!Double.isFinite(numeric)) {
                throw reject(scenario, key, "must be a finite number; got " + number);
            }
            if (numeric < 0) {
                throw reject(scenario, key, "must be >= 0; got " + number);
            }
            if (key.endsWith("_VUS") && numeric > properties.getMaxVus()) {
                throw reject(scenario, key, "exceeds lab.bench.exec.max-vus ("
                        + properties.getMaxVus() + "); got " + number);
            }
            return;
        }
        if (fallback instanceof String) {
            if (!(value instanceof String text)) {
                throw typeMismatch(scenario, key, "a string", fallback, value);
            }
            requireNoControlChars(scenario, key, text);
            if (isDurationKey(key)) {
                double seconds = parseDurationSeconds(key, text);
                if (seconds > properties.getMaxDurationSeconds()) {
                    throw reject(scenario, key, "exceeds lab.bench.exec.max-duration-seconds ("
                            + properties.getMaxDurationSeconds() + "s); got \"" + text + "\"");
                }
            }
            return;
        }
        if (fallback instanceof Boolean) {
            if (!(value instanceof Boolean)) {
                throw typeMismatch(scenario, key, "a boolean", fallback, value);
            }
            return;
        }
        if (fallback instanceof List<?> defaults) {
            if (!(value instanceof List<?> list)) {
                throw typeMismatch(scenario, key, "an array", fallback, value);
            }
            Class<?> elementKind = firstElementKind(defaults);
            if (elementKind == Map.class) {
                throw reject(scenario, key, "is an array of objects, which the controlled executor "
                        + "does not accept as a request parameter");
            }
            for (Object element : list) {
                if (element == null) {
                    throw reject(scenario, key, "must not contain null elements");
                }
                if (elementKind == Number.class && !(element instanceof Number)) {
                    throw reject(scenario, key, "expects an array of numbers; got element " + element);
                }
                if (elementKind == String.class && !(element instanceof String)) {
                    throw reject(scenario, key, "expects an array of strings; got element " + element);
                }
                if (element instanceof Number number) {
                    double numeric = number.doubleValue();
                    if (!Double.isFinite(numeric) || numeric < 0) {
                        throw reject(scenario, key, "must contain finite numbers >= 0; got " + element);
                    }
                } else if (element instanceof String text) {
                    requireNoControlChars(scenario, key, text);
                } else if (!(element instanceof Boolean)) {
                    throw reject(scenario, key, "supports scalar elements only; got " + element);
                }
            }
            return;
        }
        throw reject(scenario, key, "has an unsupported manifest default type "
                + fallback.getClass().getSimpleName());
    }

    private static Class<?> firstElementKind(List<?> defaults) {
        for (Object element : defaults) {
            if (element instanceof Number) {
                return Number.class;
            }
            if (element instanceof String) {
                return String.class;
            }
            if (element instanceof Boolean) {
                return Boolean.class;
            }
            if (element instanceof Map) {
                return Map.class;
            }
        }
        return null;
    }

    static boolean isDurationKey(String key) {
        return key.endsWith("_DURATION") || key.endsWith("_RAMP_UP") || key.endsWith("_RAMP_DOWN");
    }

    private static void requireNoControlChars(String scenario, String key, String text) {
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw reject(scenario, key, "must not contain newlines or control characters");
            }
        }
    }

    private static BenchLaunchRejectedException typeMismatch(String scenario, String key,
                                                             String expected, Object fallback,
                                                             Object value) {
        return reject(scenario, key, "expects " + expected + " (manifest default " + fallback
                + "); got " + describe(value));
    }

    private static String describe(Object value) {
        if (value instanceof String text) {
            return "\"" + text + "\"";
        }
        return value.getClass().getSimpleName() + " " + value;
    }

    private static BenchLaunchRejectedException reject(String scenario, String key, String reason) {
        return new BenchLaunchRejectedException("parameter '" + key + "' of scenario '" + scenario
                + "' " + reason);
    }

    // ---------------------------------------------------------- traffic mode

    /**
     * Resolves the traffic mode (Stage2 B2-03a).
     *
     * <ul>
     *   <li>{@code via-nginx} (default): {@code BASE_URL} is the configured
     *       Nginx entry and {@code TARGET_INSTANCE} must be omitted — a target
     *       here would look like a directed run without being one.</li>
     *   <li>{@code target-instance}: {@code TARGET_INSTANCE} is required and
     *       must be a service name from {@code LAB_BENCH_EXEC_TARGET_INSTANCES};
     *       {@code BASE_URL} becomes {@code http://<target>:<port>}. There is
     *       no request field for a URL, host or port.</li>
     * </ul>
     *
     * <p>A broken fixed table or port is a configuration problem
     * ({@link BenchUnavailableException}, 503/50303), not a bad request.</p>
     */
    private BenchLaunchPlan.Traffic resolveTraffic(Map<String, Object> params) {
        Map<String, Object> request = params == null ? Map.of() : params;
        String mode = MODE_VIA_NGINX;
        if (request.containsKey(TRAFFIC_MODE)) {
            mode = trafficSelector(TRAFFIC_MODE, request.get(TRAFFIC_MODE));
            if (!TRAFFIC_MODES.contains(mode)) {
                throw new BenchLaunchRejectedException("unknown traffic mode '" + mode
                        + "'; accepted: " + MODE_VIA_NGINX + ", " + MODE_TARGET_INSTANCE);
            }
        }
        String target = null;
        if (request.containsKey(TARGET_INSTANCE)) {
            String requested = trafficSelector(TARGET_INSTANCE, request.get(TARGET_INSTANCE));
            target = requested.isBlank() ? null : requested;
        }
        List<String> instances = configuredTargetInstances();
        int port = configuredTargetPort();
        String upstream = upstream(instances, port);
        if (MODE_VIA_NGINX.equals(mode)) {
            if (target != null) {
                throw new BenchLaunchRejectedException("traffic mode '" + MODE_VIA_NGINX
                        + "' goes through the Nginx upstream, so TARGET_INSTANCE must be omitted; got '"
                        + target + "'. Send " + TRAFFIC_MODE + "=" + MODE_TARGET_INSTANCE
                        + " to pin one instance (a target here would look directed without being one)");
            }
            return new BenchLaunchPlan.Traffic(mode, NGINX_INSTANCE,
                    requireNginxBaseUrl(), false, false, upstream);
        }
        if (target == null) {
            throw new BenchLaunchRejectedException("traffic mode '" + MODE_TARGET_INSTANCE
                    + "' requires " + TARGET_INSTANCE + " from the fixed table ("
                    + String.join(", ", instances) + ")");
        }
        if (!instances.contains(target)) {
            throw new BenchLaunchRejectedException("unknown target instance '" + target
                    + "'; the fixed table comes from LAB_BENCH_EXEC_TARGET_INSTANCES: "
                    + String.join(", ", instances) + " (service names only, never URLs, hosts "
                    + "or ports)");
        }
        return new BenchLaunchPlan.Traffic(mode, target, "http://" + target + ":" + port, true,
                false, upstream);
    }

    /** One traffic selector value: a short, control-free string, never a URL. */
    private static String trafficSelector(String key, Object value) {
        if (!(value instanceof String text)) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' is a traffic selector and "
                    + "must be a string; got " + (value == null ? "null" : describe(value)));
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new BenchLaunchRejectedException("parameter '" + key + "' is a traffic selector "
                        + "and must not contain newlines or control characters");
            }
        }
        if (text.length() > TRAFFIC_SELECTOR_MAX_LENGTH) {
            throw new BenchLaunchRejectedException("parameter '" + key + "' is a traffic selector and "
                    + "must be at most " + TRAFFIC_SELECTOR_MAX_LENGTH + " characters; got "
                    + text.length());
        }
        return text;
    }

    private List<String> configuredTargetInstances() {
        String raw = properties.getTargetInstances();
        if (raw == null || raw.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.target-instances is blank: the fixed "
                    + "table a target-instance run may pin to is unknown; set env "
                    + "LAB_BENCH_EXEC_TARGET_INSTANCES (comma-separated service names)");
        }
        Set<String> names = new LinkedHashSet<>();
        for (String part : raw.split(",")) {
            String name = part.trim();
            if (name.isEmpty()) {
                continue;
            }
            if (!SERVICE_NAME.matcher(name).matches()) {
                throw new BenchUnavailableException("LAB_BENCH_EXEC_TARGET_INSTANCES entry '" + name
                        + "' is not a plain Docker service name (lowercase letters, digits and '-', "
                        + "starting with a letter); the fixed target table never contains a URL, "
                        + "host, port or path");
            }
            names.add(name);
        }
        if (names.isEmpty()) {
            throw new BenchUnavailableException("lab.bench.exec.target-instances has no usable "
                    + "service name; set env LAB_BENCH_EXEC_TARGET_INSTANCES");
        }
        return List.copyOf(names);
    }

    private int configuredTargetPort() {
        int port = properties.getTargetPort();
        if (port < MIN_PORT || port > MAX_PORT) {
            throw new BenchUnavailableException("lab.bench.exec.target-port (" + port + ") is not a "
                    + "valid TCP port; set env LAB_BENCH_EXEC_TARGET_PORT (default 8080)");
        }
        return port;
    }

    private String requireNginxBaseUrl() {
        String url = properties.getNginxBaseUrl() == null ? "" : properties.getNginxBaseUrl().trim();
        if (!HTTP_URL.matcher(url).matches()) {
            throw new BenchUnavailableException("lab.bench.exec.nginx-base-url must be an absolute "
                    + "http(s) URL; set env LAB_BENCH_EXEC_NGINX_BASE_URL (default http://nginx)");
        }
        return url;
    }

    /** Mirrors the Nginx upstream weights: every instance weight 1. */
    private static String upstream(List<String> instances, int port) {
        StringBuilder upstream = new StringBuilder();
        for (String instance : instances) {
            if (upstream.length() > 0) {
                upstream.append(',');
            }
            upstream.append(instance).append(':').append(port).append("=1");
        }
        return upstream.toString();
    }

    // ---------------------------------------------------------------- render

    private Map<String, String> renderEnv(Scenario definition, Map<String, Object> effective,
                                          String safeRunId, BenchLaunchPlan.Traffic traffic) {
        Map<String, String> env = new LinkedHashMap<>();
        for (String name : definition.env()) {
            if (RUN_ID.equals(name)) {
                env.put(name, safeRunId);
            } else if (BASE_URL.equals(name)) {
                env.put(name, traffic.baseUrl());
            } else if (effective.containsKey(name)) {
                env.put(name, stringify(name, effective.get(name)));
            }
        }
        // Identity and runtime values are always present, even if a future
        // scenario forgot to list them in env.
        env.putIfAbsent(RUN_ID, safeRunId);
        env.putIfAbsent(BASE_URL, traffic.baseUrl());
        // Attribution is owned by the runner: k6 tags every request with these
        // two values, so they are re-rendered here instead of being trusted
        // from the scenario env list.
        env.put(TRAFFIC_MODE, traffic.mode());
        env.put(TARGET_INSTANCE, traffic.targetInstance());
        env.put(PROMETHEUS_RW_SERVER_URL, properties.getPrometheusRwUrl());
        env.put(PROMETHEUS_RW_TREND_STATS, DEFAULT_TREND_STATS);
        env.put(PROMETHEUS_RW_PUSH_INTERVAL, DEFAULT_PUSH_INTERVAL);
        return env;
    }

    private String stringify(String key, Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        if (value instanceof Number || value instanceof Boolean) {
            return String.valueOf(value);
        }
        if (value instanceof List || value instanceof Map) {
            try {
                return objectMapper.writeValueAsString(value);
            } catch (JsonProcessingException e) {
                throw new BenchLaunchRejectedException("parameter '" + key
                        + "' cannot be rendered as an environment value: " + e.getMessage(), e);
            }
        }
        throw new BenchLaunchRejectedException("parameter '" + key
                + "' has an unsupported value type " + value.getClass().getSimpleName());
    }

    private static String join(String mount, String runId, String file) {
        return mount + "/" + runId + "/" + file;
    }
}
