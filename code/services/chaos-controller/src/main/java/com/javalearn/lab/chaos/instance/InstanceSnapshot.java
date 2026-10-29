package com.javalearn.lab.chaos.instance;

import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;

import java.util.List;
import java.util.Map;

/**
 * Stage2 B4-02 immutable-enough copy of everything needed to recreate one app
 * container without a compose file (design §10.7 "固定模板或受控 Docker API").
 *
 * <p>Only values read from the running container are stored here: the image
 * reference/id, the environment (replaced on create), labels, command,
 * entrypoint, working dir, user, hostname, exposed ports, per-network aliases,
 * healthcheck and the whole {@link HostConfig}. Nothing is accepted from the
 * HTTP request except the instance name; the new image ref is always
 * {@code <lab.instance.image-repository>:<stored imageTag>}.</p>
 */
public record InstanceSnapshot(
        String instance,
        String containerName,
        String containerId,
        String imageRef,
        String imageId,
        Map<String, String> env,
        Map<String, String> labels,
        List<String> command,
        List<String> entrypoint,
        String workingDir,
        String user,
        String hostname,
        List<String> exposedPorts,
        Map<String, List<String>> networkAliases,
        HealthCheck healthcheck,
        HostConfig hostConfig) {

    public InstanceSnapshot {
        env = env == null ? Map.of() : Map.copyOf(env);
        labels = labels == null ? Map.of() : Map.copyOf(labels);
        command = command == null ? List.of() : List.copyOf(command);
        entrypoint = entrypoint == null ? List.of() : List.copyOf(entrypoint);
        exposedPorts = exposedPorts == null ? List.of() : List.copyOf(exposedPorts);
        networkAliases = networkAliases == null ? Map.of() : Map.copyOf(networkAliases);
    }

    /** Network the container mainly lives on ({@code jl-net} for the app tier). */
    public String networkMode() {
        if (hostConfig != null && hostConfig.getNetworkMode() != null) {
            return hostConfig.getNetworkMode();
        }
        return networkAliases.keySet().stream().findFirst().orElse(null);
    }

    /**
     * Aliases that must exist on the recreated container. Docker's create API
     * applies aliases to the endpoint named by {@code HostConfig.NetworkMode};
     * the remaining networks (none for the app tier) are not reconnected here.
     */
    public List<String> primaryAliases() {
        String mode = networkMode();
        if (mode == null) {
            return List.of();
        }
        return networkAliases.getOrDefault(mode, List.of());
    }

    public boolean hasHealthcheck() {
        return healthcheck != null && healthcheck.getTest() != null
                && !healthcheck.getTest().isEmpty();
    }
}
