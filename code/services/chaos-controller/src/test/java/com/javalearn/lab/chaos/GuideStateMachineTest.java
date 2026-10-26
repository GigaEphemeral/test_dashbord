package com.javalearn.lab.chaos;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.GuideConflictException;
import com.javalearn.lab.chaos.error.ScenarioNotFoundException;
import com.javalearn.lab.chaos.guide.GuideScenarioLoader;
import com.javalearn.lab.chaos.guide.GuideService;
import com.javalearn.lab.chaos.guide.GuideStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class GuideStateMachineTest {

    private static final String SCENARIO = """
            {
              "schemaVersion": 1,
              "id": "quick-failover",
              "name": "Quick failover",
              "durationHintSeconds": 30,
              "events": [
                {"atSeconds": 5, "action": "kill", "target": "jl-app-3", "ttlSeconds": 15,
                 "title": "Stop app-3", "text": "step one"},
                {"atSeconds": 20, "action": "start", "target": "jl-app-3",
                 "title": "Start app-3", "text": "step two"}
              ]
            }
            """;

    @TempDir
    Path tempDir;

    private ChaosActionService chaos;
    private GuideService guide;

    @BeforeEach
    void setUp() throws IOException {
        Files.writeString(tempDir.resolve("quick-failover.json"), SCENARIO);
        ChaosProperties properties = new ChaosProperties();
        properties.getGuide().setScenarioDir(tempDir.toString());
        GuideScenarioLoader loader = new GuideScenarioLoader(new ObjectMapper(), properties);
        chaos = mock(ChaosActionService.class);
        when(chaos.activeActions()).thenReturn(List.of());
        when(chaos.stopAll()).thenReturn(new StopAllResult(0, 0));
        guide = new GuideService(loader, chaos);
    }

    @Test
    void startLoadsWithoutExecutingAndStepsToCompletion() {
        GuideStateView started = guide.start("quick-failover");

        assertThat(started.status()).isEqualTo("running");
        assertThat(started.scenarioId()).isEqualTo("quick-failover");
        assertThat(started.currentStep()).isZero();
        assertThat(started.totalSteps()).isEqualTo(2);
        assertThat(started.currentStepTitle()).isEqualTo("Stop app-3");
        assertThat(started.steps()).containsExactly("step one", "step two");
        verify(chaos, never()).execute(any(), any());

        GuideStateView first = guide.step();
        assertThat(first.status()).isEqualTo("running");
        assertThat(first.currentStep()).isEqualTo(1);
        assertThat(first.currentStepTitle()).isEqualTo("Start app-3");
        verify(chaos).execute(eq("jl-app-3"), eq(new ChaosActionRequest("kill", 15)));

        GuideStateView second = guide.step();
        assertThat(second.status()).isEqualTo("completed");
        assertThat(second.currentStep()).isEqualTo(2);
        assertThat(second.currentStepTitle()).isNull();
        verify(chaos).execute(eq("jl-app-3"), eq(new ChaosActionRequest("start", null)));
    }

    @Test
    void stepFailureMarksRunFailedWithoutRollback() {
        guide.start("quick-failover");
        when(chaos.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenThrow(new ChaosConflictException("Target jl-app-3 already has active chaos action id-1"));

        assertThatThrownBy(() -> guide.step())
                .isInstanceOf(ChaosConflictException.class)
                .hasMessageContaining("id-1");

        GuideStateView state = guide.state();
        assertThat(state.status()).isEqualTo("failed");
        assertThat(state.currentStep()).isZero();
        assertThat(state.lastError()).contains("id-1");
        verify(chaos, never()).stopAll();
    }

    @Test
    void failedRunCanBeRestarted() {
        guide.start("quick-failover");
        when(chaos.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenThrow(new IllegalStateException("docker down"));
        assertThatThrownBy(() -> guide.step()).isInstanceOf(IllegalStateException.class);

        GuideStateView restarted = guide.start("quick-failover");
        assertThat(restarted.status()).isEqualTo("running");
        assertThat(restarted.currentStep()).isZero();
        assertThat(restarted.lastError()).isNull();
    }

    @Test
    void pauseAndResumeDriveTheActionService() {
        guide.start("quick-failover");

        GuideStateView paused = guide.pause();
        assertThat(paused.status()).isEqualTo("paused");
        verify(chaos).pause();

        GuideStateView resumed = guide.resume();
        assertThat(resumed.status()).isEqualTo("running");
        verify(chaos).resume();
    }

    @Test
    void invalidTransitionsAreRejectedWithConflict() {
        assertThatThrownBy(() -> guide.step()).isInstanceOf(GuideConflictException.class);
        assertThatThrownBy(() -> guide.pause()).isInstanceOf(GuideConflictException.class);
        assertThatThrownBy(() -> guide.resume()).isInstanceOf(GuideConflictException.class);

        guide.start("quick-failover");
        assertThatThrownBy(() -> guide.start("quick-failover"))
                .isInstanceOf(GuideConflictException.class)
                .hasMessageContaining("running");
        assertThatThrownBy(() -> guide.resume()).isInstanceOf(GuideConflictException.class);

        guide.pause();
        assertThatThrownBy(() -> guide.step()).isInstanceOf(GuideConflictException.class);
        assertThatThrownBy(() -> guide.pause()).isInstanceOf(GuideConflictException.class);

        guide.resume();
        guide.step();
        guide.step();
        assertThat(guide.state().status()).isEqualTo("completed");
        assertThatThrownBy(() -> guide.step()).isInstanceOf(GuideConflictException.class);
    }

    @Test
    void resetStopsAllAndClearsStateInAnyStatus() {
        guide.start("quick-failover");
        guide.step();

        GuideStateView reset = guide.reset();

        assertThat(reset.status()).isEqualTo("idle");
        assertThat(reset.scenarioId()).isNull();
        assertThat(reset.currentStep()).isZero();
        assertThat(reset.totalSteps()).isZero();
        assertThat(reset.steps()).isEmpty();
        verify(chaos).stopAll();
    }

    @Test
    void resetUnfreezesAPausedGuide() {
        guide.start("quick-failover");
        guide.pause();
        when(chaos.isPaused()).thenReturn(true);

        guide.reset();

        verify(chaos).stopAll();
        verify(chaos).resume();
        assertThat(guide.state().status()).isEqualTo("idle");
    }

    @Test
    void stateReportsActiveActionCountFromTheActionService() {
        when(chaos.activeActions()).thenReturn(List.of(new ChaosActionView("id-1", "jl-app-3",
                "kill", 15, Instant.parse("2026-10-26T10:00:15Z"), "start", "active", null)));

        assertThat(guide.state().activeActions()).isEqualTo(1);
    }

    @Test
    void unknownScenarioIsNotFoundAndStateStaysIdle() {
        assertThatThrownBy(() -> guide.start("missing"))
                .isInstanceOf(ScenarioNotFoundException.class);
        assertThat(guide.state().status()).isEqualTo("idle");
    }

    @Test
    void pauseAndResumeAreRejectedAfterCompletion() throws IOException {
        // API contract for the Task 23 console: a one-event scenario (like the
        // shared quick-failover.json) is COMPLETED right after its single step,
        // and pause/resume stay RUNNING/PAUSED-only. The console must disable
        // the pause button once the status is completed/failed.
        Files.writeString(tempDir.resolve("single-step.json"), """
                {
                  "schemaVersion": 1,
                  "id": "single-step",
                  "name": "Single step",
                  "events": [
                    {"atSeconds": 5, "action": "kill", "target": "jl-app-3", "ttlSeconds": 15,
                     "title": "Stop app-3", "text": "only step"}
                  ]
                }
                """);

        guide.start("single-step");
        GuideStateView completed = guide.step();

        assertThat(completed.status()).isEqualTo("completed");
        assertThat(completed.currentStep()).isEqualTo(1);
        assertThatThrownBy(guide::pause)
                .isInstanceOf(GuideConflictException.class)
                .hasMessageContaining("completed");
        assertThatThrownBy(guide::resume).isInstanceOf(GuideConflictException.class);
        verify(chaos, never()).pause();

        // reset remains the escape hatch in COMPLETED with tracked actions.
        when(chaos.activeActions()).thenReturn(List.of(new ChaosActionView("id-1", "jl-app-3",
                "kill", 15, Instant.parse("2026-10-26T10:00:20Z"), "start", "active", null)));
        GuideStateView reset = guide.reset();
        assertThat(reset.status()).isEqualTo("idle");
        verify(chaos).stopAll();
    }
}
