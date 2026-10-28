package com.javalearn.lab.chaos.bench;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Shared fixture for the bench catalog tests: a five-scenario manifest whose
 * shape mirrors the real {@code code/lab/k6/scenarios/manifest.json} (run=5,
 * bench=5, ui=smoke/load/spike) plus the service-only {@code traffic}.
 */
final class BenchManifestFixture {

    static final List<String> RUN = List.of("smoke", "load", "stress", "spike", "soak");
    static final List<String> BENCH = List.of("smoke", "load", "stress", "spike", "soak");
    static final List<String> UI = List.of("smoke", "load", "spike");

    private BenchManifestFixture() {
    }

    static ObjectMapper mapper() {
        return new ObjectMapper();
    }

    static ObjectNode manifest() {
        ObjectNode root = mapper().createObjectNode();
        root.put("version", 1);
        ObjectNode views = root.putObject("views");
        array(views, "run", RUN);
        array(views, "bench", BENCH);
        array(views, "ui", UI);
        ObjectNode forwarding = root.putObject("forwarding");
        forwarding.putArray("common")
                .add("SMOKE_VUS").add("SMOKE_DURATION")
                .add("LOAD_VUS").add("LOAD_DURATION")
                .add("STRESS_STAGES")
                .add("SPIKE_VUS").add("SPIKE_DURATION")
                .add("SOAK_VUS").add("SOAK_DURATION")
                .add("P95_MS");
        forwarding.putArray("chaos");
        forwarding.putArray("cache");
        forwarding.putArray("service").add("TRAFFIC_VUS").add("TRAFFIC_DURATION");
        root.putArray("runtimeEnv");

        ObjectNode scenarios = root.putObject("scenarios");
        scenario(scenarios, "smoke", Map.of("SMOKE_VUS", 5),
                "SMOKE_VUS", "SMOKE_DURATION", "P95_MS");
        scenario(scenarios, "load", Map.of("LOAD_VUS", 200),
                "LOAD_VUS", "LOAD_DURATION", "P95_MS");
        scenario(scenarios, "stress",
                Map.of("STRESS_STAGES", List.of(Map.of("duration", "1m", "target", 50))),
                "STRESS_STAGES", "P95_MS");
        scenario(scenarios, "spike", Map.of("SPIKE_VUS", 800),
                "SPIKE_VUS", "SPIKE_DURATION", "P95_MS");
        scenario(scenarios, "soak", Map.of("SOAK_VUS", 100),
                "SOAK_VUS", "SOAK_DURATION", "P95_MS");

        ObjectNode traffic = scenarios.putObject("traffic");
        traffic.put("script", "scenarios/traffic.js");
        traffic.put("serviceOnly", true);
        ObjectNode trafficRequires = traffic.putObject("requires");
        trafficRequires.put("chaos", false);
        trafficRequires.put("cache", false);
        trafficRequires.putNull("profile");
        traffic.put("writesData", false);
        traffic.put("thresholdsExpected", true);
        array(traffic, "env", List.of("TRAFFIC_VUS", "TRAFFIC_DURATION"));
        traffic.set("params", mapper().valueToTree(Map.of("TRAFFIC_VUS", 50)));
        return root;
    }

    static ObjectNode scenario(ObjectNode scenarios, String name, Map<String, Object> params,
                               String... env) {
        ObjectNode node = scenarios.putObject(name);
        node.put("script", "scenarios/" + name + ".js");
        node.put("serviceOnly", false);
        ObjectNode requires = node.putObject("requires");
        requires.put("chaos", false);
        requires.put("cache", false);
        requires.putNull("profile");
        node.put("writesData", true);
        node.put("thresholdsExpected", true);
        array(node, "env", List.of(env));
        node.set("params", mapper().valueToTree(params));
        return node;
    }

    static void array(ObjectNode parent, String field, List<String> values) {
        ArrayNode array = parent.putArray(field);
        values.forEach(array::add);
    }

    /** Writes {@code <dir>/scenarios/manifest.json} and returns the file path. */
    static Path write(Path dir, ObjectNode manifest) throws IOException {
        Path scenarios = Files.createDirectories(dir.resolve("scenarios"));
        Path file = scenarios.resolve("manifest.json");
        mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), manifest);
        return file;
    }

    /** Creates {@code <dir>/scenarios/<name>.js} so scriptExists can be true. */
    static void script(Path dir, String name) throws IOException {
        Path scenarios = Files.createDirectories(dir.resolve("scenarios"));
        Files.writeString(scenarios.resolve(name + ".js"), "// fixture\n");
    }

    /** The location form the controller uses ({@code file:...}). */
    static String location(Path manifest) {
        return manifest.toUri().toString();
    }
}
