package com.javalearn.lab.chaos.action;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * Request body for {@code POST /chaos/instances/{name}/actions}.
 *
 * @param action     kill|start|restart|freeze|resume
 * @param ttlSeconds optional TTL for kill/freeze; ignored for start/restart/resume
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record ChaosActionRequest(String action, Integer ttlSeconds) {
}
