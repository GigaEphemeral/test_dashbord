package com.javalearn.lab.chaos.guide;

/** List entry for {@code GET /lab/guide/scenarios}. */
public record GuideScenarioSummary(String id,
                                   String name,
                                   String description,
                                   int stepCount,
                                   Double durationHintSeconds) {
}
