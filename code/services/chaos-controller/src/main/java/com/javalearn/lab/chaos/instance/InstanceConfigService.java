package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerFacts;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.LedgerReadiness;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.dao.DataAccessException;

import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Stage2 B4-01 configuration service: validate, normalize, persist a draft and
 * read back the container facts.
 *
 * <p>No restart/apply happens here (B4-02). Saving a draft is refused with
 * 409/40906 when a run is RUNNING and the request actually changes a startup
 * field (JVM/GC/GC-log); changing only pool/Caffeine/Tomcat metadata is
 * allowed. Every ledger access goes through {@link LedgerReadiness}, so a
 * DEGRADED ledger answers 503/50302 instead of a half-written config.</p>
 */
public class InstanceConfigService {

    public static final String READBACK_OK = "OK";
    public static final String READBACK_UNAVAILABLE = "UNAVAILABLE";
    public static final String READBACK_LIMITED = "READBACK_UNAVAILABLE";
    public static final String OBSERVABILITY_FULL = "FULL-OBSERVABILITY";
    public static final String OBSERVABILITY_LIMITED = "LIMITED-OBSERVABILITY";
    public static final String OBSERVABILITY_UNKNOWN = "UNKNOWN";
    public static final String STATUS_NOT_CONFIGURED = "NOT_CONFIGURED";

    private static final String ENV_JAVA_TOOL_OPTIONS = "JAVA_TOOL_OPTIONS";
    private static final String ENV_INSTANCE_ID = "INSTANCE_ID";

    private final InstanceConfigProperties properties;
    private final InstanceConfigValidator validator;
    private final InstanceConfigRepository repository;
    private final RunLedgerRepository runRepository;
    private final LedgerProperties ledgerProperties;
    private final LedgerBootstrap bootstrap;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final ObjectProvider<ContainerGateway> gatewayProvider;

    public InstanceConfigService(InstanceConfigProperties properties,
                                 InstanceConfigValidator validator,
                                 InstanceConfigRepository repository,
                                 RunLedgerRepository runRepository,
                                 LedgerProperties ledgerProperties,
                                 LedgerBootstrap bootstrap,
                                 ObjectMapper objectMapper,
                                 Clock clock,
                                 ObjectProvider<ContainerGateway> gatewayProvider) {
        this.properties = properties;
        this.validator = validator;
        this.repository = repository;
        this.runRepository = runRepository;
        this.ledgerProperties = ledgerProperties;
        this.bootstrap = bootstrap;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.gatewayProvider = gatewayProvider;
    }

    /** Dry-run: validates and generates argv without touching the ledger. */
    public InstanceViews.InstanceValidationView validate(String instance, InstanceConfig request) {
        InstanceConfigValidator.ValidationResult result = validator.validate(instance, request);
        return new InstanceViews.InstanceValidationView(instance, true, result.config(),
                result.jvmArgs(), result.warnings(), result.limitedObservability(),
                result.requiredMemoryMb(), result.memoryLimitMb());
    }

    /** Stored draft + regenerated argv + persisted status. */
    public InstanceViews.InstanceConfigView get(String instance) {
        requireKnown(instance);
        requireReady();
        StoredInstanceConfig stored = find(instance).orElse(null);
        if (stored == null) {
            return new InstanceViews.InstanceConfigView(instance, null, STATUS_NOT_CONFIGURED, 0,
                    List.of(), List.of("no configuration saved for this instance yet"), false,
                    0, properties.getMemoryLimitMb(), null, null, null);
        }
        return toView(instance, stored);
    }

    /**
     * Validates, checks the run lock for startup fields, then stores DRAFT.
     * {@code previous_config} is kept by the repository and {@code revision}
     * increments; the answer is the stored row, not the raw request.
     */
    public InstanceViews.InstanceConfigView save(String instance, InstanceConfig request) {
        requireKnown(instance);
        InstanceConfigValidator.ValidationResult result = validator.validate(instance, request);
        requireReady();

        StoredInstanceConfig stored = find(instance).orElse(null);
        InstanceConfig previous = stored == null ? null : parse(stored);
        if (InstanceConfigValidator.startupFieldsChanged(result.config(), previous)) {
            List<RunRecord> running;
            try {
                running = runRepository.findRunsByStatuses(List.of(RunStatus.RUNNING));
            } catch (DataAccessException e) {
                throw LedgerReadiness.unavailable(bootstrap, e, "instance config run-lock check");
            }
            if (!running.isEmpty()) {
                throw new InstanceConfigConflictException("run " + running.get(0).runId()
                        + " is RUNNING; JVM/GC/GC-log startup fields cannot change during a run "
                        + "(stop the run first; pool/Caffeine/Tomcat fields are not affected)");
            }
        }

        String json;
        try {
            json = objectMapper.writeValueAsString(result.config());
        } catch (JsonProcessingException e) {
            throw new InvalidInstanceConfigException(
                    "config could not be serialized: " + e.getOriginalMessage());
        }
        StoredInstanceConfig saved;
        try {
            saved = repository.upsertDraft(instance, json, clock.instant());
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance config save");
        }
        return new InstanceViews.InstanceConfigView(instance, result.config(),
                saved.status().name(), saved.revision(), result.jvmArgs(), result.warnings(),
                result.limitedObservability(), result.requiredMemoryMb(), result.memoryLimitMb(),
                text(saved.updatedAt()), text(saved.appliedAt()), saved.lastError());
    }

    /**
     * Readback of the running container: image tag/id plus only
     * {@code JAVA_TOOL_OPTIONS} and {@code INSTANCE_ID}. No value is invented;
     * missing Docker/container/variable stays null and the status says so.
     */
    public InstanceViews.EffectiveInstanceView effective(String instance) {
        requireKnown(instance);
        requireReady();
        StoredInstanceConfig stored = find(instance).orElse(null);
        InstanceConfig config = stored == null ? null : parse(stored);
        Integer jdk = config == null ? null : config.jdkVersion();
        String container = properties.containerName(instance);

        ContainerGateway gateway;
        try {
            gateway = gatewayProvider.getIfAvailable();
        } catch (RuntimeException e) {
            gateway = null;
        }
        if (gateway == null) {
            return unavailable(instance, container, jdk,
                    "Docker gateway is not available (lab.docker.enabled=false or no DockerClient)");
        }

        Optional<ContainerFacts> inspected;
        try {
            inspected = gateway.inspectLabContainerFacts(container);
        } catch (RuntimeException e) {
            return unavailable(instance, container, jdk,
                    "container facts unavailable for " + container + " (" + e.getClass().getSimpleName()
                            + "); start the app instance or check GET /lab/containers");
        }
        if (inspected == null || inspected.isEmpty()) {
            return unavailable(instance, container, jdk,
                    "container facts unavailable for " + container);
        }
        ContainerFacts facts = inspected.get();
        String observability = jdk == null ? OBSERVABILITY_UNKNOWN
                : jdk == 8 ? OBSERVABILITY_LIMITED : OBSERVABILITY_FULL;
        String readback = jdk != null && jdk == 8 ? READBACK_LIMITED : READBACK_OK;
        List<String> notes = jdk == null
                ? List.of("no stored jdkVersion; observability class is unknown")
                : jdk == 8
                ? List.of("JDK 8 shell: app-level hot readback is READBACK_UNAVAILABLE")
                : List.of();
        return new InstanceViews.EffectiveInstanceView(instance, facts.name(), facts.image(),
                facts.imageId(), facts.env().get(ENV_JAVA_TOOL_OPTIONS),
                facts.env().get(ENV_INSTANCE_ID), readback, observability, notes);
    }

    private InstanceViews.EffectiveInstanceView unavailable(String instance, String container,
                                                            Integer jdk, String note) {
        String observability = jdk == null ? OBSERVABILITY_UNKNOWN
                : jdk == 8 ? OBSERVABILITY_LIMITED : OBSERVABILITY_FULL;
        return new InstanceViews.EffectiveInstanceView(instance, container, null, null, null, null,
                READBACK_UNAVAILABLE, observability, List.of(note));
    }

    private InstanceViews.InstanceConfigView toView(String instance, StoredInstanceConfig stored) {
        InstanceConfig config;
        try {
            config = objectMapper.readValue(stored.configJson(), InstanceConfig.class);
        } catch (JsonProcessingException e) {
            throw new LedgerUnavailableException("stored instance config for " + instance
                    + " is unreadable: " + e.getOriginalMessage());
        }
        try {
            InstanceConfigValidator.ValidationResult result = validator.validate(instance, config);
            return new InstanceViews.InstanceConfigView(instance, result.config(),
                    stored.status().name(), stored.revision(), result.jvmArgs(),
                    result.warnings(), result.limitedObservability(), result.requiredMemoryMb(),
                    result.memoryLimitMb(), text(stored.updatedAt()), text(stored.appliedAt()),
                    stored.lastError());
        } catch (InvalidInstanceConfigException e) {
            // A deploy-time limit change must not make GET fail: show the stored
            // config with the current rejection as a warning.
            return new InstanceViews.InstanceConfigView(instance, config, stored.status().name(),
                    stored.revision(), List.of(), List.of("stored config no longer validates: "
                    + e.getMessage()), config.jdkVersion() != null && config.jdkVersion() == 8,
                    0, properties.getMemoryLimitMb(), text(stored.updatedAt()),
                    text(stored.appliedAt()), stored.lastError());
        }
    }

    private InstanceConfig parse(StoredInstanceConfig stored) {
        try {
            return objectMapper.readValue(stored.configJson(), InstanceConfig.class);
        } catch (JsonProcessingException e) {
            throw new LedgerUnavailableException("stored instance config for " + stored.instance()
                    + " is unreadable: " + e.getOriginalMessage());
        }
    }

    private Optional<StoredInstanceConfig> find(String instance) {
        try {
            return repository.find(instance);
        } catch (DataAccessException e) {
            throw LedgerReadiness.unavailable(bootstrap, e, "instance config read");
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

    private static String text(Instant instant) {
        return instant == null ? null : instant.toString();
    }
}
