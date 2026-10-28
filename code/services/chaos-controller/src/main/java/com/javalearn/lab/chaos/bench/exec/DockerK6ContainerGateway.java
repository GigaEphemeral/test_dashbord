package com.javalearn.lab.chaos.bench.exec;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.CreateContainerResponse;
import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.AccessMode;
import com.github.dockerjava.api.model.Bind;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.ContainerConfig;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.HostConfig;
import com.github.dockerjava.api.model.RestartPolicy;
import com.github.dockerjava.api.model.Volume;
import com.javalearn.lab.chaos.error.BenchRunConflictException;
import com.javalearn.lab.chaos.error.BenchUnavailableException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Docker Java implementation of {@link K6ContainerGateway}.
 *
 * <p>Safety boundary: a container that will be logged, stopped or removed must
 * carry {@code lab=java-learn} <em>and</em> {@code lab.role=k6}; the name
 * prefix alone is never trusted. Created containers get exactly the plan's
 * labels (which the planner guarantees include both), {@code autoRemove=false},
 * no restart policy and no TTY. The image is verified locally and never
 * pulled.</p>
 */
public class DockerK6ContainerGateway implements K6ContainerGateway {

    public static final String LAB_LABEL = "lab";
    public static final String LAB_VALUE = "java-learn";
    public static final String ROLE_LABEL = "lab.role";
    public static final String ROLE_VALUE = "k6";
    public static final String RUN_ID_LABEL = "lab.run-id";
    public static final String SCENARIO_LABEL = "lab.scenario";

    /** Defensive hard caps; the executor's configuration is normally smaller. */
    public static final int MAX_TAIL = 10_000;
    public static final int MAX_LOG_BYTES = 4 * 1024 * 1024;

    private static final long LOG_WAIT_SECONDS = 15L;

    private static final Logger log = LoggerFactory.getLogger(DockerK6ContainerGateway.class);

    private final DockerClient dockerClient;
    private final BenchExecProperties properties;

    public DockerK6ContainerGateway(DockerClient dockerClient, BenchExecProperties properties) {
        this.dockerClient = dockerClient;
        this.properties = properties;
    }

    @Override
    public void ensureImage(String image) {
        if (image == null || image.isBlank()) {
            throw new BenchUnavailableException("lab.bench.exec.k6-image is blank: no image to run");
        }
        try {
            dockerClient.inspectImageCmd(image).exec();
        } catch (NotFoundException e) {
            throw new BenchUnavailableException("k6 image '" + image + "' is not present locally; "
                    + "the executor never pulls automatically - run `docker pull " + image
                    + "` first", e);
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while checking image '" + image + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while checking image '" + image + "'", e);
        }
    }

    @Override
    public String createAndStart(BenchLaunchPlan plan) {
        requirePlanLabels(plan);
        Snapshot existing = inspectByName(plan.containerName());
        if (!existing.isAbsent()) {
            if (existing.isLive()) {
                throw new BenchRunConflictException("k6 container '" + plan.containerName()
                        + "' already exists in state " + existing.status()
                        + "; stop it before launching run '" + plan.runId() + "'");
            }
            remove(plan.containerName());
        }

        Bind[] binds = plan.binds().stream()
                .map(DockerK6ContainerGateway::toBind)
                .toArray(Bind[]::new);
        HostConfig hostConfig = HostConfig.newHostConfig()
                .withBinds(binds)
                .withNetworkMode(plan.network())
                .withAutoRemove(false)
                .withRestartPolicy(RestartPolicy.noRestart());
        List<String> env = plan.env().entrySet().stream()
                .map(entry -> entry.getKey() + "=" + entry.getValue())
                .toList();

        CreateContainerResponse created;
        try {
            created = dockerClient.createContainerCmd(plan.image())
                    .withName(plan.containerName())
                    .withCmd(plan.argv())
                    .withEnv(env)
                    .withLabels(plan.labels())
                    .withTty(false)
                    .withAttachStdout(false)
                    .withAttachStderr(false)
                    .withHostConfig(hostConfig)
                    .exec();
        } catch (NotFoundException e) {
            throw new BenchUnavailableException("k6 image '" + plan.image()
                    + "' disappeared before the container could be created", e);
        } catch (ConflictException e) {
            throw new BenchRunConflictException("k6 container '" + plan.containerName()
                    + "' already exists", e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to create k6 container '"
                    + plan.containerName() + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to create k6 container '"
                    + plan.containerName() + "'", e);
        }
        try {
            dockerClient.startContainerCmd(created.getId()).exec();
        } catch (NotFoundException e) {
            throw new BenchUnavailableException("k6 container '" + plan.containerName()
                    + "' vanished before it could be started", e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to start k6 container '"
                    + plan.containerName() + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to start k6 container '"
                    + plan.containerName() + "'", e);
        }
        return created.getId();
    }

    @Override
    public Snapshot inspect(String runId) {
        String expectedName = containerName(runId);
        try {
            Map<String, String> filter = new LinkedHashMap<>();
            filter.put(LAB_LABEL, LAB_VALUE);
            filter.put(RUN_ID_LABEL, runId);
            List<Container> containers = dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(filter)
                    .exec();
            Container found = containers.stream()
                    .filter(this::isK6Container)
                    .findFirst()
                    .orElse(null);
            if (found == null) {
                return inspectByName(expectedName);
            }
            return inspectById(found.getId(), expectedName);
        } catch (ContainerNotAllowedException e) {
            throw e;
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting run '" + runId + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting run '" + runId + "'", e);
        }
    }

    @Override
    public LogChunk logs(String containerName, int tail, int maxBytes) {
        int effectiveTail = Math.max(1, Math.min(tail, MAX_TAIL));
        int budget = Math.max(1, Math.min(maxBytes, MAX_LOG_BYTES));
        requireK6Container(containerName);

        BoundedLogCollector collector = new BoundedLogCollector(budget);
        try (BoundedLogCollector callback = collector) {
            dockerClient.logContainerCmd(containerName)
                    .withStdOut(true)
                    .withStdErr(true)
                    .withTail(effectiveTail)
                    .exec(callback);
            if (!callback.awaitCompletion(LOG_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Timed out after {}s while reading logs for {} (returning partial output)",
                        LOG_WAIT_SECONDS, containerName);
            }
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("k6 container not found: " + containerName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerUnavailableException(
                    "Interrupted while reading logs for " + containerName, e);
        } catch (java.io.IOException e) {
            throw new DockerUnavailableException(
                    "Failed to close the log stream for " + containerName, e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to read logs for " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to read logs for " + containerName, e);
        }
        return collector.toChunk();
    }

    @Override
    public boolean stop(String containerName, int graceSeconds) {
        try {
            requireK6Container(containerName);
        } catch (ContainerNotFoundException e) {
            return false;
        }
        try {
            dockerClient.stopContainerCmd(containerName)
                    .withTimeout(Math.max(1, graceSeconds))
                    .exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (NotModifiedException e) {
            // Docker 304: already stopped, the desired state is reached.
            return false;
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to stop k6 container '"
                    + containerName + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to stop k6 container '"
                    + containerName + "'", e);
        }
    }

    @Override
    public boolean remove(String containerName) {
        try {
            requireK6Container(containerName);
        } catch (ContainerNotFoundException e) {
            return false;
        }
        try {
            dockerClient.removeContainerCmd(containerName).withForce(false).exec();
            return true;
        } catch (NotFoundException e) {
            return false;
        } catch (ConflictException e) {
            log.warn("Best-effort remove left k6 container '{}' in place: it is still running",
                    containerName);
            return false;
        } catch (DockerException e) {
            log.warn("Best-effort remove of k6 container '{}' failed: {}",
                    containerName, e.getMessage());
            return false;
        } catch (RuntimeException e) {
            log.warn("Best-effort remove of k6 container '{}' failed: {}",
                    containerName, e.getMessage());
            return false;
        }
    }

    @Override
    public List<ContainerSummary> listK6Containers() {
        Map<String, String> filter = new LinkedHashMap<>();
        filter.put(LAB_LABEL, LAB_VALUE);
        filter.put(ROLE_LABEL, ROLE_VALUE);
        try {
            return dockerClient.listContainersCmd()
                    .withShowAll(true)
                    .withLabelFilter(filter)
                    .exec()
                    .stream()
                    .filter(this::isK6Container)
                    .map(container -> new ContainerSummary(primaryName(container),
                            container.getId(), labels(container).get(RUN_ID_LABEL),
                            statusOf(container)))
                    .sorted(Comparator.comparing(ContainerSummary::containerName))
                    .toList();
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while listing k6 containers", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while listing k6 containers", e);
        }
    }

    // ------------------------------------------------------------------ probe

    private Snapshot inspectByName(String containerName) {
        try {
            InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerName).exec();
            requireK6Config(containerName, inspected.getConfig());
            return toSnapshot(inspected, containerName);
        } catch (NotFoundException e) {
            return Snapshot.absent(containerName);
        } catch (ContainerNotAllowedException e) {
            throw e;
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + containerName + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + containerName + "'", e);
        }
    }

    private Snapshot inspectById(String containerId, String fallbackName) {
        try {
            InspectContainerResponse inspected = dockerClient.inspectContainerCmd(containerId).exec();
            requireK6Config(fallbackName, inspected.getConfig());
            return toSnapshot(inspected, fallbackName);
        } catch (NotFoundException e) {
            // The container disappeared between list and inspect.
            return Snapshot.absent(fallbackName);
        } catch (ContainerNotAllowedException e) {
            throw e;
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + fallbackName + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + fallbackName + "'", e);
        }
    }

    private Snapshot toSnapshot(InspectContainerResponse inspected, String fallbackName) {
        InspectContainerResponse.ContainerState state = inspected.getState();
        String status = state == null || state.getStatus() == null
                ? "UNKNOWN" : state.getStatus().toUpperCase(Locale.ROOT);
        Integer exitCode = null;
        String startedAt = null;
        String finishedAt = null;
        if (state != null) {
            exitCode = state.getExitCodeLong() != null
                    ? state.getExitCodeLong().intValue() : state.getExitCode();
            startedAt = normalizeTimestamp(state.getStartedAt());
            finishedAt = normalizeTimestamp(state.getFinishedAt());
        }
        String configImage = inspected.getConfig() == null ? null : inspected.getConfig().getImage();
        String scenario = inspected.getConfig() == null ? null
                : labels(inspected.getConfig().getLabels()).get(SCENARIO_LABEL);
        String name = inspected.getName();
        if (name != null && name.startsWith("/")) {
            name = name.substring(1);
        }
        if (name == null || name.isBlank()) {
            name = fallbackName;
        }
        return new Snapshot(status, name, inspected.getId(), exitCode, startedAt, finishedAt,
                configImage, scenario);
    }

    private void requireK6Container(String containerName) {
        InspectContainerResponse inspected;
        try {
            inspected = dockerClient.inspectContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("k6 container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + containerName + "'", e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException(
                    "Docker daemon unavailable while inspecting '" + containerName + "'", e);
        }
        requireK6Config(containerName, inspected.getConfig());
    }

    private void requireK6Config(String containerName, ContainerConfig config) {
        Map<String, String> labels = config == null ? null : config.getLabels();
        if (labels == null || !LAB_VALUE.equals(labels.get(LAB_LABEL))
                || !ROLE_VALUE.equals(labels.get(ROLE_LABEL))) {
            throw new ContainerNotAllowedException("Container is not a lab k6 run: " + containerName);
        }
    }

    private void requirePlanLabels(BenchLaunchPlan plan) {
        Map<String, String> labels = plan.labels();
        if (!LAB_VALUE.equals(labels.get(LAB_LABEL)) || !ROLE_VALUE.equals(labels.get(ROLE_LABEL))) {
            throw new IllegalArgumentException("launch plan is missing the lab=java-learn / "
                    + "lab.role=k6 labels; refusing to create an unlabelled container");
        }
    }

    private boolean isK6Container(Container container) {
        Map<String, String> labels = labels(container);
        return LAB_VALUE.equals(labels.get(LAB_LABEL)) && ROLE_VALUE.equals(labels.get(ROLE_LABEL));
    }

    /**
     * Docker's {@code AccessMode.fromBoolean} means <em>read-write</em> when the
     * flag is true, which is the opposite of the plan's {@code readOnly}. Passing
     * it through directly mounted the scripts read-write and the reports volume
     * read-only - a live k6 then failed to write summary.json.
     */
    static Bind toBind(BenchLaunchPlan.Bind bind) {
        AccessMode mode = bind.readOnly() ? AccessMode.ro : AccessMode.rw;
        return new Bind(bind.source(), new Volume(bind.destination()), mode);
    }

    private static Map<String, String> labels(Container container) {
        return container.getLabels() == null ? Map.of() : container.getLabels();
    }

    private static Map<String, String> labels(Map<String, String> labels) {
        return labels == null ? Map.of() : labels;
    }

    private static String statusOf(Container container) {
        return container.getState() == null ? "UNKNOWN"
                : container.getState().toUpperCase(Locale.ROOT);
    }

    private String containerName(String runId) {
        return properties.getContainerPrefix() + runId;
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

    /** Docker reports {@code 0001-01-01T00:00:00Z} for "never". */
    private static String normalizeTimestamp(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-01-01")) {
            return null;
        }
        return value;
    }

    /** Collects at most {@code budget} bytes and counts everything received. */
    private static final class BoundedLogCollector extends ResultCallback.Adapter<Frame> {

        private final ByteArrayOutputStream kept = new ByteArrayOutputStream();
        private final int budget;
        private long received;

        BoundedLogCollector(int budget) {
            this.budget = budget;
        }

        @Override
        public void onNext(Frame frame) {
            byte[] payload = frame == null ? null : frame.getPayload();
            if (payload == null || payload.length == 0) {
                return;
            }
            received += payload.length;
            int room = budget - kept.size();
            if (room > 0) {
                kept.write(payload, 0, Math.min(room, payload.length));
            }
        }

        LogChunk toChunk() {
            byte[] bytes = kept.toByteArray();
            return new LogChunk(new String(bytes, StandardCharsets.UTF_8), received > bytes.length,
                    bytes.length, received);
        }
    }
}
