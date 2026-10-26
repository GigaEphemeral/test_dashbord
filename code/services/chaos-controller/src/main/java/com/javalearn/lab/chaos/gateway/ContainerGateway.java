package com.javalearn.lab.chaos.gateway;

import com.javalearn.lab.chaos.model.ContainerView;

import java.util.List;

/**
 * Lab container operations. Every mutating call must enforce the
 * {@code lab=java-learn} label before touching the Docker daemon.
 */
public interface ContainerGateway {

    String SIGSTOP = "SIGSTOP";
    String SIGCONT = "SIGCONT";

    List<ContainerView> listLabContainers();

    String tailLogs(String containerName, int tail);

    /**
     * Inspects a whitelisted container.
     *
     * @throws com.javalearn.lab.chaos.error.ContainerNotFoundException when unknown
     * @throws com.javalearn.lab.chaos.error.ContainerNotAllowedException when not a lab container
     */
    ContainerView inspectLabContainer(String containerName);

    /** Docker stop (manual stop so {@code unless-stopped} does not restart it). */
    void stop(String containerName, int graceSeconds);

    void start(String containerName);

    void restart(String containerName, int graceSeconds);

    /** Sends a signal to the container's init process; only SIGSTOP/SIGCONT are allowed. */
    void signal(String containerName, String signal);
}
