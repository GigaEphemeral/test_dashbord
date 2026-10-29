package com.javalearn.lab.chaos.gateway;

import com.github.dockerjava.api.DockerClient;
import com.github.dockerjava.api.async.ResultCallback;
import com.github.dockerjava.api.command.HealthState;
import com.github.dockerjava.api.exception.ConflictException;
import com.github.dockerjava.api.exception.DockerException;
import com.github.dockerjava.api.exception.NotFoundException;
import com.github.dockerjava.api.exception.NotModifiedException;
import com.github.dockerjava.api.model.Container;
import com.github.dockerjava.api.model.Frame;
import com.github.dockerjava.api.model.StreamType;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.error.InvalidTailException;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.model.ExecResult;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

    /** Upper bound for one exec; the probe's own timeout is far smaller. */
    public static final int MAX_EXEC_TIMEOUT_SECONDS = 300;

    private static final Logger log = LoggerFactory.getLogger(DockerContainerGateway.class);
    private static final long LOG_WAIT_SECONDS = 15L;
    private static final Set<String> ALLOWED_SIGNALS = Set.of(SIGSTOP, SIGCONT);

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

    @Override
    public ContainerView inspectLabContainer(String containerName) {
        requireLabContainer(containerName);
        try {
            var inspected = dockerClient.inspectContainerCmd(containerName).exec();
            String state = "unknown";
            String health = "none";
            if (inspected.getState() != null) {
                if (inspected.getState().getStatus() != null) {
                    state = inspected.getState().getStatus();
                }
                HealthState inspectedHealth = inspected.getState().getHealth();
                if (inspectedHealth != null && inspectedHealth.getStatus() != null) {
                    health = inspectedHealth.getStatus();
                }
            }
            return new ContainerView(canonicalName(inspected.getName(), containerName), state, health);
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Docker daemon unavailable while inspecting " + containerName, e);
        }
    }

    @Override
    public java.util.Optional<ContainerFacts> inspectLabContainerFacts(String containerName) {
        requireLabContainer(containerName);
        try {
            var inspected = dockerClient.inspectContainerCmd(containerName).exec();
            var config = inspected.getConfig();
            String image = config == null ? null : config.getImage();
            java.util.Map<String, String> env = new java.util.LinkedHashMap<>();
            if (config != null && config.getEnv() != null) {
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
            return java.util.Optional.of(new ContainerFacts(
                    canonicalName(inspected.getName(), containerName), image,
                    inspected.getImageId(), java.util.Map.copyOf(env)));
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

    @Override
    public void stop(String containerName, int graceSeconds) {
        requireLabContainer(containerName);
        try {
            dockerClient.stopContainerCmd(containerName)
                    .withTimeout(graceSeconds)
                    .exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (NotModifiedException e) {
            // Docker returns 304 when the container is already stopped; treat as done.
            log.debug("Container {} was already stopped", containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to stop " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to stop " + containerName, e);
        }
    }

    @Override
    public void start(String containerName) {
        requireLabContainer(containerName);
        try {
            dockerClient.startContainerCmd(containerName).exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (NotModifiedException e) {
            // Docker returns 304 when the container is already running; treat as done.
            log.debug("Container {} was already running", containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to start " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to start " + containerName, e);
        }
    }

    @Override
    public void restart(String containerName, int graceSeconds) {
        requireLabContainer(containerName);
        try {
            dockerClient.restartContainerCmd(containerName)
                    .withTimeout(graceSeconds)
                    .exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to restart " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to restart " + containerName, e);
        }
    }

    @Override
    public void signal(String containerName, String signal) {
        if (!ALLOWED_SIGNALS.contains(signal)) {
            throw new InvalidChaosActionException("Unsupported signal: " + signal
                    + " (allowed: " + ALLOWED_SIGNALS + ")");
        }
        requireLabContainer(containerName);
        try {
            dockerClient.killContainerCmd(containerName)
                    .withSignal(signal)
                    .exec();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (ConflictException e) {
            // The container is not running (Docker returns 409); nothing to signal.
            throw new InvalidChaosActionException("Container is not running: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to signal " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to signal " + containerName, e);
        }
    }

    @Override
    public ExecResult execInContainer(String containerName,
                                      List<String> command,
                                      List<String> env,
                                      String stdin,
                                      int timeoutSeconds) {
        if (command == null || command.isEmpty()) {
            throw new InvalidChaosActionException("exec command must not be empty");
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_EXEC_TIMEOUT_SECONDS) {
            throw new InvalidChaosActionException("exec timeout must be between 1 and "
                    + MAX_EXEC_TIMEOUT_SECONDS + " seconds");
        }
        requireLabContainer(containerName);

        ByteArrayOutputStream stdout = new ByteArrayOutputStream();
        ByteArrayOutputStream stderr = new ByteArrayOutputStream();
        boolean timedOut = false;
        String execId;
        try {
            execId = dockerClient.execCreateCmd(containerName)
                    .withAttachStdout(true)
                    .withAttachStderr(true)
                    .withAttachStdin(stdin != null)
                    .withTty(false)
                    .withCmd(command.toArray(String[]::new))
                    .withEnv(env == null || env.isEmpty() ? List.of() : env)
                    .exec()
                    .getId();
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (ConflictException e) {
            throw new InvalidChaosActionException("Container is not running: " + containerName);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to create exec in " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to create exec in " + containerName, e);
        }

        try (ResultCallback.Adapter<Frame> callback = new ResultCallback.Adapter<>() {
            @Override
            public void onNext(Frame frame) {
                if (frame == null || frame.getPayload() == null) {
                    return;
                }
                if (StreamType.STDERR.equals(frame.getStreamType())) {
                    stderr.writeBytes(frame.getPayload());
                } else {
                    stdout.writeBytes(frame.getPayload());
                }
            }
        }) {
            dockerClient.execStartCmd(execId)
                    .withDetach(false)
                    .withTty(false)
                    .withStdIn(stdin == null ? null : new ByteArrayInputStream(stdin.getBytes(StandardCharsets.UTF_8)))
                    .exec(callback);
            timedOut = !callback.awaitCompletion(timeoutSeconds, TimeUnit.SECONDS);
            if (timedOut) {
                log.warn("Timed out after {}s while executing a command in {} (aborting the stream)",
                        timeoutSeconds, containerName);
            }
        } catch (NotFoundException e) {
            throw new ContainerNotFoundException("Container not found: " + containerName);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DockerUnavailableException("Interrupted while executing a command in " + containerName, e);
        } catch (IOException e) {
            throw new DockerUnavailableException("Failed to close the exec stream for " + containerName, e);
        } catch (DockerException e) {
            throw new DockerUnavailableException("Failed to execute a command in " + containerName, e);
        } catch (RuntimeException e) {
            throw new DockerUnavailableException("Failed to execute a command in " + containerName, e);
        }

        Integer exitCode = null;
        if (!timedOut) {
            // The stream closed, so the process is done; the inspect call is the
            // only way Docker reports the exit status of a non-detached exec.
            try {
                exitCode = dockerClient.inspectExecCmd(execId).exec().getExitCode();
            } catch (DockerException e) {
                log.debug("Could not read the exit code of exec {} in {}: {}", execId, containerName, e.getMessage());
            } catch (RuntimeException e) {
                log.debug("Could not read the exit code of exec {} in {}: {}", execId, containerName, e.getMessage());
            }
        }
        return new ExecResult(stdout.toString(StandardCharsets.UTF_8),
                stderr.toString(StandardCharsets.UTF_8),
                exitCode,
                timedOut);
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

    /**
     * Canonical container name from an inspect result ("/jl-mysql" → "jl-mysql").
     * Docker resolves names <em>and</em> id-prefixes to the same container, so an
     * id reference echoes a different name than the caller requested; the
     * datastore allow-list is re-checked against that canonical name
     * (M3-1c review). Falls back to the requested name when Docker reports none.
     */
    private static String canonicalName(String inspectedName, String requestedName) {
        if (inspectedName == null || inspectedName.isEmpty()) {
            return requestedName;
        }
        return inspectedName.startsWith("/") ? inspectedName.substring(1) : inspectedName;
    }
}
