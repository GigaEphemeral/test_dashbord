package com.javalearn.lab.chaos.instance;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.model.ContainerNetwork;
import com.github.dockerjava.api.model.ExposedPort;
import com.github.dockerjava.api.model.HealthCheck;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.InternetProtocol;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ExecResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Docker Java implementation of {@link InstanceRebuildGateway} (design §10.7).
 *
 * <p>Safety boundary: the target container must be the configured instance
 * container and carry {@code lab=java-learn}; creation is only possible from a
 * snapshot whose labels already carry that boundary, and the image reference is
 * passed in by the service (never by a request). No pull, no arbitrary mount,
 * no shell command.</p>
 *
 * <p>The snapshot keeps the inspected {@link HostConfig} and reuses it on
 * create, which is the closest Docker API equivalent of
 * {@code compose up -d --no-deps --force-recreate app-N}: memory limit, swap,
 * binds, restart policy, log config, ulimits and port bindings survive; only the
 * environment is replaced.</p>
 */
public class DockerInstanceRebuildGateway implements InstanceRebuildGateway {

    public static final String LAB_LABEL = "lab";
    public static final String LAB_VALUE = "java-learn";

    /** Docker stop grace period of the parked container. */
    private static final int STOP_GRACE_SECONDS = 10;

    /** Nginx exec budget; reload is bounded and never waits for a running worker. */
    private static final int NGINX_EXEC_TIMEOUT_SECONDS = 15;

    private static final Logger log = LoggerFactory.getLogger(DockerInstanceRebuildGateway.class);

    private final DockerClient dockerClient;
    private final ContainerGateway containerGateway;
    private final InstanceConfigProperties properties;

    public DockerInstanceRebuildGateway(DockerClient dockerClient, ContainerGateway containerGateway,
                                        InstanceConfigProperties properties) {
        this.dockerClient = dockerClient;
        this.containerGateway = containerGateway;
        this.properties = properties;
    }

    @Override
    public InstanceSnapshot snapshot(String instance) {
        String expected = properties.containerName(instance);
        var inspected = inspect(expected);
        String name = canonicalName(inspected.getName(), expected);
        if (!name.equals(expected)) {
            throw new ContainerNotAllowedException("Container " + name
                    + " is not the configured container of instance " + instance + " (" + expected + ")");
        }
        requireLab(name, inspected.getConfig() == null ? null : inspected.getConfig().getLabels());

        var config = inspected.getConfig();
        Map<String, String> env = new LinkedHashMap<>();
        if (config.getEnv() != null) {
            for (String entry : config.getEnv()) {
                if (entry == null) {
                    continue;
                }
                int separator = entry.indexOf('=');
                if (separator > 0) {
                    env.put(entry.substring(0, separator), entry.substring(separator + 1));
                }
            }
        }
        List<String> exposed = new ArrayList<>();
        if (config.getExposedPorts() != null) {
            for (ExposedPort port : config.getExposedPorts()) {
                exposed.add(port.getPort() + "/"
                        + (port.getProtocol() == null ? "tcp"
                        : port.getProtocol().name().toLowerCase(Locale.ROOT)));
            }
        }
        Map<String, List<String>> aliases = new LinkedHashMap<>();
        if (inspected.getNetworkSettings() != null
                && inspected.getNetworkSettings().getNetworks() != null) {
            for (Map.Entry<String, ContainerNetwork> entry
                    : inspected.getNetworkSettings().getNetworks().entrySet()) {
                ContainerNetwork network = entry.getValue();
                aliases.put(entry.getKey(), network == null || network.getAliases() == null
                        ? List.of() : List.copyOf(network.getAliases()));
            }
        }
        return new InstanceSnapshot(instance, name, inspected.getId(), config.getImage(),
                inspected.getImageId(), env, config.getLabels(), arrayToList(config.getCmd()),
                arrayToList(config.getEntrypoint()), config.getWorkingDir(), config.getUser(),
                config.getHostName(), exposed, aliases, copyHealthcheck(config.getHealthcheck()),
                inspected.getHostConfig());
    }

    @Override
    public boolean imageExists(String imageRef) {
        try {
            dockerClient.inspectImageCmd(imageRef).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while checking image '" + imageRef + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while checking image '" + imageRef + "'", e);
        }
    }

    @Override
    public String imageId(String imageRef) {
        try {
            var inspected = dockerClient.inspectImageCmd(imageRef).exec();
            if (inspected.getId() == null || inspected.getId().isBlank()) {
                throw new DockerUnavailableException("image '" + imageRef + "' has no id");
            }
            return inspected.getId();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Image not found: " + imageRef);
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting image '" + imageRef + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting image '" + imageRef + "'", e);
        }
    }

    @Override
    public void stopAndPark(String instance, String parkName) {
        String container = properties.containerName(instance);
        requireLab(container, inspectLabels(container));
        try {
            dockerClient.stopContainerCmd(container).withTimeout(STOP_GRACE_SECONDS).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + container);
        } catch (DockerException e) {
            // 304 (already stopped) is not an error for the rebuild.
            log.debug("Stop of {} answered {}; continuing", container, e.getMessage());
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to stop " + container, e);
        }
        try {
            dockerClient.renameContainerCmd(container).withName(parkName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + container);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to park " + container + " as " + parkName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to park " + container + " as " + parkName, e);
        }
    }

    @Override
    public String createFrom(InstanceSnapshot snapshot, Map<String, String> env, String imageRef,
                             String name) {
        requireLab(snapshot.containerName(), snapshot.labels());
        if (imageRef == null || imageRef.isBlank()) {
            throw new IllegalArgumentException("image ref is required to create the replacement");
        }
        HostConfig hostConfig = snapshot.hostConfig() != null
                ? snapshot.hostConfig() : HostConfig.newHostConfig();
        hostConfig.withAutoRemove(false);
        if (hostConfig.getNetworkMode() == null) {
            hostConfig.withNetworkMode("jl-net");
        }
        List<String> envEntries = new ArrayList<>();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            envEntries.add(entry.getKey() + "=" + entry.getValue());
        }
        var create = dockerClient.createContainerCmd(imageRef)
                .withName(name)
                .withLabels(snapshot.labels())
                .withEnv(envEntries)
                .withHostConfig(hostConfig)
                .withTty(false)
                .withAttachStdin(false)
                .withAttachStdout(true)
                .withAttachStderr(true);
        // An image swap (jdk21 -> jdk8) must use the NEW image's entrypoint/cmd,
        // not the parked container's. Keeping the old JDK21 entrypoint
        // (-XX:MaxRAMPercentage=70) makes the JDK8 JVM fail before main.
        List<String> command = snapshot.command();
        List<String> entrypoint = snapshot.entrypoint();
        if (!imageRef.equals(snapshot.imageRef())) {
            try {
                var imageConfig = dockerClient.inspectImageCmd(imageRef).exec().getConfig();
                if (imageConfig != null) {
                    command = imageConfig.getCmd() == null
                            ? List.of() : java.util.Arrays.asList(imageConfig.getCmd());
                    entrypoint = imageConfig.getEntrypoint() == null
                            ? List.of() : java.util.Arrays.asList(imageConfig.getEntrypoint());
                }
            } catch (RuntimeException e) {
                throw new DockerUnavailableException(
                        "cannot read entrypoint of new image '" + imageRef + "': " + e.getMessage());
            }
        }
        if (!command.isEmpty()) {
            create.withCmd(command);
        }
        if (!entrypoint.isEmpty()) {
            create.withEntrypoint(entrypoint);
        }
        if (snapshot.workingDir() != null && !snapshot.workingDir().isBlank()) {
            create.withWorkingDir(snapshot.workingDir());
        }
        if (snapshot.user() != null && !snapshot.user().isBlank()) {
            create.withUser(snapshot.user());
        }
        // The old hostname is the old container's short id; pinning it would be
        // misleading, so Docker assigns a fresh one in that case.
        if (snapshot.hostname() != null && !snapshot.hostname().isBlank()
                && !snapshot.containerId().startsWith(snapshot.hostname())) {
            create.withHostName(snapshot.hostname());
        }
        if (!snapshot.exposedPorts().isEmpty()) {
            create.withExposedPorts(snapshot.exposedPorts().stream()
                    .map(ExposedPort::parse).toArray(ExposedPort[]::new));
        }
        if (snapshot.hasHealthcheck()) {
            create.withHealthcheck(snapshot.healthcheck());
        }
        if (!snapshot.primaryAliases().isEmpty()) {
            create.withAliases(snapshot.primaryAliases());
        }
        String containerId;
        try {
            containerId = create.exec().getId();
        } catch (NotFoundException e) {
            throw new DockerUnavailableException("Image '" + imageRef
                    + "' disappeared before the replacement could be created", e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to create replacement container '"
                    + name + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to create replacement container '"
                    + name + "'", e);
        }
        // Docker attaches only the primary network at create time. The lab's
        // ha overlay puts the app tier on jl-sentinel-net as a SECOND network;
        // dropping it leaves REDIS_MODE=sentinel pointing at an unreachable
        // master (and wedges every Lettuce call). Re-attach the snapshot's
        // other networks before the caller starts the replacement.
        for (Map.Entry<String, List<String>> network
                : extraNetworks(snapshot, hostConfig.getNetworkMode()).entrySet()) {
            try {
                dockerClient.connectToNetworkCmd()
                        .withContainerId(containerId)
                        .withNetworkId(network.getKey())
                        .withContainerNetwork(new ContainerNetwork()
                                .withAliases(network.getValue()))
                        .exec();
            } catch (RuntimeException e) {
                throw new DockerUnavailableException("Failed to attach replacement container '"
                        + name + "' to network '" + network.getKey() + "'", e);
            }
        }
        return containerId;
    }

    /**
     * Networks the parked container had besides its primary one, with their
     * aliases. Connecting these after {@code create} is the only way docker-java
     * attaches more than one network before start.
     */
    static Map<String, List<String>> extraNetworks(InstanceSnapshot snapshot, String primaryNetwork) {
        Map<String, List<String>> extra = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> network : snapshot.networkAliases().entrySet()) {
            if (primaryNetwork != null && primaryNetwork.equals(network.getKey())) {
                continue;
            }
            extra.put(network.getKey(), network.getValue());
        }
        return extra;
    }

    @Override
    public void start(String containerName) {
        requireLab(containerName, inspectLabels(containerName));
        try {
            dockerClient.startContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            // 304 already running.
            log.debug("Start of {} answered {}; continuing", containerName, e.getMessage());
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to start " + containerName, e);
        }
    }

    @Override
    public void remove(String containerName, boolean force) {
        try {
            dockerClient.removeContainerCmd(containerName).withForce(force).exec();
        } catch (NotFoundException e) {
            log.debug("Container {} is already gone", containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to remove " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to remove " + containerName, e);
        }
    }

    @Override
    public void rename(String containerName, String newName) {
        try {
            dockerClient.renameContainerCmd(containerName).withName(newName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to rename " + containerName
                    + " to " + newName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to rename " + containerName
                    + " to " + newName, e);
        }
    }

    /**
     * Best-effort restore: the rename back is skipped when the original name is
     * already free/present (a partially failed park), but the original name must
     * end up running. Never leaves a stopped container behind silently.
     */
    @Override
    public void restore(String parkName, String originalName) {
        try {
            dockerClient.renameContainerCmd(parkName).withName(originalName).exec();
        } catch (NotFoundException e) {
            log.warn("Parked container {} is gone; trying to start {} as is", parkName, originalName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to restore " + parkName + " as "
                    + originalName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to restore " + parkName + " as "
                    + originalName, e);
        }
        start(originalName);
    }

    @Override
    public void reloadNginx() {
        String nginx = properties.getNginxContainer();
        ExecResult test = containerGateway.execInContainer(nginx,
                List.of("nginx", "-t"), List.of(), null, NGINX_EXEC_TIMEOUT_SECONDS);
        if (test.timedOut() || test.exitCode() == null || test.exitCode() != 0) {
            throw new DockerUnavailableException("nginx -t failed in " + nginx + ": "
                    + snippet(test.stderr().isBlank() ? test.stdout() : test.stderr()));
        }
        ExecResult reload = containerGateway.execInContainer(nginx,
                List.of("nginx", "-s", "reload"), List.of(), null, NGINX_EXEC_TIMEOUT_SECONDS);
        if (reload.timedOut() || reload.exitCode() == null || reload.exitCode() != 0) {
            throw new DockerUnavailableException("nginx -s reload failed in " + nginx + ": "
                    + snippet(reload.stderr().isBlank() ? reload.stdout() : reload.stderr()));
        }
    }

    // ------------------------------------------------------------------ help

    private com.github.dockerjava.api.command.InspectContainerResponse inspect(String containerName) {
        try {
            return dockerClient.inspectContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting " + containerName, e);
        }
    }

    private Map<String, String> inspectLabels(String containerName) {
        var inspected = inspect(containerName);
        return inspected.getConfig() == null ? null : inspected.getConfig().getLabels();
    }

    private static void requireLab(String containerName, Map<String, String> labels) {
        if (labels == null || !LAB_VALUE.equals(labels.get(LAB_LABEL))) {
            throw new ContainerNotAllowedException("Container is not part of the lab: " + containerName);
        }
    }

    private static List<String> arrayToList(String[] values) {
        return values == null ? List.of() : List.of(values);
    }

    /** Detached copy: the inspected response object is never mutated afterwards. */
    private static HealthCheck copyHealthcheck(HealthCheck source) {
        if (source == null || source.getTest() == null || source.getTest().isEmpty()) {
            return null;
        }
        return new HealthCheck()
                .withTest(List.copyOf(source.getTest()))
                .withInterval(source.getInterval())
                .withTimeout(source.getTimeout())
                .withRetries(source.getRetries())
                .withStartPeriod(source.getStartPeriod())
                .withStartInterval(source.getStartInterval());
    }

    private static String canonicalName(String inspectedName, String fallback) {
        if (inspectedName == null || inspectedName.isEmpty()) {
            return fallback;
        }
        return inspectedName.startsWith("/") ? inspectedName.substring(1) : inspectedName;
    }

    private static String snippet(String value) {
        if (value == null) {
            return "";
        }
        String collapsed = value.replaceAll("\\s+", " ").trim();
        return collapsed.length() > 200 ? collapsed.substring(0, 200) + "..." : collapsed;
    }
}
