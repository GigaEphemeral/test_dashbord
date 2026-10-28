package com.javalearn.lab.chaos.bench.exec;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Requires;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Scenario;
import com.javalearn.lab.chaos.bench.BenchScenarioCatalog.Snapshot;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunLifecycleHook;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2-02b-2a hook tests: fake repository/executor, real resolver + planner. No
 * Docker, no Spring, no database.
 */
class BenchRunLifecycleHookTest {

    private static final String RUN_ID = "run-20261028-001";
    private static final String HOST_K6 = "/host/code/lab/k6";
    private static final Instant NOW = Instant.parse("2026-10-28T10:00:00Z");

    private final BenchExecProperties properties = new BenchExecProperties();
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final BenchScenarioCatalog catalog = mock(BenchScenarioCatalog.class);
    private final BenchRunExecutor executor = mock(BenchRunExecutor.class);
    private final RunLedgerRepository repository = mock(RunLedgerRepository.class);

    private BenchLaunchPlanner planner;
    private BenchScriptsHostDirResolver resolver;
    private BenchRunLifecycleHook hook;

    @BeforeEach
    void setUp() {
        properties.setReportsMount("/reports");
        properties.setScriptsMount("/scripts");
        planner = new BenchLaunchPlanner(catalog, properties, objectMapper);
        resolver = new BenchScriptsHostDirResolver(properties,
                container -> List.of(new BenchScriptsHostDirResolver.Mount(HOST_K6,
                        properties.getControllerK6Mount())));
        hook = new BenchRunLifecycleHook(repository, catalog, planner, resolver, executor,
                objectMapper);
        when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run("load")));
        when(catalog.load()).thenReturn(snapshot(loadScenario(), Map.of("ui", List.of("load"))));
    }

    @Test
    void launchResolvesTheHostDirParsesParamsAndReturnsContainerDetails() {
        when(repository.findParams(RUN_ID)).thenReturn(List.of(
                new RunLedgerRepository.RunParamRow("request", "{\"LOAD_DURATION\":\"2m\"}", NOW)));
        when(executor.start(any())).thenReturn(state("RUNNING", 0, null));

        RunLifecycleHook.LaunchResult result = hook.launch(RUN_ID);

        ArgumentCaptor<BenchLaunchPlan> plan = ArgumentCaptor.forClass(BenchLaunchPlan.class);
        verify(executor).start(plan.capture());
        assertThat(plan.getValue().binds().get(0).source()).isEqualTo(HOST_K6);
        assertThat(plan.getValue().binds().get(0).destination()).isEqualTo("/scripts");
        assertThat(plan.getValue().binds().get(0).readOnly()).isTrue();
        // The persisted request params overlay the manifest default (10m).
        assertThat(plan.getValue().env()).containsEntry("LOAD_DURATION", "2m");
        assertThat(result.details())
                .containsEntry("k6Container", "jl-k6-" + RUN_ID)
                .containsEntry("k6ContainerId", "cid-" + RUN_ID)
                .containsEntry("k6Scenario", "load")
                .containsEntry("k6ReportPath", "/reports/" + RUN_ID + "/summary.json")
                .containsEntry("k6Status", "RUNNING");
    }

    @Test
    void aMissingRequestGroupFallsBackToManifestDefaults() {
        when(repository.findParams(RUN_ID)).thenReturn(List.of());
        when(executor.start(any())).thenReturn(state("RUNNING", 0, null));

        hook.launch(RUN_ID);

        ArgumentCaptor<BenchLaunchPlan> plan = ArgumentCaptor.forClass(BenchLaunchPlan.class);
        verify(executor).start(plan.capture());
        assertThat(plan.getValue().env()).containsEntry("LOAD_DURATION", "10m");
    }

    @Test
    void aPlannerRejectionPropagatesUnchangedAndStartsNothing() {
        when(repository.findRun(RUN_ID)).thenReturn(Optional.of(run("stress")));
        when(catalog.load()).thenReturn(snapshot(stressScenario(),
                Map.of("ui", List.of("smoke"), "bench", List.of("stress"))));

        assertThatThrownBy(() -> hook.launch(RUN_ID))
                .isInstanceOf(BenchLaunchRejectedException.class)
                .hasMessageContaining("stress")
                .hasMessageContaining("CLI-only");

        verify(executor, never()).start(any());
    }

    @Test
    void anUnresolvableHostDirIsUnavailableWithRunScenarioAndHostContext() {
        BenchScriptsHostDirResolver failing = new BenchScriptsHostDirResolver(properties,
                container -> List.of());
        BenchRunLifecycleHook broken = new BenchRunLifecycleHook(repository, catalog, planner,
                failing, executor, objectMapper);

        assertThatThrownBy(() -> broken.launch(RUN_ID))
                .isInstanceOf(BenchUnavailableException.class)
                .hasMessageContaining(RUN_ID)
                .hasMessageContaining("load")
                .hasMessageContaining("hostDir");
    }

    @Test
    void stopIsIdempotentAndReportsTheAbsentContainer() {
        when(executor.stop(RUN_ID)).thenReturn(false);
        when(executor.state(RUN_ID)).thenReturn(state("ABSENT", null, "no k6 container"));

        RunLifecycleHook.LaunchResult result = hook.stop(RUN_ID);

        assertThat(result.details())
                .containsEntry("k6Stopped", false)
                .containsEntry("k6Status", "ABSENT")
                .containsEntry("k6ReportAvailable", false);
        assertThat(result.details()).doesNotContainKey("k6ExitCode");
    }

    @Test
    void stopKeepsTheStoppedVerdictWhenTheStateReadFails() {
        when(executor.stop(RUN_ID)).thenReturn(true);
        when(executor.state(RUN_ID)).thenThrow(new BenchUnavailableException("daemon gone"));

        RunLifecycleHook.LaunchResult result = hook.stop(RUN_ID);

        assertThat(result.details()).containsEntry("k6Stopped", true);
        assertThat(result.details().get("k6StateError").toString()).contains("daemon gone");
    }

    // -------------------------------------------------------------- fixtures

    private BenchExecutionState state(String status, Integer exitCode, String note) {
        boolean available = !"ABSENT".equals(status);
        return new BenchExecutionState(status, "jl-k6-" + RUN_ID, "cid-" + RUN_ID, "load",
                exitCode, "2026-10-28T10:00:00Z", null, available, available ? 120 : 0, note);
    }

    private RunRecord run(String scenario) {
        return new RunRecord(RUN_ID, scenario, RunStatus.READY, "sha", "local", NOW, NOW, null, null);
    }

    private static Scenario loadScenario() {
        return scenario("scenarios/load.js", Map.of("LOAD_DURATION", "10m"));
    }

    private static Scenario stressScenario() {
        return scenario("scenarios/stress.js", Map.of("STRESS_STAGES", List.of()));
    }

    private static Scenario scenario(String script, Map<String, Object> params) {
        return new Scenario(script, false, new Requires(false, false, "dev"), true, true,
                List.of("RUN_ID", "BASE_URL", "LOAD_DURATION", "STRESS_STAGES"), params, true);
    }

    private static Snapshot snapshot(Scenario scenario, Map<String, List<String>> views) {
        Map<String, Scenario> scenarios = new LinkedHashMap<>();
        scenarios.put(scenario.script().startsWith("scenarios/stress") ? "stress" : "load", scenario);
        return new Snapshot("OK", 1, views, scenarios);
    }
}
