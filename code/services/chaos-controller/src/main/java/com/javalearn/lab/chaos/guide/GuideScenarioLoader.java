package com.javalearn.lab.chaos.guide;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import com.javalearn.lab.chaos.error.ScenarioNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;
import java.util.regex.PatternSyntaxException;
import java.util.stream.Stream;

/**
 * Loads and validates scenario JSON (schema v1) at request time.
 *
 * <p>The rules mirror {@code code/lab/scenarios/README.md} and the k6
 * {@code chaos-schema.js} validator: version, id/name, ascending positive
 * offsets, allowed actions, TTL bounds from {@code chaos.max-ttl-seconds},
 * unique {@code (atSeconds,target)}, lab-app target pattern and a hard datastore
 * rejection. Invalid files are skipped for listing and rejected on start; a bad
 * file never takes the controller down.</p>
 */
@Component
public class GuideScenarioLoader {

    private static final Logger log = LoggerFactory.getLogger(GuideScenarioLoader.class);

    private static final int SCHEMA_VERSION = 1;
    private static final Pattern ID_PATTERN = Pattern.compile("^[a-z0-9-]+$");
    /** Datastores are forbidden regardless of the configured target pattern. */
    private static final Pattern DATASTORE_PATTERN =
            Pattern.compile("(?i)(mysql|redis|rabbit|mongo|postgres)");
    private static final Set<String> ALLOWED_ACTIONS =
            Set.of("kill", "start", "restart", "freeze", "resume");
    private static final Set<String> TTL_ACTIONS = Set.of("kill", "freeze");

    private final ObjectMapper objectMapper;
    private final ChaosProperties properties;
    private final Pattern targetPattern;

    public GuideScenarioLoader(ObjectMapper objectMapper, ChaosProperties properties) {
        this.objectMapper = objectMapper;
        this.properties = properties;
        String configured = properties.getGuide().getTargetPattern();
        try {
            this.targetPattern = Pattern.compile(configured);
        } catch (PatternSyntaxException | NullPointerException e) {
            throw new IllegalStateException(
                    "chaos.guide.target-pattern is not a valid regular expression: " + configured, e);
        }
    }

    /** Valid scenarios in the configured directory, sorted by id. */
    public List<GuideScenarioSummary> list() {
        List<GuideScenarioSummary> summaries = new ArrayList<>();
        for (Path file : scenarioFiles()) {
            try {
                GuideScenario scenario = parse(file);
                summaries.add(new GuideScenarioSummary(scenario.id(), scenario.name(),
                        scenario.description(), scenario.stepCount(), scenario.durationHintSeconds()));
            } catch (InvalidScenarioException e) {
                log.warn("Skipping invalid guide scenario {}: {}", file.getFileName(), e.getMessage());
            }
        }
        summaries.sort(Comparator.comparing(GuideScenarioSummary::id));
        return summaries;
    }

    /**
     * Loads one scenario by id. Ids are restricted to {@code [a-z0-9-]} so the
     * lookup can never escape the scenario directory.
     */
    public GuideScenario load(String scenarioId) {
        String id = scenarioId == null ? "" : scenarioId.trim();
        if (!ID_PATTERN.matcher(id).matches()) {
            throw new ScenarioNotFoundException("Unknown scenario: " + scenarioId);
        }
        Path dir = scenarioDir();
        if (!Files.isDirectory(dir)) {
            throw new ScenarioNotFoundException(
                    "Scenario directory is not available: " + dir);
        }
        Path direct = dir.resolve(id + ".json");
        if (Files.isRegularFile(direct)) {
            return parse(direct);
        }
        // The README calls the file name a convention, not a hard requirement;
        // fall back to matching the embedded id so renamed files still work.
        for (Path file : scenarioFiles()) {
            try {
                GuideScenario scenario = parse(file);
                if (scenario.id().equals(id)) {
                    return scenario;
                }
            } catch (InvalidScenarioException e) {
                log.debug("Ignoring invalid guide scenario {}: {}", file.getFileName(), e.getMessage());
            }
        }
        throw new ScenarioNotFoundException("Unknown scenario: " + id);
    }

    private Path scenarioDir() {
        return Path.of(properties.getGuide().getScenarioDir()).toAbsolutePath().normalize();
    }

    private List<Path> scenarioFiles() {
        Path dir = scenarioDir();
        if (!Files.isDirectory(dir)) {
            log.warn("Guide scenario directory is not available: {}", dir);
            return List.of();
        }
        try (Stream<Path> stream = Files.list(dir)) {
            return stream
                    .filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".json"))
                    .sorted()
                    .toList();
        } catch (IOException e) {
            log.warn("Could not list guide scenario directory {}: {}", dir, e.toString());
            return List.of();
        }
    }

    private GuideScenario parse(Path file) {
        String source = file.getFileName().toString();
        JsonNode root;
        try {
            root = objectMapper.readTree(Files.readString(file, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw invalid(source, "cannot read scenario JSON: " + e.getMessage());
        } catch (RuntimeException e) {
            throw invalid(source, "cannot parse scenario JSON: " + e.getMessage());
        }
        if (root == null || !root.isObject()) {
            throw invalid(source, "root must be a JSON object");
        }

        int schemaVersion = requireInt(source, "schemaVersion", root.get("schemaVersion"));
        if (schemaVersion != SCHEMA_VERSION) {
            throw invalid(source, "schemaVersion must be " + SCHEMA_VERSION + ", got " + schemaVersion);
        }

        String id = requireText(source, "id", root.get("id"));
        if (!ID_PATTERN.matcher(id).matches()) {
            throw invalid(source, "id must match " + ID_PATTERN.pattern() + ", got \"" + id + "\"");
        }
        String name = requireText(source, "name", root.get("name"));
        String description = optionalText(source, "description", root.get("description"));
        Double durationHintSeconds = optionalPositiveNumber(source, "durationHintSeconds",
                root.get("durationHintSeconds"));

        JsonNode eventsNode = root.get("events");
        if (eventsNode == null || !eventsNode.isArray() || eventsNode.isEmpty()) {
            throw invalid(source, "events must be a non-empty array");
        }

        int maxTtl = properties.getMaxTtlSeconds();
        List<GuideEvent> events = new ArrayList<>();
        Set<String> offsets = new HashSet<>();
        double previousOffset = 0;
        for (int index = 0; index < eventsNode.size(); index++) {
            JsonNode event = eventsNode.get(index);
            String label = "events[" + index + "]";
            if (event == null || !event.isObject()) {
                throw invalid(source, label + " must be an object");
            }

            double atSeconds = requirePositiveNumber(source, label + ".atSeconds", event.get("atSeconds"));
            if (atSeconds < previousOffset) {
                throw invalid(source, label + ".atSeconds=" + atSeconds
                        + " is out of order (previous " + previousOffset + ")");
            }
            previousOffset = atSeconds;

            String action = requireText(source, label + ".action", event.get("action"));
            if (!ALLOWED_ACTIONS.contains(action)) {
                throw invalid(source, label + ".action must be one of "
                        + String.join("/", ALLOWED_ACTIONS) + ", got \"" + action + "\"");
            }

            String target = requireText(source, label + ".target", event.get("target"));
            if (!targetPattern.matcher(target).matches()) {
                throw invalid(source, label + ".target \"" + target + "\" does not match "
                        + targetPattern.pattern());
            }
            if (DATASTORE_PATTERN.matcher(target).find()) {
                throw invalid(source, label + ".target \"" + target
                        + "\" is a datastore; datastore actions are forbidden");
            }

            String offsetKey = Double.toString(atSeconds) + ":" + target;
            if (!offsets.add(offsetKey)) {
                throw invalid(source, label + " duplicates atSeconds=" + atSeconds
                        + " for target \"" + target + "\"");
            }

            JsonNode ttlNode = event.get("ttlSeconds");
            Integer ttlSeconds = null;
            if (TTL_ACTIONS.contains(action)) {
                if (ttlNode == null || ttlNode.isNull()) {
                    throw invalid(source, label + ".ttlSeconds is required for " + action);
                }
                ttlSeconds = requireInt(source, label + ".ttlSeconds", ttlNode);
                if (ttlSeconds <= 0 || ttlSeconds > maxTtl) {
                    throw invalid(source, label + ".ttlSeconds=" + ttlSeconds
                            + " must be between 1 and " + maxTtl + "s");
                }
            } else if (ttlNode != null && !ttlNode.isNull()) {
                throw invalid(source, label + ".ttlSeconds is only valid for kill/freeze");
            }

            events.add(new GuideEvent(atSeconds, action, target, ttlSeconds,
                    optionalText(source, label + ".title", event.get("title")),
                    optionalText(source, label + ".text", event.get("text"))));
        }

        return new GuideScenario(schemaVersion, id, name, description, durationHintSeconds, events);
    }

    private static String requireText(String source, String field, JsonNode node) {
        if (node == null || !node.isTextual() || node.asText().trim().isEmpty()) {
            throw invalid(source, field + " must be a non-empty string");
        }
        return node.asText().trim();
    }

    private static String optionalText(String source, String field, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isTextual() || node.asText().trim().isEmpty()) {
            throw invalid(source, field + " must be a non-empty string when present");
        }
        return node.asText().trim();
    }

    private static int requireInt(String source, String field, JsonNode node) {
        if (node == null || !node.isIntegralNumber() || !node.canConvertToInt()) {
            throw invalid(source, field + " must be an integer");
        }
        return node.asInt();
    }

    private static double requirePositiveNumber(String source, String field, JsonNode node) {
        if (node == null || !node.isNumber()) {
            throw invalid(source, field + " must be a positive number");
        }
        double value = node.asDouble();
        if (!Double.isFinite(value) || value <= 0) {
            throw invalid(source, field + " must be a positive number, got " + node);
        }
        return value;
    }

    private static Double optionalPositiveNumber(String source, String field, JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        return requirePositiveNumber(source, field, node);
    }

    private static InvalidScenarioException invalid(String source, String message) {
        return new InvalidScenarioException(source + ": " + message);
    }
}
