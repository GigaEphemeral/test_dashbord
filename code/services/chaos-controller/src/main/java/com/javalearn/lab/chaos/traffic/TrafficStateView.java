package com.javalearn.lab.chaos.traffic;

/**
 * API view of the background traffic container.
 *
 * @param container compose container name, e.g. {@code jl-traffic}
 * @param state     Docker state, or {@code missing} when the traffic profile
 *                  container was never created
 * @param health    Docker health status, {@code none} without a healthcheck
 * @param running   whether load is currently being generated
 */
public record TrafficStateView(String container, String state, String health, boolean running) {

    public static final String MISSING = "missing";

    public static TrafficStateView missing(String container) {
        return new TrafficStateView(container, MISSING, "none", false);
    }
}
