package com.javalearn.lab.chaos.precheck;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.Container;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.gateway.DockerContainerGateway;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * Docker Java implementation of the read-only facts the run pre-check needs.
 *
 * <p>Kept separate from {@code DockerContainerGateway} on purpose: the gateway
 * is the mutation boundary (label check on every call), while this class only
 * inspects. Every Docker error is translated into the existing
 * {@link DockerUnavailableException}, so a check can degrade to WARN/BLOCK with
 * one exception type instead of catching transport errors.</p>
 */
public class DockerClientFacts implements PrecheckDockerFacts {

    private static final Logger log = LoggerFactory.getLogger(DockerClientFacts.class);

    private final DockerClient dockerClient;

    public DockerClientFacts(DockerClient dockerClient) {
        this.dockerClient = dockerClient;
    }

    @Override
    public List<LabContainerFacts> runningLabContainers() {
        try {
            List<LabContainerFacts> facts = new ArrayList<>();
            for (Container container : dockerClient.listContainersCmd().withShowAll(false).exec()) {
                if (!isLabContainer(container)) {
                    continue;
                }
                String name = primaryName(container);
                facts.add(new LabContainerFacts(name, container.getImageId(),
                        memoryLimit(container.getId()), container.getState()));
            }
            facts.sort(Comparator.comparing(LabContainerFacts::name));
            return List.copyOf(facts);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while listing containers", e);
        } catch (RuntimeException e) {
            // The httpclient5 transport wraps connect failures in a plain RuntimeException.
            throw new DockerUnavailableException("Docker daemon unavailable while listing containers", e);
        }
    }

    @Override
    public LabContainerFacts inspect(String containerName) {
        try {
            InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerName).exec();
            String state = inspected.getState() == null || inspected.getState().getStatus() == null
                    ? "unknown" : inspected.getState().getStatus();
            return new LabContainerFacts(canonicalName(inspected.getName(), containerName),
                    inspected.getImageId(), memoryLimitOf(inspected), state);
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting "
                    + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting "
                    + containerName, e);
        }
    }

    @Override
    public long memoryTotalBytes() {
        try {
            Long total = dockerClient.infoCmd().exec().getMemTotal();
            return total == null ? 0L : total;
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while reading docker info", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while reading docker info", e);
        }
    }

    private long memoryLimit(String containerId) {
        try {
            return memoryLimitOf(dockerClient.inspectContainerCmd(containerId).exec());
        } catch (NotFoundException e) {
            // The container disappeared between list and inspect: its limit is
            // unknown, so report 0 (unbounded) instead of failing the whole list.
            log.debug("Container {} disappeared before its memory limit could be read", containerId);
            return 0L;
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting "
                    + containerId, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting "
                    + containerId, e);
        }
    }

    private static long memoryLimitOf(InspectContainerResponse inspected) {
        if (inspected.getHostConfig() == null || inspected.getHostConfig().getMemory() == null) {
            return 0L;
        }
        return Math.max(0L, inspected.getHostConfig().getMemory());
    }

    private static boolean isLabContainer(Container container) {
        Map<String, String> labels = container.getLabels();
        return labels != null && DockerContainerGateway.LAB_VALUE.equals(
                labels.get(DockerContainerGateway.LAB_LABEL));
    }

    private static String primaryName(Container container) {
        String[] names = container.getNames();
        if (names == null || names.length == 0) {
            String id = container.getId();
            return id == null ? "unknown" : id.substring(0, Math.min(12, id.length()));
        }
        String name = names[0];
        return name.startsWith("/") ? name.substring(1) : name;
    }

    private static String canonicalName(String inspectedName, String requestedName) {
        if (inspectedName == null || inspectedName.isEmpty()) {
            return requestedName;
        }
        return inspectedName.startsWith("/") ? inspectedName.substring(1) : inspectedName;
    }
}
