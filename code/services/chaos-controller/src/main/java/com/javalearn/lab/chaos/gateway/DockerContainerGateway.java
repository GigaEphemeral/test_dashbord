package com.javalearn.lab.chaos.gateway;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidTailException;
import com.javalearn.lab.chaos.model.ContainerView;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker Java implementation of {@link ContainerGateway}.
 *
 * <p>Only containers carrying the {@code lab=java-learn} label are ever exposed.
 * Logs are read through the Docker API (never the CLI) and bounded by the
 * caller's tail value.</p>
 */
public class DockerContainerGateway implements ContainerGateway {

    public static final String LAB_LABEL = "lab";
    public static final String LAB_VALUE = "java-learn";
    public static final int MIN_TAIL = 1;
    public static final int MAX_TAIL = 1000;

    private static final Logger log = LoggerFactory.getLogger(DockerContainerGateway.class);
    private static final long LOG_WAIT_SECONDS = 15L;

    private final DockerClient dockerClient;

    public DockerContainerGateway(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public List<ContainerView> listLabContainers() {
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .exec()
                    .stream()
                    .filter(this::isLabContainer)
                    .map(this::toView)
                    .sorted(Comparator.comparing(ContainerView::name))
                    .toList();
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while listing containers", e);
        } catch (RuntimeException e) {
            // The httpclient5 transport wraps connect failures in a plain RuntimeException.
            throw new DockerUnavailableException("Docker daemon unavailable while listing containers", e);
        }
    }

    @Override
    public String tailLogs(String containerName, int tail) {
        if (tail < MIN_TAIL || tail > MAX_TAIL) {
            throw new InvalidTailException("tail must be between " + MIN_TAIL + " and " + MAX_TAIL);
        }

        requireLabContainer(containerName);

        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null) {
                    return;
                }
                try {
                    buffer.write(frame.getPayload());
                } catch (IOException e) {
                    throw new UncheckedIOException(e);
                }
            }
        }) {
            dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(tail)
                    .exec(callback);
            if (!callback.awaitCompletion(LOG_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Timed out after {}s while reading logs for {} (returning partial output)",
                        LOG_WAIT_SECONDS, containerName);
            }
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerUnavailableException("Interrupted while reading logs for " + containerName, e);
        } catch (IOException e) {
            throw new DockerUnavailableException("Failed to close log stream for " + containerName, e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to read logs for " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to read logs for " + containerName, e);
        }
        return buffer.toString(StandardCharsets.UTF_8);
    }

    private void requireLabContainer(String containerName) {
        Map<String, String> labels;
        try {
            var inspected = dockerClient.inspectContainerCmd(containerName).exec();
            labels = inspected.getConfig() == null ? null : inspected.getConfig().getLabels();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting " + containerName, e);
        }
        if (labels == null || !LAB_VALUE.equals(labels.get(LAB_LABEL))) {
            throw new ContainerNotAllowedException("Container is not part of the lab: " + containerName);
        }
    }

    private boolean isLabContainer(Container container) {
        Map<String, String> labels = container.getLabels();
        return labels != null && LAB_VALUE.equals(labels.get(LAB_LABEL));
    }

    private ContainerView toView(Container container) {
        String name = primaryName(container);
        String state = container.getState() == null ? "unknown" : container.getState();
        String health = "none";
        try {
            var inspectedState = dockerClient.inspectContainerCmd(container.getId()).exec().getState();
            if (inspectedState != null) {
                if (inspectedState.getStatus() != null) {
                    state = inspectedState.getStatus();
                }
                HealthState inspectedHealth = inspectedState.getHealth();
                if (inspectedHealth != null && inspectedHealth.getStatus() != null) {
                    health = inspectedHealth.getStatus();
                }
            }
        } catch (DockerException e) {
            // A container can disappear between list and inspect; keep the list entry.
            log.debug("Could not inspect container {}: {}", name, e.getMessage());
        }
        return new ContainerView(name, state, health);
    }

    private String primaryName(Container container) {
        String[] names = container.getNames();
        if (names == null || names.length == 0) {
            String id = container.getId();
            return id == null ? "unknown" : id.substring(0, Math.min(12, id.length()));
        }
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }
}
