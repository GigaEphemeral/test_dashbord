package com.javalearn.lab.chaos.guide;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.error.GuideConflictException;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Interactive guide state machine (one run per controller instance).
 *
 * <pre>
 * IDLE/COMPLETED/FAILED --start--&gt; RUNNING
 * RUNNING --step (last step)--&gt; COMPLETED
 * RUNNING --step failure--&gt; FAILED
 * RUNNING --pause--&gt; PAUSED --resume--&gt; RUNNING
 * any --reset--&gt; IDLE (stop-all + unfreeze)
 * </pre>
 *
 * <p>{@code step} executes exactly one scenario event through
 * {@link ChaosActionService}, so the existing whitelist, TTL, annotation and
 * metrics behavior is reused. A failed step leaves the run FAILED without an
 * automatic rollback; {@code reset} is the explicit stop-all.</p>
 *
 * <p>State is in memory and per chaos-controller instance. The lab runs a
 * single controller, so no replication is needed; a restart resets the guide to
 * IDLE while active TTLs remain tracked by the (also in-memory) action service.</p>
 */
@Service
public class GuideService {

    private static final Logger log = LoggerFactory.getLogger(GuideService.class);

    private final GuideScenarioLoader scenarioLoader;
    private final ChaosActionService chaosActionService;

    private Run run = Run.idle();

    public GuideService(GuideScenarioLoader scenarioLoader, ChaosActionService chaosActionService) {
        this.scenarioLoader = scenarioLoader;
        this.chaosActionService = chaosActionService;
    }

    public List<GuideScenarioSummary> scenarios() {
        return scenarioLoader.list();
    }

    public synchronized GuideStateView state() {
        return view();
    }

    /** Loads a scenario without executing anything; the caller then drives step. */
    public synchronized GuideStateView start(String scenarioId) {
        GuideStatus status = run.status;
        if (status != GuideStatus.IDLE && status != GuideStatus.COMPLETED && status != GuideStatus.FAILED) {
            throw new GuideConflictException("Guide is " + status.wireName()
                    + "; start is only allowed from idle/completed/failed (reset first)");
        }
        String id = scenarioId == null ? "" : scenarioId.trim();
        if (id.isEmpty()) {
            throw new InvalidScenarioException("scenarioId is required");
        }
        GuideScenario scenario = scenarioLoader.load(id);
        run = new Run(scenario, GuideStatus.RUNNING, 0, null);
        log.info("Guide started: scenario={} steps={}", scenario.id(), scenario.stepCount());
        return view();
    }

    /** Executes the next scenario event; exactly one action per call. */
    public synchronized GuideStateView step() {
        if (run.status != GuideStatus.RUNNING) {
            throw new GuideConflictException("Guide is " + run.status.wireName()
                    + "; step is only allowed while running");
        }
        GuideScenario scenario = run.scenario;
        if (scenario == null || run.currentStep >= scenario.stepCount()) {
            run.status = GuideStatus.COMPLETED;
            return view();
        }
        GuideEvent event = scenario.events().get(run.currentStep);
        try {
            chaosActionService.execute(event.target(),
                    new ChaosActionRequest(event.action(), event.ttlSeconds()));
        } catch (RuntimeException e) {
            run.status = GuideStatus.FAILED;
            run.lastError = e.getMessage();
            log.warn("Guide step {} ({} on {}) failed: {}", run.currentStep, event.action(),
                    event.target(), e.toString());
            throw e;
        }
        run.currentStep++;
        if (run.currentStep >= scenario.stepCount()) {
            run.status = GuideStatus.COMPLETED;
            log.info("Guide completed: scenario={} steps={}", scenario.id(), scenario.stepCount());
        }
        return view();
    }

    /**
     * Freezes every tracked TTL while the operator inspects the lab.
     *
     * <p>API semantics for the Task 23 console: pause is RUNNING-only, so a
     * one-event scenario (like the shared {@code quick-failover.json}) turns
     * COMPLETED right after its single step and the pause action must then be
     * disabled; {@code reset} stays available in every status and is the
     * documented escape hatch while actions are still tracked.</p>
     */
    public synchronized GuideStateView pause() {
        if (run.status != GuideStatus.RUNNING) {
            throw new GuideConflictException("Guide is " + run.status.wireName()
                    + "; pause is only allowed while running");
        }
        chaosActionService.pause();
        run.status = GuideStatus.PAUSED;
        log.info("Guide paused: scenario={} step={}/{}", run.scenario.id(),
                run.currentStep, run.scenario.stepCount());
        return view();
    }

    /** Resumes with the remaining TTL, never a fresh full TTL. */
    public synchronized GuideStateView resume() {
        if (run.status != GuideStatus.PAUSED) {
            throw new GuideConflictException("Guide is " + run.status.wireName()
                    + "; resume is only allowed while paused");
        }
        chaosActionService.resume();
        run.status = GuideStatus.RUNNING;
        log.info("Guide resumed: scenario={} step={}/{}", run.scenario.id(),
                run.currentStep, run.scenario.stepCount());
        return view();
    }

    /** Always allowed: stop-all, unfreeze and clear the guide run. */
    public synchronized GuideStateView reset() {
        StopAllResult result = chaosActionService.stopAll();
        if (chaosActionService.isPaused()) {
            chaosActionService.resume();
        }
        run = Run.idle();
        log.warn("Guide reset: stop-all reverted={} failed={} pending={}",
                result.reverted(), result.failed(), result.pending());
        return view();
    }

    private GuideStateView view() {
        GuideScenario scenario = run.scenario;
        int total = scenario == null ? 0 : scenario.stepCount();
        String currentTitle = null;
        String currentText = null;
        if (scenario != null && run.currentStep < total) {
            GuideEvent event = scenario.events().get(run.currentStep);
            currentTitle = event.title();
            currentText = event.text();
        }
        List<String> steps = scenario == null
                ? List.of()
                : scenario.events().stream()
                        .map(event -> event.text() != null ? event.text()
                                : event.title() != null ? event.title() : "")
                        .toList();
        return new GuideStateView(
                scenario == null ? null : scenario.id(),
                scenario == null ? null : scenario.name(),
                run.status.wireName(),
                run.currentStep,
                total,
                currentTitle,
                currentText,
                steps,
                chaosActionService.activeActions().size(),
                run.lastError);
    }

    private static final class Run {

        private final GuideScenario scenario;
        private GuideStatus status;
        private int currentStep;
        private String lastError;

        private Run(GuideScenario scenario, GuideStatus status, int currentStep, String lastError) {
            this.scenario = scenario;
            this.status = status;
            this.currentStep = currentStep;
            this.lastError = lastError;
        }

        private static Run idle() {
            return new Run(null, GuideStatus.IDLE, 0, null);
        }
    }
}
