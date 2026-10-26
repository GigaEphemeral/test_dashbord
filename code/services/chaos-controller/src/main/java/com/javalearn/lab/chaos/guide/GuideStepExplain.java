package com.javalearn.lab.chaos.guide;

/**
 * Optional per-step teaching text (scenario schema v1, {@code events[].explain}).
 *
 * <p>The lab console prints these four lines under the current step, so the
 * operator sees what to look for before executing an action. Every field is
 * optional; the console only renders the ones a scenario actually fills in.</p>
 *
 * @param symptom    现象 — what the dashboards/logs show once the action lands
 * @param rootCause  根因 — the defect the scenario is demonstrating
 * @param fix        处方 — the countermeasure to apply or already applied
 * @param expectation 预期曲线 — how the metric curve should move afterwards
 */
public record GuideStepExplain(String symptom, String rootCause, String fix, String expectation) {
}
