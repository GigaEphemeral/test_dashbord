package com.javalearn.lab.chaos.guide;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/** Request body for {@code POST /lab/guide/start}. */
@JsonIgnoreProperties(ignoreUnknown = true)
public record GuideStartRequest(String scenarioId) {
}
