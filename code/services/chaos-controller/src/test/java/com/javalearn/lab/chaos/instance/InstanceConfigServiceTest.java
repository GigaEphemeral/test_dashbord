package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerFacts;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import com.javalearn.lab.chaos.model.ExecResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** B4-01 service behaviour without Docker or MySQL. */
class InstanceConfigServiceTest {

    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");
    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstanceConfigProperties properties;
    private InMemoryRepository repository;
    private RunLedgerRepository runRepository;
    private LedgerBootstrap bootstrap;
    private ObjectProvider<ContainerGateway> gatewayProvider;
    private InstanceConfigService service;

    @BeforeEach
    @SuppressWarnings("unchecked")
    void setUp() {
        properties = new InstanceConfigProperties();
        properties.validate();
        repository = new InMemoryRepository();
        runRepository = mock(RunLedgerRepository.class);
        when(runRepository.findRunsByStatuses(any())).thenReturn(List.of());
        bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "4", null, NOW));
        gatewayProvider = mock(ObjectProvider.class);
        when(gatewayProvider.getIfAvailable()).thenReturn(null);
        service = new InstanceConfigService(properties, new InstanceConfigValidator(properties),
                repository, runRepository, new LedgerProperties(), bootstrap, MAPPER,
                Clock.fixed(NOW, ZoneOffset.UTC), gatewayProvider);
    }

    @Test
    void saveKeepsPreviousConfigAndIncrementsRevision() throws Exception {
        InstanceViews.InstanceConfigView first = service.save("app-1", config("""
                {"jdkVersion":21,"xmxMb":256,"corePoolSize":16}
                """));
        assertThat(first.status()).isEqualTo("DRAFT");
        assertThat(first.revision()).isEqualTo(1);
        String firstJson = repository.rows.get("app-1").configJson();

        InstanceViews.InstanceConfigView second = service.save("app-1", config("""
                {"jdkVersion":21,"xmxMb":256,"corePoolSize":32}
                """));
        assertThat(second.revision()).isEqualTo(2);
        assertThat(second.status()).isEqualTo("DRAFT");
        assertThat(repository.rows.get("app-1").previousConfigJson()).isEqualTo(firstJson);
        assertThat(second.jvmArgs()).contains("-Xmx256m");
    }

    @Test
    void saveRefusesStartupChangesWhileARunIsRunningButAllowsPoolOnlyChanges() throws Exception {
        service.save("app-1", config("{\"jdkVersion\":21,\"xmxMb\":256,\"corePoolSize\":16}"));
        when(runRepository.findRunsByStatuses(any())).thenReturn(List.of(run("run-20261030-001")));

        assertThatThrownBy(() -> service.save("app-1",
                config("{\"jdkVersion\":21,\"xmxMb\":384,\"corePoolSize\":16}")))
                .isInstanceOf(InstanceConfigConflictException.class)
                .hasMessageContaining("run-20261030-001")
                .hasMessageContaining("RUNNING");

        InstanceViews.InstanceConfigView saved = service.save("app-1",
                config("{\"jdkVersion\":21,\"xmxMb\":256,\"corePoolSize\":32}"));
        assertThat(saved.revision()).isEqualTo(2);
    }

    @Test
    void getWithoutARowReportsNotConfiguredInsteadOfFailing() {
        InstanceViews.InstanceConfigView view = service.get("app-1");
        assertThat(view.status()).isEqualTo(InstanceConfigService.STATUS_NOT_CONFIGURED);
        assertThat(view.revision()).isZero();
        assertThat(view.config()).isNull();
    }

    @Test
    void effectiveIsUnavailableWithoutADockerGatewayAndInventsNoValues() {
        InstanceViews.EffectiveInstanceView view = service.effective("app-1");
        assertThat(view.readback()).isEqualTo(InstanceConfigService.READBACK_UNAVAILABLE);
        assertThat(view.jvmReadback()).isEqualTo(InstanceConfigService.READBACK_UNAVAILABLE);
        assertThat(view.jvmFlags()).isNull();
        assertThat(view.containerName()).isEqualTo("jl-app-1");
        assertThat(view.javaToolOptions()).isNull();
        assertThat(view.instanceId()).isNull();
        assertThat(view.imageTag()).isNull();
        assertThat(view.notes()).isNotEmpty();
    }

    @Test
    void jdk8EffectiveReadbackIsLimitedAndCopiesOnlyContainerEnvironment() throws Exception {
        service.save("app-1", config("{\"jdkVersion\":8,\"gcCollector\":\"G1\"}"));
        ContainerGateway gateway = mock(ContainerGateway.class);
        when(gateway.inspectLabContainerFacts("jl-app-1")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-1", "seckill-jdk8:local", "sha256:abc",
                        Map.of("JAVA_TOOL_OPTIONS", "-Xmx256m", "INSTANCE_ID", "app-1",
                                "MYSQL_PASSWORD", "must-not-leak"))));
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);

        InstanceViews.EffectiveInstanceView view = service.effective("app-1");
        assertThat(view.readback()).isEqualTo(InstanceConfigService.READBACK_LIMITED);
        assertThat(view.jvmReadback()).isEqualTo(InstanceConfigService.JVM_READBACK_READBACK_UNAVAILABLE);
        assertThat(view.jvmFlags()).isNull();
        assertThat(view.observability()).isEqualTo(InstanceConfigService.OBSERVABILITY_LIMITED);
        assertThat(view.javaToolOptions()).isEqualTo("-Xmx256m");
        assertThat(view.instanceId()).isEqualTo("app-1");
        assertThat(view.imageTag()).isEqualTo("seckill-jdk8:local");
        assertThat(view.imageId()).isEqualTo("sha256:abc");
        // B5-04b: JDK 8 never runs jcmd VM.flags.
        verify(gateway, never()).execInContainer(any(), any(), any(), any(), anyInt());
        // Secrets in the container env are never surfaced through this view.
        assertThat(view.toString()).doesNotContain("MYSQL_PASSWORD").doesNotContain("must-not-leak");
    }

    @Test
    void jdk21EffectiveReadbackIsOkAndDockerFailureFallsBackToUnavailable() throws Exception {
        service.save("app-1", config("{\"jdkVersion\":21}"));
        ContainerGateway gateway = mock(ContainerGateway.class);
        when(gateway.inspectLabContainerFacts("jl-app-1")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-1", "jdk21", "sha256:def", Map.of())));
        when(gateway.execInContainer(eq("jl-app-1"),
                eq(List.of("jcmd", "1", "VM.flags")), eq(List.of()), isNull(), eq(10)))
                .thenReturn(new ExecResult(
                        "1:\n-XX:+UseG1GC -Xmx512m -XX:MaxHeapSize=536870912 \n",
                        "Picked up JAVA_TOOL_OPTIONS\n", 0, false));
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        InstanceViews.EffectiveInstanceView ok = service.effective("app-1");
        assertThat(ok.readback()).isEqualTo(InstanceConfigService.READBACK_OK);
        assertThat(ok.jvmReadback()).isEqualTo(InstanceConfigService.JVM_READBACK_OK);
        assertThat(ok.jvmFlags()).containsExactly(
                "-XX:+UseG1GC", "-Xmx512m", "-XX:MaxHeapSize=536870912");
        assertThat(ok.notes()).isEmpty();

        when(gateway.inspectLabContainerFacts("jl-app-1"))
                .thenThrow(new RuntimeException("daemon down"));
        InstanceViews.EffectiveInstanceView view = service.effective("app-1");
        assertThat(view.readback()).isEqualTo(InstanceConfigService.READBACK_UNAVAILABLE);
        assertThat(view.jvmReadback()).isEqualTo(InstanceConfigService.READBACK_UNAVAILABLE);
        assertThat(view.imageTag()).isNull();
    }

    @Test
    void jvmFlagsReadbackFailsHonestlyWhenJcmdFailsOrTimesOut() throws Exception {
        service.save("app-1", config("{\"jdkVersion\":21}"));
        ContainerGateway gateway = mock(ContainerGateway.class);
        when(gateway.inspectLabContainerFacts("jl-app-1")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-1", "jdk21", "sha256:def", Map.of())));
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);

        when(gateway.execInContainer(any(), any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult("", "jcmd: not found", 127, false));
        InstanceViews.EffectiveInstanceView failed = service.effective("app-1");
        assertThat(failed.jvmReadback()).isEqualTo(InstanceConfigService.JVM_READBACK_READBACK_UNAVAILABLE);
        assertThat(failed.jvmFlags()).isNull();
        assertThat(failed.notes()).anyMatch(note -> note.contains("jcmd VM.flags failed"));

        when(gateway.execInContainer(any(), any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult("", "", null, true));
        InstanceViews.EffectiveInstanceView timedOut = service.effective("app-1");
        assertThat(timedOut.jvmReadback()).isEqualTo(InstanceConfigService.JVM_READBACK_READBACK_UNAVAILABLE);
        assertThat(timedOut.jvmFlags()).isNull();
        assertThat(timedOut.notes()).anyMatch(note -> note.contains("timed out"));
    }

    @Test
    void jvmFlagsOutputIsBoundedBeforeParsing() throws Exception {
        properties.setJvmFlagsMaxBytes(1024);
        service.save("app-1", config("{\"jdkVersion\":21}"));
        ContainerGateway gateway = mock(ContainerGateway.class);
        when(gateway.inspectLabContainerFacts("jl-app-1")).thenReturn(Optional.of(
                new ContainerFacts("jl-app-1", "jdk21", "sha256:def", Map.of())));
        when(gatewayProvider.getIfAvailable()).thenReturn(gateway);
        String longFlags = "-XX:+UseG1GC " + "-XX:FlagPadding=1 ".repeat(200);
        when(gateway.execInContainer(any(), any(), any(), any(), anyInt()))
                .thenReturn(new ExecResult("1:\n" + longFlags, "", 0, false));

        InstanceViews.EffectiveInstanceView view = service.effective("app-1");

        assertThat(view.jvmReadback()).isEqualTo(InstanceConfigService.JVM_READBACK_OK);
        assertThat(view.jvmFlags()).isNotEmpty();
        assertThat(view.notes()).anyMatch(note -> note.contains("truncated"));
    }

    @Test
    void degradedLedgerAnswersLedgerUnavailable() {
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.DEGRADED, "4", "mysql down", NOW));
        assertThatThrownBy(() -> service.get("app-1"))
                .isInstanceOf(LedgerUnavailableException.class)
                .hasMessageContaining("DEGRADED");
    }

    private static RunRecord run(String runId) {
        return new RunRecord(runId, "spike", RunStatus.RUNNING, "commit", "local",
                NOW, NOW, NOW, null);
    }

    private static InstanceConfig config(String json) throws Exception {
        return MAPPER.readValue(json, InstanceConfig.class);
    }

    /** Minimal in-memory stand-in: owns the revision/previous-config contract. */
    private static final class InMemoryRepository extends InstanceConfigRepository {

        private final Map<String, StoredInstanceConfig> rows = new HashMap<>();

        private InMemoryRepository() {
            super(null);
        }

        @Override
        public Optional<StoredInstanceConfig> find(String instance) {
            return Optional.ofNullable(rows.get(instance));
        }

        @Override
        public List<StoredInstanceConfig> list() {
            return List.copyOf(rows.values());
        }

        @Override
        public StoredInstanceConfig upsertDraft(String instance, String configJson, Instant now) {
            StoredInstanceConfig previous = rows.get(instance);
            StoredInstanceConfig saved = new StoredInstanceConfig(instance, configJson,
                    previous == null ? null : previous.configJson(), InstanceConfigStatus.DRAFT,
                    previous == null ? 1 : previous.revision() + 1, now, null, null);
            rows.put(instance, saved);
            return saved;
        }

        @Override
        public int markApplied(String instance, Instant appliedAt) {
            StoredInstanceConfig row = rows.get(instance);
            if (row == null) {
                return 0;
            }
            rows.put(instance, new StoredInstanceConfig(instance, row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.APPLIED, row.revision(),
                    appliedAt, appliedAt, null));
            return 1;
        }

        @Override
        public int markFailed(String instance, String error, Instant now) {
            StoredInstanceConfig row = rows.get(instance);
            if (row == null) {
                return 0;
            }
            rows.put(instance, new StoredInstanceConfig(instance, row.configJson(),
                    row.previousConfigJson(), InstanceConfigStatus.FAILED, row.revision(),
                    now, null, error));
            return 1;
        }
    }
}
