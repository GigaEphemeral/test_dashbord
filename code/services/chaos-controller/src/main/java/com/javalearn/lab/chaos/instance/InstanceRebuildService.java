package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.error.InstanceRebuildConflictException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerReadiness;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.metrics.PrometheusGateway;
import com.javalearn.lab.chaos.metrics.PrometheusMatrix;
import com.javalearn.lab.chaos.metrics.PrometheusPoint;
import com.javalearn.lab.chaos.metrics.PrometheusSeries;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

/**
 * Stage2 B4-02 controlled per-instance rebuild (design §4.2/§4.6/§10.7).
 *
 * <p>Sequence, exactly as accepted in the plan:
 * snapshot → stop/park the old container → create+start the replacement with
 * the same name and copied host config → Docker health (or the app probe when
 * the copied healthcheck is absent) → app probe → pool readback (JDK 21 only) →
 * {@code nginx -s reload} + Nginx entry probe → Prometheus
 * {@code up{job="seckill-app",instance="<instance>:8080"}} → running image
 * tag/id match.</p>
 *
 * <p>On success the ledger row becomes APPLIED with the applied image tag/id,
 * {@code last_apply_at} and a stable period ({@code stable_until}); the parked
 * container is removed best-effort. On any failure the replacement is removed,
 * the parked container is renamed back and started, and the row becomes FAILED
 * with the failing check, the rollback result and a stable period as well. An
 * apply failure is HTTP 200 evidence, never an opaque 500; a failed restore is
 * DEGRADED so no formal benchmark can run on it.</p>
 *
 * <p>The image ref is always {@code <lab.instance.image-repository>:<imageTag>}
 * from the stored config (never from the request), and the gateway never pulls
 * it; a missing image is refused before anything is stopped.</p>
 */
public class InstanceRebuildService {

    public static final String ACTION_APPLY = "APPLY";
    public static final String ACTION_ROLLBACK = "ROLLBACK";

    public static final String STATUS_APPLIED = "APPLIED";
    public static final String STATUS_ROLLED_BACK = "ROLLED_BACK";
    public static final String STATUS_FAILED = "FAILED";
    public static final String STATUS_DEGRADED = "DEGRADED";

    public static final String ROLLBACK_NOT_NEEDED = "NOT_NEEDED";
    public static final String ROLLBACK_RESTORED = "RESTORED";
    public static final String ROLLBACK_FAILED = "FAILED";

    public static final String CHECK_PASS = "PASS";
    public static final String CHECK_FAIL = "FAIL";
    public static final String CHECK_SKIPPED = "SKIPPED";

    private static final String ENV_JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
    private static final String ENV_CORE = "BUSINESS_POOL_CORE_SIZE";
    private static final String ENV_MAX = "BUSINESS_POOL_MAX_SIZE";
    private static final String ENV_QUEUE = "BUSINESS_POOL_QUEUE_CAPACITY";
    private static final String ENV_KEEP_ALIVE = "BUSINESS_POOL_KEEP_ALIVE_SECONDS";
    private static final String ENV_REJECTION = "BUSINESS_POOL_REJECTION_POLICY";

    private static final Set<RunStatus> ACTIVE_RUNS = Set.of(
            RunStatus.RUNNING, RunStatus.STOPPING, RunStatus.VERIFYING);

    private static final long POLL_MILLIS = 1000L;
    private static final long PROMETHEUS_POLL_MILLIS = 2000L;

    private static final Logger log = LoggerFactory.getLogger(InstanceRebuildService.class);

    private final InstanceConfigProperties properties;
    private final InstanceConfigValidator validator;
    private final InstanceConfigRepository repository;
    private final RunLedgerRepository runRepository;
    private final LedgerProperties ledgerProperties;
    private final LedgerBootstrap bootstrap;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<InstanceRebuildGateway> rebuildGatewayProvider;
    private final ObjectProvider<ContainerGateway> containerGatewayProvider;
    private final PrometheusGateway prometheusGateway;
    private final InstanceProbeGateway probeGateway;
    private final String labToken;

    /** One rebuild per instance at a time; the ledger APPLYING row is the durable trace. */
    private final Set<String> inFlight = ConcurrentHashMap.newKeySet();

    public InstanceRebuildService(InstanceConfigProperties properties,
                                  InstanceConfigValidator validator,
                                  InstanceConfigRepository repository,
                                  RunLedgerRepository runRepository,
                                  LedgerProperties ledgerProperties,
                                  LedgerBootstrap bootstrap,
                                  ObjectMapper objectMapper,
                                  Clock clock,
                                  ObjectProvider<InstanceRebuildGateway> rebuildGatewayProvider,
                                  ObjectProvider<ContainerGateway> containerGatewayProvider,
                                  PrometheusGateway prometheusGateway,
                                  InstanceProbeGateway probeGateway,
                                  String labToken) {
        this.properties = properties;
        this.validator = validator;
        this.repository = repository;
        this.runRepository = runRepository;
        this.ledgerProperties = ledgerProperties;
        this.bootstrap = bootstrap;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.rebuildGatewayProvider = rebuildGatewayProvider;
        this.containerGatewayProvider = containerGatewayProvider;
        this.prometheusGateway = prometheusGateway;
        this.probeGateway = probeGateway;
        this.labToken = labToken;
    }

    /** {@code POST /lab/instances/{instance}/apply}: rebuild with the stored config. */
    public InstanceViews.InstanceApplyView apply(String instance) {
        return rebuild(instance, Action.APPLY);
    }

    /** {@code POST /lab/instances/{instance}/rollback}: rebuild with previous_config. */
    public InstanceViews.InstanceApplyView rollback(String instance) {
        return rebuild(instance, Action.ROLLBACK);
    }

    /**
     * {@code POST /lab/instances/{instance}/restart}: a manual controlled
     * restart with the config that is already applied. It refuses unless the
     * stored status is APPLIED, so a restart can never silently promote an
     * unsaved/draft config.
     */
    public InstanceViews.InstanceApplyView restart(String instance) {
        return rebuild(instance, Action.RESTART);
    }

    // ------------------------------------------------------------- refusals

    private InstanceViews.InstanceApplyView rebuild(String instance, Action action) {
        requireKnown(instance);
        requireReady();
        if (!inFlight.add(instance)) {
            throw new InstanceRebuildConflictException("another rebuild of " + instance
                    + " is already in flight; wait for it to finish");
        }
        try {
            StoredInstanceConfig stored = find(instance).orElseThrow(() ->
                    new InstanceRebuildConflictException("no stored configuration for " + instance
                            + "; PUT /lab/instances/" + instance + "/config first"));
            String targetJson = switch (action) {
                case APPLY -> stored.configJson();
                case ROLLBACK -> stored.previousConfigJson();
                case RESTART -> {
                    if (stored.status() != InstanceConfigStatus.APPLIED) {
                        throw new InstanceRebuildConflictException("restart needs an APPLIED config; "
                                + instance + " is " + stored.status()
                                + " (use apply for a saved draft)");
                    }
                    yield stored.configJson();
                }
            };
            if (targetJson == null) {
                throw new InstanceRebuildConflictException("no previous configuration for "
                        + instance + "; a rollback needs at least one earlier saved config");
            }
            requireNoActiveRun(instance);

            InstanceConfig target = parse(instance, targetJson);
            rejectJdk8(instance, target);
            // Re-validate: regenerates argv and re-applies the current budget gate.
            InstanceConfigValidator.ValidationResult validation = validator.validate(instance, target);
            String imageRef = imageRef(instance, target);
            InstanceRebuildGateway gateway = rebuildGateway();

            if (!gateway.imageExists(imageRef)) {
                throw new InstanceRebuildConflictException("image '" + imageRef
                        + "' is not present locally; the rebuild never pulls - build or import it first");
            }
            String newImageId = gateway.imageId(imageRef);
            Instant startedAt = clock.instant();
            markApplying(instance, startedAt);
            return execute(instance, action, stored, validation, imageRef, newImageId,
                    startedAt, gateway);
        } finally {
            inFlight.remove(instance);
        }
    }

    private void requireKnown(String instance) {
        if (!properties.isKnownInstance(instance)) {
            throw new InstanceNotFoundException("unknown instance '" + instance
                    + "'; configured: " + properties.getNames());
        }
    }

    private void requireReady() {
        LedgerReadiness.requireReady(ledgerProperties, bootstrap);
    }

    private void requireNoActiveRun(String instance) {
        List<RunRecord> active;
        try {
            active = runRepository.findRunsByStatuses(List.copyOf(ACTIVE_RUNS));
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance rebuild run-lock check");
        }
        if (!active.isEmpty()) {
            RunRecord run = active.get(0);
            throw new InstanceConfigConflictException("run " + run.runId() + " is "
                    + run.status() + "; a controlled rebuild of " + instance
                    + " is only allowed outside RUNNING/STOPPING/VERIFYING runs "
                    + "(stop the run first)");
        }
    }

    /**
     * Only the configured JDK 8 slot may run the jdk8 image. In the historical
     * {@code shell} mode that is the separate {@code seckill-app-jdk8}
     * container; in {@code app-slot} mode it is one of app-1/2/3 (2x21 + 1x8).
     */
    private void rejectJdk8(String instance, InstanceConfig config) {
        boolean jdk8 = "jdk8".equals(config.imageTag())
                || (config.jdkVersion() != null && config.jdkVersion() == 8);
        boolean slot = instance.equals(properties.getJdk8Instance());
        if (jdk8 && !slot) {
            throw new InvalidInstanceConfigException("instance " + instance
                    + " cannot use the jdk8 image: only the configured JDK 8 slot ("
                    + properties.getJdk8Instance() + ", mode " + properties.getJdk8Mode()
                    + ") may run it");
        }
        if (slot && !jdk8) {
            throw new InvalidInstanceConfigException("instance " + instance
                    + " is the configured JDK 8 slot and must pin jdkVersion=8/imageTag=jdk8");
        }
    }

    /** Only {@code <repository>:<tag>} is ever built, and the tag is allow-listed. */
    private String imageRef(String instance, InstanceConfig config) {
        String tag = config.imageTag();
        if (tag == null || tag.isBlank()) {
            throw new InvalidInstanceConfigException("instance " + instance
                    + " has no imageTag; pin a verified image before applying/rolling back");
        }
        if (!properties.getImageTags().contains(tag)) {
            throw new InvalidInstanceConfigException("imageTag '" + tag
                    + "' is not allow-listed (" + properties.getImageTags() + ")");
        }
        return properties.imageRef(tag);
    }

    private InstanceRebuildGateway rebuildGateway() {
        InstanceRebuildGateway gateway;
        try {
            gateway = rebuildGatewayProvider.getIfAvailable();
        } catch (RuntimeException e) {
            gateway = null;
        }
        if (gateway == null) {
            throw new DockerUnavailableException("Docker rebuild gateway is not available "
                    + "(lab.docker.enabled=false or no DockerClient); cannot rebuild the instance");
        }
        return gateway;
    }

    private ContainerGateway containerGateway() {
        ContainerGateway gateway = containerGatewayProvider.getIfAvailable();
        if (gateway == null) {
            throw new DockerUnavailableException("Docker container gateway is not available "
                    + "(lab.docker.enabled=false or no DockerClient); cannot verify the rebuild");
        }
        return gateway;
    }

    // ------------------------------------------------------------- rebuild

    private InstanceViews.InstanceApplyView execute(String instance, Action action,
                                                    StoredInstanceConfig stored,
                                                    InstanceConfigValidator.ValidationResult validation,
                                                    String imageRef, String imageId,
                                                    Instant startedAt,
                                                    InstanceRebuildGateway gateway) {
        long startedNanos = System.nanoTime();
        long applyDeadline = startedNanos + TimeUnit.SECONDS.toNanos(
                properties.getApplyTimeoutSeconds());
        List<InstanceViews.ApplyCheckView> checks = new ArrayList<>();
        String containerName = properties.containerName(instance);
        String parkName = parkName(containerName, startedAt);
        InstanceSnapshot snapshot = null;
        String newContainerId = null;
        boolean mutated = false;
        boolean replacementAttempted = false;
        String stage = "snapshot";
        try {
            long snapshotStart = System.nanoTime();
            try {
                snapshot = gateway.snapshot(instance);
                checks.add(new InstanceViews.ApplyCheckView("snapshot", CHECK_PASS,
                        millis(snapshotStart), "container " + snapshot.containerName() + " ("
                        + snapshot.containerId() + ") image " + snapshot.imageRef() + " / "
                        + snapshot.imageId()));
            } catch (RuntimeException e) {
                checks.add(new InstanceViews.ApplyCheckView("snapshot", CHECK_FAIL,
                        millis(snapshotStart), describe(e)));
                throw new CheckFailure("snapshot", describe(e), e);
            }
            stage = "park";
            mutated = true;
            InstanceSnapshot toPark = snapshot;
            timed(checks, stage, () -> {
                gateway.stopAndPark(instance, parkName);
                return "stopped and parked as " + parkName;
            });
            Map<String, String> env = buildEnv(toPark.env(), validation.jvmArgs(), validation.config());
            stage = "create";
            replacementAttempted = true;
            newContainerId = timed(checks, stage,
                    () -> gateway.createFrom(toPark, env, imageRef, containerName));
            stage = "start";
            timed(checks, stage, () -> {
                gateway.start(containerName);
                return "started " + containerName + " with " + imageRef;
            });
            stage = "docker-health";
            timed(checks, stage, () -> waitHealth(instance, toPark, containerName, applyDeadline));
            checkAppProbe(instance, checks, applyDeadline);
            checkPool(instance, validation.config(), checks, applyDeadline);
            checkNginx(gateway, checks, applyDeadline);
            checkPrometheus(instance, checks, applyDeadline);
            checkImage(instance, gateway, imageRef, imageId, checks, applyDeadline);

            Instant now = clock.instant();
            Instant stableUntil = now.plusSeconds(properties.getStableSeconds());
            String ledgerError = null;
            int revision = stored.revision();
            try {
                if (action != Action.ROLLBACK) {
                    repository.markApplied(instance, imageRef, imageId, snapshot.imageRef(),
                            snapshot.imageId(), now, stableUntil);
                } else {
                    repository.markRolledBack(instance, stored.previousConfigJson(),
                            stored.configJson(), imageRef, imageId, snapshot.imageRef(),
                            snapshot.imageId(), now, stableUntil);
                    revision = stored.revision() + 1;
                }
            } catch (DataAccessException e) {
                // The containers are already in the verified target state; a
                // ledger hiccup must not trigger a rollback of a healthy rebuild.
                ledgerError = "rebuild succeeded but the ledger outcome write failed: "
                        + e.getMostSpecificCause().getMessage();
                log.error("Could not record the successful rebuild of {}", instance, e);
            }
            removeParkedBestEffort(gateway, parkName);
            String outcome = action == Action.ROLLBACK ? STATUS_ROLLED_BACK : STATUS_APPLIED;
            return new InstanceViews.InstanceApplyView(instance, action.name(), outcome,
                    false, ROLLBACK_NOT_NEEDED, containerName, newContainerId, imageRef, imageId,
                    snapshot.imageRef(), snapshot.imageId(), javaToolOptions(validation.jvmArgs()),
                    List.copyOf(checks), millis(startedNanos), stableUntil.toString(), null,
                    ledgerError, revision);
        } catch (RuntimeException failure) {
            String failedCheck = failure instanceof CheckFailure cf ? cf.check : stage;
            String message = describe(failure);
            addFailureCheck(checks, failedCheck, message);
            return recover(instance, action, stored, snapshot, containerName, parkName,
                    newContainerId, mutated, replacementAttempted, checks, startedNanos,
                    failedCheck, message, gateway);
        }
    }

    /**
     * Failure path: remove the replacement (it holds the original name), restore
     * the parked container under its original name and start it, wait until it is
     * running, reload Nginx for the restored IP and wait for the app probe. The
     * ledger records FAILED (or DEGRADED when the restore itself failed) with the
     * failing check and the stable period.
     */
    private InstanceViews.InstanceApplyView recover(String instance, Action action,
                                                    StoredInstanceConfig stored,
                                                    InstanceSnapshot snapshot, String containerName,
                                                    String parkName, String newContainerId,
                                                    boolean mutated, boolean replacementAttempted,
                                                    List<InstanceViews.ApplyCheckView> checks,
                                                    long startedNanos, String failedCheck,
                                                    String message,
                                                    InstanceRebuildGateway gateway) {
        boolean restored = false;
        String rollback = ROLLBACK_NOT_NEEDED;
        String restoreError = null;
        if (mutated) {
            long restoreStart = System.nanoTime();
            try {
                if (replacementAttempted) {
                    // The replacement (or a half-created one) holds the original
                    // name; restore needs that name back. Never runs when only the
                    // park step failed, so the old container is never removed.
                    try {
                        gateway.remove(containerName, true);
                    } catch (RuntimeException e) {
                        log.warn("Could not remove the replacement {}: {}", containerName,
                                e.getMessage());
                    }
                }
                gateway.restore(parkName, containerName);
                waitRunning(containerName);
                try {
                    gateway.reloadNginx();
                } catch (RuntimeException e) {
                    log.warn("Nginx reload after restoring {} failed: {}", containerName,
                            e.getMessage());
                }
                restored = true;
                rollback = ROLLBACK_RESTORED;
                checks.add(new InstanceViews.ApplyCheckView("rollback", CHECK_PASS,
                        millis(restoreStart), "restored " + parkName + " as " + containerName
                        + " and started it"));
            } catch (RuntimeException e) {
                restoreError = describe(e);
                rollback = ROLLBACK_FAILED;
                checks.add(new InstanceViews.ApplyCheckView("rollback", CHECK_FAIL,
                        millis(restoreStart), restoreError));
                log.error("Automatic restore of {} failed: {}", containerName, restoreError, e);
            }
        }

        Instant now = clock.instant();
        Instant stableUntil = now.plusSeconds(properties.getStableSeconds());
        String ledgerError = "check " + failedCheck + " failed: " + message;
        String status;
        try {
            if (restored) {
                repository.markFailed(instance, ledgerError + "; rollback RESTORED",
                        now, stableUntil,
                        snapshot == null ? null : snapshot.imageRef(),
                        snapshot == null ? null : snapshot.imageId());
                status = STATUS_FAILED;
            } else if (mutated) {
                repository.markDegraded(instance, ledgerError + "; rollback FAILED: " + restoreError
                        + "; instance " + instance + " needs an operator", now, stableUntil);
                status = STATUS_DEGRADED;
            } else {
                repository.markFailed(instance, ledgerError + "; rollback NOT_NEEDED", now,
                        stableUntil, null, null);
                status = STATUS_FAILED;
            }
        } catch (DataAccessException e) {
            // The container outcome is already known; report it, and surface the
            // ledger problem in the message instead of throwing a 503 that hides it.
            log.error("Could not record the rebuild outcome for {}", instance, e);
            status = restored || !mutated ? STATUS_FAILED : STATUS_DEGRADED;
            message = message + "; ledger outcome write failed: "
                    + e.getMostSpecificCause().getMessage();
        }
        return new InstanceViews.InstanceApplyView(instance, action.name(), status, restored,
                rollback, containerName, newContainerId,
                restored && snapshot != null ? snapshot.imageRef() : null,
                restored && snapshot != null ? snapshot.imageId() : null,
                snapshot == null ? null : snapshot.imageRef(),
                snapshot == null ? null : snapshot.imageId(),
                javaToolOptions(intendedArgs(stored, action)),
                List.copyOf(checks), millis(startedNanos), stableUntil.toString(), failedCheck,
                message, stored.revision());
    }

    private void removeParkedBestEffort(InstanceRebuildGateway gateway, String parkName) {
        try {
            gateway.remove(parkName, true);
        } catch (RuntimeException e) {
            // A leftover parked container is a housekeeping issue, never a reason
            // to fail a healthy apply; it is easy to spot by name.
            log.warn("Could not remove parked container {}: {}", parkName, e.getMessage());
        }
    }

    // --------------------------------------------------------------- health

    private String waitHealth(String instance, InstanceSnapshot snapshot, String containerName,
                              long applyDeadline) {
        long healthDeadline = Math.min(applyDeadline,
                System.nanoTime() + TimeUnit.SECONDS.toNanos(
                        properties.getHealthTimeoutSeconds()));
        ContainerGateway containers = containerGateway();
        String last = "no observation";
        while (System.nanoTime() < healthDeadline) {
            if (snapshot.hasHealthcheck()) {
                var view = containers.inspectLabContainer(containerName);
                last = "docker health=" + view.health() + " state=" + view.state();
                if ("healthy".equals(view.health())) {
                    return "docker health healthy (" + view.state() + ")";
                }
                // A cold Spring Boot start can outlive Docker's
                // start_period + retries window, so an "unhealthy" sample is
                // not final: keep polling until the health budget expires.
                if (!"running".equalsIgnoreCase(view.state())) {
                    throw new IllegalStateException("replacement " + containerName + " is "
                            + view.state() + " instead of running; last " + last
                            + recentLogs(containers, containerName));
                }
            } else {
                // No copied healthcheck: the app probe is the readiness gate.
                var result = probeGateway.get(appProbeUrl(instance), null,
                        properties.getProbeTimeoutSeconds());
                last = "app probe " + result.describe();
                if (result.ok()) {
                    return "no copied healthcheck; app probe passed instead";
                }
            }
            sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("health gate did not pass within "
                + properties.getHealthTimeoutSeconds() + "s (apply budget "
                + properties.getApplyTimeoutSeconds() + "s; " + last
                + recentLogs(containers, containerName));
    }

    /** Last lines of a failed replacement, bounded; the check detail stays readable. */
    private static String recentLogs(ContainerGateway containers, String containerName) {
        try {
            String text = containers.tailLogs(containerName, 40);
            if (text == null || text.isBlank()) {
                return "; logs: <empty>";
            }
            String trimmed = text.length() > 1200 ? text.substring(text.length() - 1200) : text;
            return "; logs: " + trimmed;
        } catch (RuntimeException e) {
            return "; logs unavailable: " + e.getMessage();
        }
    }

    private void waitRunning(String containerName) {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(
                properties.getHealthTimeoutSeconds());
        String last = "no observation";
        while (System.nanoTime() < deadline) {
            var view = containerGateway().inspectLabContainer(containerName);
            last = view.state();
            if ("running".equalsIgnoreCase(view.state())) {
                return;
            }
            sleep(POLL_MILLIS);
        }
        throw new IllegalStateException("restored container " + containerName
                + " is not running within " + properties.getHealthTimeoutSeconds()
                + "s (last state: " + last + ")");
    }

    // --------------------------------------------------------------- checks

    private void checkAppProbe(String instance, List<InstanceViews.ApplyCheckView> checks,
                               long applyDeadline) {
        andThen(checks, "app-probe", () -> {
            requireBudget(applyDeadline, "app-probe");
            var result = probeGateway.get(appProbeUrl(instance), null,
                    properties.getProbeTimeoutSeconds());
            if (!result.ok()) {
                throw new IllegalStateException("app probe failed: " + result.describe());
            }
            return "GET " + appProbeUrl(instance) + " -> " + result.describe();
        });
    }

    private void checkPool(String instance, InstanceConfig config,
                           List<InstanceViews.ApplyCheckView> checks, long applyDeadline) {
        if (config.jdkVersion() != null && config.jdkVersion() == 8
                && !properties.isJdk8AppSlotMode()) {
            checks.add(new InstanceViews.ApplyCheckView("pool-probe", CHECK_SKIPPED, 0L,
                    "JDK 8 shell has no /lab/pool readback (LIMITED-OBSERVABILITY)"));
            return;
        }
        if (config.corePoolSize() == null && config.maxPoolSize() == null
                && config.queueCapacity() == null && config.keepAliveSeconds() == null
                && config.rejectionPolicy() == null) {
            checks.add(new InstanceViews.ApplyCheckView("pool-probe", CHECK_SKIPPED, 0L,
                    "configuration carries no business-pool values to compare"));
            return;
        }
        andThen(checks, "pool-probe", () -> {
            requireBudget(applyDeadline, "pool-probe");
            String url = poolProbeUrl(instance);
            var result = probeGateway.get(url, labToken, properties.getProbeTimeoutSeconds());
            if (!result.ok()) {
                throw new IllegalStateException("pool readback failed: " + result.describe());
            }
            JsonNode data;
            try {
                data = objectMapper.readTree(result.body()).path("data");
            } catch (Exception e) {
                throw new IllegalStateException("pool readback is not JSON: " + result.describe());
            }
            if (data.isMissingNode() || data.isNull()) {
                throw new IllegalStateException("pool readback has no data node: " + result.describe());
            }
            List<String> mismatches = new ArrayList<>();
            compare(mismatches, "corePoolSize", config.corePoolSize(), data.path("corePoolSize"));
            compare(mismatches, "maxPoolSize", config.maxPoolSize(), data.path("maxPoolSize"));
            compare(mismatches, "queueCapacity", config.queueCapacity(), data.path("queueCapacity"));
            compare(mismatches, "keepAliveSeconds", config.keepAliveSeconds(),
                    data.path("keepAliveSeconds"));
            if (config.rejectionPolicy() != null
                    && !config.rejectionPolicy().equals(data.path("rejectionPolicy").asText())) {
                mismatches.add("rejectionPolicy expected " + config.rejectionPolicy()
                        + " but read " + data.path("rejectionPolicy").asText());
            }
            if (!mismatches.isEmpty()) {
                throw new IllegalStateException("effective pool differs from the configuration: "
                        + String.join("; ", mismatches));
            }
            return "GET " + url + " -> core=" + data.path("corePoolSize").asText()
                    + " max=" + data.path("maxPoolSize").asText()
                    + " queue=" + data.path("queueCapacity").asText()
                    + " keepAlive=" + data.path("keepAliveSeconds").asText()
                    + " policy=" + data.path("rejectionPolicy").asText();
        });
    }

    private void checkNginx(InstanceRebuildGateway gateway,
                            List<InstanceViews.ApplyCheckView> checks, long applyDeadline) {
        andThen(checks, "nginx-reload", () -> {
            requireBudget(applyDeadline, "nginx-reload");
            gateway.reloadNginx();
            var result = probeGateway.get(properties.getNginxProbeUrl(), null,
                    properties.getProbeTimeoutSeconds());
            if (!result.ok()) {
                throw new IllegalStateException("Nginx upstream probe failed after reload: "
                        + result.describe());
            }
            return "nginx -s reload in " + properties.getNginxContainer() + "; GET "
                    + properties.getNginxProbeUrl() + " -> " + result.describe();
        });
    }

    private void checkPrometheus(String instance, List<InstanceViews.ApplyCheckView> checks,
                                 long applyDeadline) {
        andThen(checks, "prometheus-up", () -> {
            requireBudget(applyDeadline, "prometheus-up");
            String job = properties.getJdk8Instance().equals(instance)
                    ? properties.getJdk8PrometheusJob() : "seckill-app";
            String query = "up{job=\"" + job + "\",instance=\"" + instance + ":8080\"}";
            long waitNanos = Math.min(
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(
                            properties.getPrometheusUpWaitSeconds()),
                    applyDeadline);
            String last = "no sample yet";
            do {
                try {
                    PrometheusMatrix matrix = prometheusGateway.queryInstant(query,
                            clock.instant().getEpochSecond());
                    for (PrometheusSeries series : matrix.series()) {
                        List<PrometheusPoint> points = series.points();
                        if (!points.isEmpty()) {
                            double value = points.get(points.size() - 1).value();
                            last = "up=" + value;
                            if (value == 1.0d) {
                                return query + " -> 1";
                            }
                        }
                    }
                } catch (RuntimeException e) {
                    last = e.getMessage();
                }
                if (System.nanoTime() >= waitNanos) {
                    break;
                }
                sleep(PROMETHEUS_POLL_MILLIS);
            } while (true);
            throw new IllegalStateException("Prometheus up gate not satisfied within "
                    + properties.getPrometheusUpWaitSeconds() + "s (" + last + ")");
        });
    }

    private void checkImage(String instance, InstanceRebuildGateway gateway, String imageRef,
                            String imageId, List<InstanceViews.ApplyCheckView> checks,
                            long applyDeadline) {
        andThen(checks, "image-identity", () -> {
            requireBudget(applyDeadline, "image-identity");
            InstanceSnapshot running = gateway.snapshot(instance);
            if (!imageRef.equals(running.imageRef())) {
                throw new IllegalStateException("running image tag is '" + running.imageRef()
                        + "', expected '" + imageRef + "'");
            }
            if (imageId != null && !imageId.equals(running.imageId())) {
                throw new IllegalStateException("running image id is '" + running.imageId()
                        + "', expected '" + imageId + "' (resolved from " + imageRef + ")");
            }
            return "running " + running.containerName() + " image " + running.imageRef()
                    + " / " + running.imageId();
        });
    }

    // ---------------------------------------------------------------- utils

    private Map<String, String> buildEnv(Map<String, String> copied, List<String> jvmArgs,
                                         InstanceConfig config) {
        Map<String, String> env = new LinkedHashMap<>(copied);
        env.put(ENV_JAVA_TOOL_OPTIONS, javaToolOptions(jvmArgs));
        putIfPresent(env, ENV_CORE, config.corePoolSize());
        putIfPresent(env, ENV_MAX, config.maxPoolSize());
        putIfPresent(env, ENV_QUEUE, config.queueCapacity());
        putIfPresent(env, ENV_KEEP_ALIVE, config.keepAliveSeconds());
        putIfPresent(env, ENV_REJECTION, config.rejectionPolicy());
        return env;
    }

    private static void putIfPresent(Map<String, String> env, String key, Object value) {
        if (value != null) {
            env.put(key, String.valueOf(value));
        }
    }

    private static String javaToolOptions(List<String> jvmArgs) {
        return String.join(" ", jvmArgs);
    }

    private List<String> intendedArgs(StoredInstanceConfig stored, Action action) {
        try {
            InstanceConfig config = parse(stored.instance(), action == Action.ROLLBACK
                    ? stored.previousConfigJson() : stored.configJson());
            return validator.validate(stored.instance(), config).jvmArgs();
        } catch (RuntimeException e) {
            return List.of();
        }
    }

    private String appProbeUrl(String instance) {
        return String.format(properties.getAppProbeUrlTemplate(), instance);
    }

    private String poolProbeUrl(String instance) {
        return String.format(properties.getPoolProbeUrlTemplate(), instance);
    }

    private static void requireBudget(long applyDeadline, String check) {
        if (System.nanoTime() >= applyDeadline) {
            throw new IllegalStateException("apply budget exhausted before check '" + check
                    + "' (lab.instance.apply-timeout-seconds)");
        }
    }

    private static String parkName(String containerName, Instant startedAt) {
        return containerName + "-pre-" + startedAt.toEpochMilli();
    }

    private static void compare(List<String> mismatches, String field, Integer expected,
                                JsonNode actual) {
        if (expected == null) {
            return;
        }
        if (!actual.isNumber() || actual.asInt() != expected) {
            mismatches.add(field + " expected " + expected + " but read " + actual.asText());
        }
    }

    private <T> T timed(List<InstanceViews.ApplyCheckView> checks, String name, CheckBody<T> body) {
        long start = System.nanoTime();
        try {
            T value = body.run();
            checks.add(new InstanceViews.ApplyCheckView(name, CHECK_PASS, millis(start),
                    String.valueOf(value)));
            return value;
        } catch (RuntimeException e) {
            long duration = millis(start);
            checks.add(new InstanceViews.ApplyCheckView(name, CHECK_FAIL, duration, describe(e)));
            throw new CheckFailure(name, describe(e), e);
        }
    }

    private void andThen(List<InstanceViews.ApplyCheckView> checks, String name, CheckBody<String> body) {
        timed(checks, name, body);
    }

    private void addFailureCheck(List<InstanceViews.ApplyCheckView> checks, String name,
                                 String detail) {
        boolean alreadyRecorded = !checks.isEmpty()
                && checks.get(checks.size() - 1).name().equals(name)
                && CHECK_FAIL.equals(checks.get(checks.size() - 1).status());
        if (!alreadyRecorded) {
            checks.add(new InstanceViews.ApplyCheckView(name, CHECK_FAIL, 0L, detail));
        }
    }

    private Optional<StoredInstanceConfig> find(String instance) {
        try {
            return repository.find(instance);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance config read");
        }
    }

    private void markApplying(String instance, Instant now) {
        try {
            repository.markApplying(instance, now);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance config APPLYING");
        }
    }

    private InstanceConfig parse(String instance, String json) {
        try {
            return objectMapper.readValue(json, InstanceConfig.class);
        } catch (Exception e) {
            throw new LedgerUnavailableException("stored instance config for " + instance
                    + " is unreadable: " + e.getMessage());
        }
    }

    private static String describe(RuntimeException e) {
        String message = e.getMessage();
        return message == null || message.isBlank() ? e.getClass().getSimpleName() : message;
    }

    private static long millis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedNanos);
    }

    private static void sleep(long millis) {
        try {
            TimeUnit.MILLISECONDS.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while waiting for the instance");
        }
    }

    private enum Action {
        APPLY, ROLLBACK, RESTART
    }

    /** A named health gate failure; carries the check name for the ledger evidence. */
    private static final class CheckFailure extends RuntimeException {

        private final String check;

        private CheckFailure(String check, String message, Throwable cause) {
            super(message, cause);
            this.check = check;
        }
    }

    @FunctionalInterface
    private interface CheckBody<T> {
        T run();
    }
}
