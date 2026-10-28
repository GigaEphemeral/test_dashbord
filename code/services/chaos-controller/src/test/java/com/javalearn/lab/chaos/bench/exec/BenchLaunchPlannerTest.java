package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Requires;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Scenario;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure planner tests (Stage2 B2-02a): no Spring context, no Docker, no
 * filesystem. The snapshot is built in memory with the same shape the catalog
 * projects from {@code scenarios/manifest.json} (run/bench/ui views, the
 * service-only traffic entry and the ui-only smoke/load/spike scenarios).
 */
class BenchLaunchPlannerTest {

    private static final String RUN_ID = "run-20261028-001";
    private static final String REPORT = "/reports/" + RUN_ID + "/summary.json";
    private static final Snapshot SNAPSHOT = snapshot();

    private final BenchExecProperties properties = new BenchExecProperties();
    // The catalog is only used by the plan(runId, ...) overload; the tests use
    // the snapshot overload on purpose so nothing is loaded from disk.
    private final BenchLaunchPlanner planner = new BenchLaunchPlanner(null, properties, new ObjectMapper());

    @BeforeEach
    void setUp() {
        // B2-02b resolves the real host path; the tests pin a fake one.
        properties.setScriptsHostDir("/host/k6");
    }

    // ------------------------------------------------------------- happy path

    @ParameterizedTest
    @ValueSource(strings = {"smoke", "load", "spike"})
    void defaultPlanForEveryUiScenario(String scenario) {
        BenchLaunchPlan plan = planner.plan(SNAPSHOT, RUN_ID, scenario, Map.of());

        assertEquals("grafana/k6:1.3.0", plan.image());
        assertEquals("jl-net", plan.network());
        assertEquals("jl-k6-" + RUN_ID, plan.containerName());
        assertEquals(scenario, plan.scenario());
        assertEquals(RUN_ID, plan.runId());
        assertEquals(REPORT, plan.reportContainerPath());
        assertEquals(REPORT, plan.reportHostPath());
        assertTrue(plan.argv().contains(REPORT));
        assertTrue(plan.argv().contains("/scripts/scenarios/" + scenario + ".js"));
        assertFalse(plan.argv().contains("--out"));
        assertFalse(plan.argv().contains("--summary-export=" + REPORT));
    }

    @Test
    void smokePlanRendersFixedArgvEnvBindsAndLabels() {
        BenchLaunchPlan plan = planner.plan(SNAPSHOT, RUN_ID, "smoke", Map.of());

        assertEquals(List.of("run", "--quiet", "-o", "experimental-prometheus-rw",
                "--summary-export", REPORT, "/scripts/scenarios/smoke.js"), plan.argv());
        assertEquals("jl-k6-" + RUN_ID, plan.containerName());

        assertEquals(RUN_ID, plan.env().get("RUN_ID"));
        assertEquals("http://nginx", plan.env().get("BASE_URL"));
        assertEquals("http://prometheus:9090/api/v1/write",
                plan.env().get("K6_PROMETHEUS_RW_SERVER_URL"));
        assertEquals("p(95),p(99),max", plan.env().get("K6_PROMETHEUS_RW_TREND_STATS"));
        assertEquals("5s", plan.env().get("K6_PROMETHEUS_RW_PUSH_INTERVAL"));
        assertEquals("1", plan.env().get("SMOKE_VUS"));
        assertEquals("30s", plan.env().get("SMOKE_DURATION"));
        assertEquals("0.01", plan.env().get("ERROR_RATE"));
        // Only scenario env names plus the forced values: nothing else leaks in.
        assertFalse(plan.env().containsKey("LOAD_VUS"));
        assertFalse(plan.env().containsKey("PRODUCT_ID"));

        assertEquals(2, plan.binds().size());
        BenchLaunchPlan.Bind scripts = plan.binds().get(0);
        assertEquals("/host/k6", scripts.source());
        assertEquals("/scripts", scripts.destination());
        assertTrue(scripts.readOnly());
        BenchLaunchPlan.Bind reports = plan.binds().get(1);
        assertEquals("jl-k6-reports", reports.source());
        assertEquals("/reports", reports.destination());
        assertFalse(reports.readOnly());

        assertEquals(Map.of("lab", "java-learn", "lab.role", "k6",
                "lab.run-id", RUN_ID, "lab.scenario", "smoke"), plan.labels());
    }

    @Test
    void customValidParamsOverrideManifestDefaults() {
        BenchLaunchPlan plan = planner.plan(SNAPSHOT, RUN_ID, "load",
                Map.of("LOAD_VUS", 300, "LOAD_DURATION", "5m", "ERROR_RATE", 0.02, "P95_MS", 0));

        assertEquals("300", plan.env().get("LOAD_VUS"));
        assertEquals("5m", plan.env().get("LOAD_DURATION"));
        assertEquals("0.02", plan.env().get("ERROR_RATE"));
        assertEquals("0", plan.env().get("P95_MS"));
        // Params never change argv: same summary path, no --out.
        assertEquals("/scripts/scenarios/load.js", plan.argv().get(plan.argv().size() - 1));
        assertTrue(plan.argv().contains(REPORT));
    }

    @Test
    void nullParamsMeanManifestDefaults() {
        BenchLaunchPlan plan = planner.plan(SNAPSHOT, RUN_ID, "spike", null);
        assertEquals("800", plan.env().get("SPIKE_VUS"));
    }

    // ------------------------------------------------------ scenario whitelist

    @ParameterizedTest
    @ValueSource(strings = {"stress", "soak"})
    void benchOnlyScenariosAreCliOnly(String scenario) {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, scenario, Map.of()));
        assertTrue(error.getMessage().contains("CLI-only"), error.getMessage());
        assertTrue(error.getMessage().contains("views.ui"), error.getMessage());
    }

    @Test
    void trafficIsServiceOnly() {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "traffic", Map.of()));
        assertTrue(error.getMessage().contains("service-only"), error.getMessage());
    }

    @Test
    void runOnlyScenarioIsCliOnly() {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "d7", Map.of()));
        assertTrue(error.getMessage().contains("views.run"), error.getMessage());
    }

    @Test
    void unknownScenarioIsRejectedWithWhitelist() {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "nope", Map.of()));
        assertTrue(error.getMessage().contains("unknown scenario"), error.getMessage());
        assertTrue(error.getMessage().contains("smoke, load, spike"), error.getMessage());
    }

    @Test
    void blankScenarioIsRejected() {
        assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "  ", Map.of()));
    }

    // ------------------------------------------------------------ parameters

    @Test
    void unknownParameterIsRejected() {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "smoke", Map.of("PRODUCT_ID", 1)));
        assertTrue(error.getMessage().contains("unknown parameter"), error.getMessage());
        assertTrue(error.getMessage().contains("SMOKE_VUS"), error.getMessage());
    }

    @Test
    void typeMismatchIsRejected() {
        assertRejected("smoke", Map.of("SMOKE_VUS", "10"), "expects a number");
        assertRejected("smoke", Map.of("ERROR_RATE", "fast"), "expects a number");
        assertRejected("smoke", Map.of("SMOKE_DURATION", 30), "expects a string");
        assertRejected("spike", Map.of("SPIKE_VUS", Map.of("value", 1)), "expects a number");
    }

    @Test
    void nullParameterValueIsRejected() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("SMOKE_VUS", null);
        assertRejected("smoke", params, "must not be null");
    }

    @Test
    void negativeAndNonFiniteNumbersAreRejected() {
        assertRejected("smoke", Map.of("P95_MS", -1), ">= 0");
        assertRejected("smoke", Map.of("ERROR_RATE", Double.NaN), "finite");
    }

    @Test
    void vusAboveConfiguredCapIsRejected() {
        assertRejected("load", Map.of("LOAD_VUS", 1001), "max-vus");
        // Exactly at the cap is allowed.
        assertEquals("1000",
                planner.plan(SNAPSHOT, RUN_ID, "load", Map.of("LOAD_VUS", 1000)).env().get("LOAD_VUS"));
    }

    @Test
    void durationAboveConfiguredCapIsRejected() {
        assertRejected("load", Map.of("LOAD_DURATION", "2h"), "max-duration-seconds");
        assertRejected("load", Map.of("LOAD_DURATION", "1h1s"), "max-duration-seconds");
        // Exactly one hour (the configured cap) is allowed, compounds too.
        assertEquals("60m",
                planner.plan(SNAPSHOT, RUN_ID, "load", Map.of("LOAD_DURATION", "60m")).env().get("LOAD_DURATION"));
        assertEquals("1m30s",
                planner.plan(SNAPSHOT, RUN_ID, "spike", Map.of("SPIKE_DURATION", "1m30s"))
                        .env().get("SPIKE_DURATION"));
    }

    @Test
    void malformedDurationIsRejected() {
        assertRejected("load", Map.of("LOAD_DURATION", "soon"), "k6 duration");
        assertRejected("load", Map.of("LOAD_DURATION", "1m30"), "k6 duration");
        assertRejected("load", Map.of("LOAD_DURATION", ""), "k6 duration");
    }

    @Test
    void controlCharactersInStringsAreRejected() {
        assertRejected("smoke", Map.of("SMOKE_DURATION", "30s\n"), "control characters");
        assertRejected("smoke", Map.of("SMOKE_DURATION", "30s\t"), "control characters");
    }

    @Test
    void forcedValuesCannotBeOverridden() {
        for (String forced : List.of("RUN_ID", "BASE_URL", "K6_PROMETHEUS_RW_SERVER_URL",
                "K6_PROMETHEUS_RW_TREND_STATS", "K6_PROMETHEUS_RW_PUSH_INTERVAL")) {
            BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                    () -> planner.plan(SNAPSHOT, RUN_ID, "smoke", Map.of(forced, "x")));
            assertTrue(error.getMessage().contains("forced"), error.getMessage());
        }
    }

    @Test
    void runIdMustBeUsableAndIsSanitised() {
        BenchLaunchRejectedException blank = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, "  ", "smoke", Map.of()));
        assertTrue(blank.getMessage().contains("runId"), blank.getMessage());
        BenchLaunchRejectedException unsafe = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, "../etc", "smoke", Map.of()));
        assertTrue(unsafe.getMessage().contains("does not start"), unsafe.getMessage());

        BenchLaunchPlan plan = planner.plan(SNAPSHOT, "run 20261028/001", "smoke", Map.of());
        assertEquals("jl-k6-run-20261028-001", plan.containerName());
        assertEquals("/reports/run-20261028-001/summary.json", plan.reportContainerPath());
    }

    // ---------------------------------------------------------- configuration

    @Test
    void blankScriptsHostDirIsUnavailableNotRejected() {
        properties.setScriptsHostDir("  ");
        BenchUnavailableException error = assertThrows(BenchUnavailableException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, "smoke", Map.of()));
        assertTrue(error.getMessage().contains("scripts-host-dir"), error.getMessage());
        assertTrue(error.getMessage().contains("LAB_BENCH_EXEC_SCRIPTS_HOST_DIR"), error.getMessage());
    }

    @Test
    void planUsesConfigurationValuesOnly() {
        properties.setK6Image("grafana/k6:9.9.9");
        properties.setNetwork("custom-net");
        properties.setContainerPrefix("x-k6-");
        properties.setScriptsHostDir("/mnt/k6");
        properties.setScriptsMount("/s");
        properties.setReportsMount("/r");
        properties.setReportsVolume("reports-vol");
        properties.setSummaryFile("out.json");
        properties.setBaseUrl("http://gateway");
        properties.setPrometheusRwUrl("http://prom:9090/api/v1/write");
        properties.setMaxVus(10);
        properties.setMaxDurationSeconds(60);

        BenchLaunchPlan plan = planner.plan(SNAPSHOT, RUN_ID, "smoke",
                Map.of("SMOKE_VUS", 10, "SMOKE_DURATION", "60s"));

        assertEquals("grafana/k6:9.9.9", plan.image());
        assertEquals("custom-net", plan.network());
        assertEquals("x-k6-" + RUN_ID, plan.containerName());
        assertEquals("/r/" + RUN_ID + "/out.json", plan.reportContainerPath());
        assertEquals("/s/scenarios/smoke.js", plan.argv().get(plan.argv().size() - 1));
        assertEquals("/mnt/k6", plan.binds().get(0).source());
        assertEquals("/s", plan.binds().get(0).destination());
        assertEquals("reports-vol", plan.binds().get(1).source());
        assertEquals("/r", plan.binds().get(1).destination());
        assertEquals("http://gateway", plan.env().get("BASE_URL"));
        assertEquals("http://prom:9090/api/v1/write", plan.env().get("K6_PROMETHEUS_RW_SERVER_URL"));

        assertRejected("spike", Map.of("SPIKE_VUS", 11), "max-vus");
        assertRejected("spike", Map.of("SPIKE_DURATION", "61s"), "max-duration-seconds");
    }

    // -------------------------------------------------------------- duration

    @Test
    void durationParserAcceptsK6Grammar() {
        assertEquals(30.0, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "30s"));
        assertEquals(600.0, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "10m"));
        assertEquals(3600.0, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "1h"));
        assertEquals(90.0, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "1m30s"));
        assertEquals(30.0, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "30"));
        assertEquals(1.5, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "1.5s"));
        assertEquals(0.5, BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "500ms"));
        assertThrows(BenchLaunchRejectedException.class,
                () -> BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "1m30"));
        assertThrows(BenchLaunchRejectedException.class,
                () -> BenchLaunchPlanner.parseDurationSeconds("LOAD_DURATION", "-1s"));
    }

    // --------------------------------------------------------------- helpers

    private void assertRejected(String scenario, Map<String, Object> params, String fragment) {
        BenchLaunchRejectedException error = assertThrows(BenchLaunchRejectedException.class,
                () -> planner.plan(SNAPSHOT, RUN_ID, scenario, params));
        assertTrue(error.getMessage().contains(fragment),
                "expected '" + fragment + "' in: " + error.getMessage());
    }

    /**
     * In-memory projection of the real manifest: ui = smoke/load/spike,
     * bench adds stress/soak, run adds d7, traffic is service-only.
     */
    private static Snapshot snapshot() {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put("smoke", scenario("smoke", params(
                "SMOKE_VUS", 1, "SMOKE_ITERATIONS", 1, "SMOKE_DURATION", "30s",
                "P95_MS", 300, "P99_MS", 800, "ERROR_RATE", 0.01, "CHECKS_RATE", 0.99)));
        scenarios.put("load", scenario("load", params(
                "LOAD_DURATION", "10m", "LOAD_START_VUS", 50, "LOAD_VUS", 200,
                "P95_MS", 300, "P99_MS", 800, "ERROR_RATE", 0.01)));
        scenarios.put("stress", scenario("stress", params(
                "STRESS_STAGES", List.of(Map.of("duration", "1m", "target", 50)),
                "P95_MS", 300)));
        scenarios.put("spike", scenario("spike", params(
                "SPIKE_BASELINE_VUS", 50, "SPIKE_BASELINE_DURATION", "30s",
                "SPIKE_RAMP_UP", "10s", "SPIKE_VUS", 800, "SPIKE_DURATION", "60s",
                "SPIKE_RAMP_DOWN", "10s", "P95_MS", 300)));
        scenarios.put("soak", scenario("soak", params("SOAK_VUS", 100, "SOAK_DURATION", "30m")));
        scenarios.put("d7", scenario("d7", params("LOAD_VUS", 200)));
        scenarios.put("traffic", new Scenario("scenarios/traffic.js", true,
                new Requires(false, false, "traffic"), false, true,
                List.of("BASE_URL", "RUN_ID", "TRAFFIC_VUS", "TRAFFIC_DURATION"),
                params("TRAFFIC_VUS", 50, "TRAFFIC_DURATION", "30m"), true));

        Map<String, List<String>> views = new LinkedHashMap<>();
        views.put("run", List.of("smoke", "load", "stress", "spike", "soak", "d7"));
        views.put("bench", List.of("smoke", "load", "stress", "spike", "soak"));
        views.put("ui", List.of("smoke", "load", "spike"));
        return new Snapshot("OK", 1, views, scenarios);
    }

    private static Scenario scenario(String name, Map<String, Object> params) {
        List<String> env = new ArrayList<>(List.of("BASE_URL", "RUN_ID"));
        env.addAll(params.keySet());
        return new Scenario("scenarios/" + name + ".js", false,
                new Requires(false, false, "dev"), false, true, List.copyOf(env), params, true);
    }

    private static Map<String, Object> params(Object... keyValues) {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) {
            params.put((String) keyValues[i], keyValues[i + 1]);
        }
        return params;
    }
}
