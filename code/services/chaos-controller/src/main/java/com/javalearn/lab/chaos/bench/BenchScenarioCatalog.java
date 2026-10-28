package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Read-only projection of the k6 scenario manifest
 * ({@code scenarios/manifest.json}, the same file {@code run.sh}/{@code bench.sh}
 * consume through {@code lib/scenario-manifest.mjs}).
 *
 * <p>The manifest is re-read per request on purpose: the controller starts even
 * when the compose mount is missing (the endpoint then answers 503/50303), and
 * mounting a corrected file takes effect without a restart. Validation follows
 * the node reader's invariants so the backend can never advertise a manifest the
 * shell runners would reject:</p>
 * <ul>
 *   <li>structure: positive integer {@code version}, {@code views}/{@code scenarios}
 *       objects, boolean/string/enum field types;</li>
 *   <li>view containment: every referenced name exists, {@code bench ⊆ run} and
 *       {@code ui ⊆ bench};</li>
 *   <li>{@code serviceOnly} scenarios never appear in a view;</li>
 *   <li>every {@code params} key is declared in the scenario's {@code env}, and
 *       every {@code env} name is forwarded by {@code forwarding}/{@code runtimeEnv};</li>
 *   <li>every forwarded name is consumed by a scenario or {@code runtimeEnv}.</li>
 * </ul>
 *
 * <p>{@code scriptExists} is a best-effort stat of the sibling script relative to
 * the manifest's k6 root. A {@code classpath:} manifest (or a mount without the
 * scripts) reports {@code false} instead of failing the whole catalog; the UI
 * treats it as a hint, not as a reason to hide the scenario.</p>
 */
@Component
public class BenchScenarioCatalog {

    static final List<String> VIEW_NAMES = List.of("run", "bench", "ui");
    private static final List<String> PROFILE_VALUES = List.of("dev", "ha", "db-ha", "traffic");
    private static final Pattern ENV_NAME = Pattern.compile("[A-Za-z_][A-Za-z0-9_]*");
    private static final Pattern SCRIPT_PATH = Pattern.compile("scenarios/[A-Za-z0-9._-]+\\.js");

    private final BenchProperties properties;
    private final ResourceLoader resourceLoader;
    private final ObjectMapper objectMapper;

    public BenchScenarioCatalog(BenchProperties properties, ResourceLoader resourceLoader,
                                ObjectMapper objectMapper) {
        this.properties = properties;
        this.resourceLoader = resourceLoader;
        this.objectMapper = objectMapper;
    }

    /** Loads, validates and projects the manifest; any problem is a 503/50303. */
    public Snapshot load() {
        String location = properties.getScenariosLocation();
        if (location == null || location.isBlank()) {
            throw new BenchUnavailableException("lab.bench.scenarios-location is blank; "
                    + "env LAB_BENCH_SCENARIOS_LOCATION (default file:/app/k6/scenarios/manifest.json)");
        }
        Resource resource = resourceLoader.getResource(location);
        if (!resource.exists()) {
            throw new BenchUnavailableException("scenario manifest not found: " + location
                    + "; env LAB_BENCH_SCENARIOS_LOCATION (compose mounts code/lab/k6 at /app/k6)");
        }
        JsonNode root;
        try (InputStream input = resource.getInputStream()) {
            root = objectMapper.readTree(input);
        } catch (IOException e) {
            throw new BenchUnavailableException("cannot read scenario manifest " + location
                    + ": " + e.getMessage(), e);
        }
        if (root == null || !root.isObject()) {
            throw new BenchUnavailableException("scenario manifest " + location
                    + " must be a JSON object");
        }
        Path k6Root = k6Root(resource);
        return validate(root, location, k6Root);
    }

    private Snapshot validate(JsonNode root, String source, Path k6Root) {
        int version = positiveInt(root.get("version"), "version", source);
        JsonNode scenariosNode = requireObject(root.get("scenarios"), "scenarios", source);
        if (scenariosNode.isEmpty()) {
            fail(source, "\"scenarios\" must be a non-empty object");
        }

        Map<String, List<String>> views = new LinkedHashMap<>();
        JsonNode viewsNode = requireObject(root.get("views"), "views", source);
        for (String view : VIEW_NAMES) {
            views.put(view, nameList(viewsNode.get(view), "views." + view, source));
        }
        Set<String> scenarioNames = new LinkedHashSet<>();
        scenariosNode.fieldNames().forEachRemaining(scenarioNames::add);
        for (String view : VIEW_NAMES) {
            for (String name : views.get(view)) {
                if (!scenarioNames.contains(name)) {
                    fail(source, "views." + view + " references unknown scenario \"" + name + "\"");
                }
            }
        }
        for (String name : views.get("bench")) {
            if (!views.get("run").contains(name)) {
                fail(source, "views.bench must be a subset of views.run (\"" + name
                        + "\" is not in run)");
            }
        }
        for (String name : views.get("ui")) {
            if (!views.get("bench").contains(name)) {
                fail(source, "views.ui must be a subset of views.bench (\"" + name
                        + "\" is not in bench)");
            }
        }

        JsonNode forwardingNode = requireObject(root.get("forwarding"), "forwarding", source);
        Set<String> forwarded = new LinkedHashSet<>();
        Set<String> serviceForwarded = new LinkedHashSet<>();
        for (String group : List.of("common", "chaos", "cache", "service")) {
            List<String> names = envNames(forwardingNode.get(group), "forwarding." + group, source);
            if ("service".equals(group)) {
                serviceForwarded.addAll(names);
            }
            forwarded.addAll(names);
        }
        List<String> runtimeEnv = envNames(root.get("runtimeEnv"), "runtimeEnv", source);
        forwarded.addAll(runtimeEnv);

        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        for (String name : scenarioNames) {
            JsonNode node = scenariosNode.get(name);
            scenarios.put(name, scenario(name, requireObject(node, "scenarios." + name, source),
                    views, forwarded, serviceForwarded, source, k6Root));
        }

        Set<String> consumed = new LinkedHashSet<>(runtimeEnv);
        for (Scenario scenario : scenarios.values()) {
            consumed.addAll(scenario.env());
        }
        for (String key : forwarded) {
            if (!consumed.contains(key)) {
                fail(source, "\"" + key + "\" is forwarded but no scenario or runtimeEnv declares it");
            }
        }

        Map<String, List<String>> viewCopy = new LinkedHashMap<>();
        for (String view : VIEW_NAMES) {
            viewCopy.put(view, List.copyOf(views.get(view)));
        }
        return new Snapshot("OK", version, viewCopy, scenarios);
    }

    private Scenario scenario(String name, JsonNode node, Map<String, List<String>> views,
                              Set<String> forwarded, Set<String> serviceForwarded,
                              String source, Path k6Root) {
        String label = "scenarios." + name;
        String script = requireText(node.get("script"), label + ".script", source);
        if (!SCRIPT_PATH.matcher(script).matches()) {
            fail(source, label + ".script must look like \"scenarios/<name>.js\"");
        }
        boolean serviceOnly = requireBoolean(node.get("serviceOnly"), label + ".serviceOnly", source);
        JsonNode requiresNode = requireObject(node.get("requires"), label + ".requires", source);
        boolean chaos = requireBoolean(requiresNode.get("chaos"), label + ".requires.chaos", source);
        boolean cache = requireBoolean(requiresNode.get("cache"), label + ".requires.cache", source);
        JsonNode profileNode = requiresNode.get("profile");
        if (profileNode == null) {
            fail(source, label + ".requires.profile is missing (null or one of "
                    + String.join(", ", PROFILE_VALUES) + ")");
        }
        String profile = null;
        if (!profileNode.isNull()) {
            if (!profileNode.isTextual() || !PROFILE_VALUES.contains(profileNode.asText())) {
                fail(source, label + ".requires.profile must be null or one of "
                        + String.join(", ", PROFILE_VALUES) + "; got " + profileNode);
            }
            profile = profileNode.asText();
        }
        boolean writesData = requireBoolean(node.get("writesData"), label + ".writesData", source);
        boolean thresholdsExpected = requireBoolean(node.get("thresholdsExpected"),
                label + ".thresholdsExpected", source);
        List<String> env = envNames(node.get("env"), label + ".env", source);
        JsonNode paramsNode = requireObject(node.get("params"), label + ".params", source);
        List<String> paramKeys = new ArrayList<>();
        paramsNode.fieldNames().forEachRemaining(paramKeys::add);
        for (String key : paramKeys) {
            if (!env.contains(key)) {
                fail(source, label + ".params." + key + " is not declared in " + label + ".env");
            }
        }
        for (String key : env) {
            if (!forwarded.contains(key)) {
                fail(source, label + ".env lists \"" + key
                        + "\" but neither forwarding nor runtimeEnv forwards it");
            }
            if (!serviceOnly && serviceForwarded.contains(key)) {
                fail(source, label + ".env lists compose-service variable \"" + key
                        + "\"; only a service-only scenario may consume forwarding.service");
            }
        }
        for (String view : VIEW_NAMES) {
            if (serviceOnly && views.get(view).contains(name)) {
                fail(source, "service-only scenario \"" + name + "\" must not be in views." + view);
            }
        }
        if (!serviceOnly && !views.get("run").contains(name)) {
            fail(source, "scenario \"" + name + "\" is neither service-only nor in views.run");
        }
        Map<String, Object> params = objectMapper.convertValue(paramsNode,
                new TypeReference<Map<String, Object>>() {
                });
        return new Scenario(script, serviceOnly, new Requires(chaos, cache, profile),
                writesData, thresholdsExpected, List.copyOf(env), params,
                scriptExists(k6Root, script));
    }

    // ------------------------------------------------------------- validation

    private static int positiveInt(JsonNode node, String label, String source) {
        if (node == null || !node.isIntegralNumber() || node.asInt() < 1) {
            fail(source, "\"" + label + "\" must be a positive integer");
        }
        return node.asInt();
    }

    private static JsonNode requireObject(JsonNode node, String label, String source) {
        if (node == null || !node.isObject()) {
            fail(source, "\"" + label + "\" must be an object");
        }
        return node;
    }

    private static String requireText(JsonNode node, String label, String source) {
        if (node == null || !node.isTextual() || node.asText().isBlank()) {
            fail(source, "\"" + label + "\" must be a non-empty string");
        }
        return node.asText();
    }

    private static boolean requireBoolean(JsonNode node, String label, String source) {
        if (node == null || !node.isBoolean()) {
            fail(source, "\"" + label + "\" must be a boolean");
        }
        return node.asBoolean();
    }

    private static List<String> nameList(JsonNode node, String label, String source) {
        if (node == null || !node.isArray()) {
            fail(source, "\"" + label + "\" must be an array of scenario names");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || item.asText().isBlank()) {
                fail(source, "\"" + label + "\" contains a non-string or empty entry");
            }
            if (names.contains(item.asText())) {
                fail(source, "\"" + label + "\" lists \"" + item.asText() + "\" twice");
            }
            names.add(item.asText());
        }
        return names;
    }

    private static List<String> envNames(JsonNode node, String label, String source) {
        if (node == null || !node.isArray()) {
            fail(source, "\"" + label + "\" must be an array of environment variable names");
        }
        List<String> names = new ArrayList<>();
        for (JsonNode item : node) {
            if (!item.isTextual() || !ENV_NAME.matcher(item.asText()).matches()) {
                fail(source, "\"" + label + "\" contains an invalid environment variable name: "
                        + item);
            }
            if (names.contains(item.asText())) {
                fail(source, "\"" + label + "\" lists \"" + item.asText() + "\" twice");
            }
            names.add(item.asText());
        }
        return names;
    }

    private static void fail(String source, String message) {
        throw new BenchUnavailableException("scenario manifest " + source + ": " + message);
    }

    // --------------------------------------------------------- script probing

    /**
     * The manifest lives at {@code <k6root>/scenarios/manifest.json}; the
     * {@code script} field is relative to that root. Non-file resources cannot be
     * stat'ed, which only turns {@code scriptExists} into {@code false}.
     */
    private static Path k6Root(Resource resource) {
        try {
            Path manifest = resource.getFile().toPath().toAbsolutePath().normalize();
            Path dir = manifest.getParent();
            if (dir == null) {
                return null;
            }
            Path name = dir.getFileName();
            if (name != null && "scenarios".equals(name.toString()) && dir.getParent() != null) {
                return dir.getParent();
            }
            return dir;
        } catch (IOException | RuntimeException e) {
            return null;
        }
    }

    private static boolean scriptExists(Path k6Root, String script) {
        if (k6Root == null) {
            return false;
        }
        Path candidate = k6Root.resolve(script).normalize();
        return candidate.startsWith(k6Root) && Files.isRegularFile(candidate);
    }

    // ---------------------------------------------------------------- views

    /** {@code data} payload of {@code GET /lab/bench/scenarios}. */
    public record Snapshot(String status, int version, Map<String, List<String>> views,
                           Map<String, Scenario> scenarios) {
    }

    /** Manifest scenario fields projected verbatim plus the best-effort stat. */
    public record Scenario(String script, boolean serviceOnly, Requires requires,
                           boolean writesData, boolean thresholdsExpected, List<String> env,
                           Map<String, Object> params, boolean scriptExists) {
    }

    public record Requires(boolean chaos, boolean cache, String profile) {
    }
}
