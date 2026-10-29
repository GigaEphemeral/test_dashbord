package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.ledger.RunLedgerRepository;
import com.javalearn.lab.chaos.ledger.RunRecord;
import com.javalearn.lab.chaos.ledger.RunStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RunEnvCaptureHookTest {

    private static final String RUN = "run-1";
    private static final Instant NOW = Instant.parse("2026-10-30T10:00:00Z");

    private InstanceConfigService configs;
    private InstanceConfigRepository storedConfigs;
    private RunLedgerRepository ledger;
    private RunEnvCaptureHook hook;

    @BeforeEach
    void setUp() {
        configs = mock(InstanceConfigService.class);
        storedConfigs = mock(InstanceConfigRepository.class);
        ledger = mock(RunLedgerRepository.class);
        InstanceConfigProperties properties = new InstanceConfigProperties();
        hook = new RunEnvCaptureHook(configs, properties, storedConfigs, ledger,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
    }

    private static RunRecord run(String nodes) {
        return new RunRecord(RUN, "load", "n", nodes, RunStatus.RUNNING, "abc", "tester",
                NOW.minusSeconds(30), NOW, NOW.minusSeconds(20), null, false);
    }

    private static InstanceViews.EffectiveInstanceView effective(String instance, String container) {
        return new InstanceViews.EffectiveInstanceView(instance, container,
                "java-learn-lab/seckill-app:jdk21", "sha256:abc", "-Xms256m -Xmx512m -XX:+UseG1GC",
                instance, "OK", "FULL-OBSERVABILITY", List.of(),
                List.of("-XX:+UseG1GC", "-Xmx512m"), "OK");
    }

    @Test
    void capturesTheRunningIdentityForEveryNode() {
        when(ledger.findRun(RUN)).thenReturn(Optional.of(run("app-1,app-2")));
        when(configs.effective("app-1")).thenReturn(effective("app-1", "jl-app-1"));
        when(configs.effective("app-2")).thenReturn(effective("app-2", "jl-app-2"));
        when(storedConfigs.find("app-1")).thenReturn(Optional.of(new StoredInstanceConfig("app-1",
                "{\"jdkVersion\":21,\"gcCollector\":\"G1\",\"maxGcPauseMillis\":200}",
                null, InstanceConfigStatus.APPLIED, 1, NOW, NOW, null)));
        when(storedConfigs.find("app-2")).thenReturn(Optional.empty());

        hook.onStarted(RUN);

        ArgumentCaptor<String> jvm = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> gc = ArgumentCaptor.forClass(String.class);
        verify(ledger).insertEnv(eq(RUN), eq("jl-app-1"),
                eq("java-learn-lab/seckill-app:jdk21"), eq("sha256:abc"), eq("21"),
                jvm.capture(), gc.capture(), eq(NOW));
        assertThat(jvm.getValue()).contains("-XX:+UseG1GC").contains("javaToolOptions");
        assertThat(gc.getValue()).contains("\"collector\":\"G1\"").contains("maxGcPauseMillis");
        // app-2 has no stored config: the identity is still captured, GC is null.
        verify(ledger).insertEnv(eq(RUN), eq("jl-app-2"),
                eq("java-learn-lab/seckill-app:jdk21"), eq("sha256:abc"), eq(null),
                anyString(), eq(null), eq(NOW));
    }

    @Test
    void aMissingContainerIsSkippedAndNeverFailsTheRun() {
        when(ledger.findRun(RUN)).thenReturn(Optional.of(run("app-1,app-2")));
        when(configs.effective("app-1")).thenReturn(effective("app-1", "jl-app-1"));
        when(configs.effective("app-2")).thenThrow(new IllegalStateException("daemon down"));
        when(storedConfigs.find("app-1")).thenReturn(Optional.empty());

        assertThatCode(() -> hook.onStarted(RUN)).doesNotThrowAnyException();

        verify(ledger).insertEnv(eq(RUN), eq("jl-app-1"), any(), any(), any(), any(), any(), any());
        verify(ledger, never()).insertEnv(eq(RUN), eq("jl-app-2"), any(), any(), any(), any(), any(), any());
    }

    @Test
    void aMissingRunIsIgnored() {
        when(ledger.findRun(RUN)).thenReturn(Optional.empty());

        assertThatCode(() -> hook.onStarted(RUN)).doesNotThrowAnyException();
        verify(ledger, never()).insertEnv(any(), any(), any(), any(), any(), any(), any(), any());
    }
}
