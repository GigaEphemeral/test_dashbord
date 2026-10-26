package com.javalearn.lab.chaos.guide;

import java.util.List;

/**
 * Validated scenario (schema v1), aligned with {@code code/lab/scenarios/README.md}.
 * Events are already sorted ascending by {@code atSeconds}.
 */
public record GuideScenario(int schemaVersion,
                            String id,
                            String name,
                            String description,
                            Double durationHintSeconds,
                            List<GuideEvent> events) {

    public GuideScenario {
        events = events == null ? List.of() : List.copyOf(events);
    }

    public int stepCount() {
        return events.size();
    }
}
