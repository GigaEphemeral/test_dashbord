package com.javalearn.lab.chaos.model;

/**
 * Read-only view of a whitelisted lab container.
 *
 * @param name   container name without Docker's leading slash
 * @param state  Docker state, e.g. running/exited
 * @param health Docker health status, or {@code none} when no healthcheck exists
 */
public record ContainerView(String name, String state, String health) {
}
