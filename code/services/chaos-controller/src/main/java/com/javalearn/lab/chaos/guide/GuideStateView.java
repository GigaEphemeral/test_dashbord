package com.javalearn.lab.chaos.guide;

import java.util.List;

/**
 * API view of the guide state.
 *
 * @param scenarioId    loaded scenario, {@code null} while IDLE
 * @param scenarioName  display name, {@code null} while IDLE
 * @param status        idle/running/paused/completed/failed
 * @param currentStep   index of the next event to execute (== totalSteps when completed)
 * @param totalSteps    number of scenario events
 * @param steps         scenario timeline; {@code steps[currentStep]} is the pending step
 * @param activeActions chaos actions currently tracked by the controller
 * @param lastError     failure message after a failed step
 * @param resetMode     {@code manual} or {@code auto}: whether a terminal guide
 *                      state reverts every tracked fault by itself
 */
public record GuideStateView(String scenarioId,
                             String scenarioName,
                             String status,
                             int currentStep,
                             int totalSteps,
                             List<GuideStepView> steps,
                             int activeActions,
                             String lastError,
                             String resetMode) {

    public GuideStateView {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
