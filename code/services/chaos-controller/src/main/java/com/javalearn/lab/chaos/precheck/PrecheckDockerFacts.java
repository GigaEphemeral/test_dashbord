package com.javalearn.lab.chaos.precheck;

import java.util.List;

/**
 * Read-only Docker facts the gate needs but the mutation-oriented
 * {@code ContainerGateway} does not expose (image id, memory limit, daemon
 * memory). Implemented by {@link DockerClientFacts}; tests provide in-memory
 * implementations.
 */
public interface PrecheckDockerFacts {

    /**
     * Running containers carrying the {@code lab=java-learn} label, with the
     * image id and the configured {@code HostConfig.Memory} (0 = unlimited).
     *
     * @throws com.javalearn.lab.chaos.error.DockerUnavailableException when the
     *         daemon cannot be queried
     */
    List<LabContainerFacts> runningLabContainers();

    /**
     * One container by name (running or not).
     *
     * @throws com.javalearn.lab.chaos.error.ContainerNotFoundException when unknown
     * @throws com.javalearn.lab.chaos.error.DockerUnavailableException when the
     *         daemon cannot be queried
     */
    LabContainerFacts inspect(String containerName);

    /**
     * Host memory reported by {@code docker info} in bytes.
     *
     * @throws com.javalearn.lab.chaos.error.DockerUnavailableException when the
     *         daemon cannot be queried
     */
    long memoryTotalBytes();

    /**
     * One running (or inspected) lab container.
     *
     * @param name             container name without Docker's leading slash
     * @param imageId          image id the container runs, e.g. {@code sha256:...}
     * @param memoryLimitBytes {@code HostConfig.Memory}, 0 when unlimited
     * @param state            Docker state, e.g. running/exited
     */
    record LabContainerFacts(String name, String imageId, long memoryLimitBytes, String state) {
    }
}
