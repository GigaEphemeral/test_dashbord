package com.javalearn.lab.chaos.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for {@code POST /chaos/instances/{name}/actions}.
 *
 * @param action     kill|start|restart|freeze|resume
 * @param ttlSeconds optional TTL for kill/freeze; ignored for start/restart/resume
 * @param runId      B2-04a: optional run that owns this fault. When provided, the
 *                   run must exist and the action is written to that run's fault
 *                   timeline ({@code lab_fault_event}); the run does not have to
 *                   be RUNNING (the pre-check blocks a later start). Omitted
 *                   means the fault is not attached to any run.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChaosActionRequest(String action, Integer ttlSeconds, String runId) {

    /** Compatibility constructor for callers that predate B2-04a (e.g. the guide). */
    public ChaosActionRequest(String action, Integer ttlSeconds) {
        this(action, ttlSeconds, null);
    }
}
