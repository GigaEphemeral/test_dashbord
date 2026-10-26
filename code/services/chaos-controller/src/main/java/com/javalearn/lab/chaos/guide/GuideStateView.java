package com.javalearn.lab.chaos.guide;

import java.util.List;

/**
 * API view of the guide state.
 *
 * @param scenarioId       loaded scenario, {@code null} while IDLE
 * @param scenarioName     display name, {@code null} while IDLE
 * @param status           idle/running/paused/completed/failed
 * @param currentStep      index of the next event to execute (== totalSteps when completed)
 * @param totalSteps       number of scenario events
 * @param currentStepTitle title of the next event, {@code null} when none is pending
 * @param currentStepText  text of the next event, {@code null} when none is pending
 * @param steps            one text line per scenario event
 * @param activeActions    chaos actions currently tracked by the controller
 * @param lastError        failure message after a failed step
 */
public record GuideStateView(String scenarioId,
                             String scenarioName,
                             String status,
                             int currentStep,
                             int totalSteps,
                             String currentStepTitle,
                             String currentStepText,
                             List<String> steps,
                             int activeActions,
                             String lastError) {

    public GuideStateView {
        steps = steps == null ? List.of() : List.copyOf(steps);
    }
}
