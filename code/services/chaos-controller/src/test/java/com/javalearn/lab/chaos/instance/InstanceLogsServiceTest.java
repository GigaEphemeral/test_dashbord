package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.ledger.LedgerBootstrap;
import com.javalearn.lab.chaos.ledger.LedgerProperties;
import com.javalearn.lab.chaos.model.ExecResult;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** B4-03 GC-log readback: disabled/no-config/OK/NO_DATA/path and bounds. */
class InstanceLogsServiceTest {

    private InstanceConfigRepository repository;
    private ContainerGateway gateway;
    private InstanceLogsService service;
    private InstanceConfigProperties properties;

    @BeforeEach
    void setUp() {
        properties = new InstanceConfigProperties();
        properties.setNames(java.util.List.of("app-1", "app-2", "app-3"));
        repository = mock(InstanceConfigRepository.class);
        gateway = mock(ContainerGateway.class);
        ObjectProvider<ContainerGateway> provider = mock(ObjectProvider.class);
        when(provider.getIfAvailable()).thenReturn(gateway);
        LedgerProperties ledger = new LedgerProperties();
        LedgerBootstrap bootstrap = mock(LedgerBootstrap.class);
        when(bootstrap.status()).thenReturn(new LedgerBootstrap.BootstrapStatus(
                LedgerBootstrap.State.OK, "5", null, Instant.now()));
        service = new InstanceLogsService(properties, repository, provider, ledger, bootstrap,
                new ObjectMapper());
    }

    private void store(String json) {
        when(repository.find("app-1")).thenReturn(Optional.of(new StoredInstanceConfig(
                "app-1", json, null, InstanceConfigStatus.APPLIED, 1, Instant.now(), Instant.now(),
                null)));
    }

    @Test
    void disabledWhenGcLoggingIsOff() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":false}");

        InstanceLogsView view = service.gcLog("app-1", null);

        assertThat(view.status()).isEqualTo(InstanceLogsService.DISABLED);
    }

    @Test
    void noConfigWhenNothingWasSaved() {
        when(repository.find("app-1")).thenReturn(Optional.empty());

        assertThat(service.gcLog("app-1", null).status()).isEqualTo(InstanceLogsService.NO_CONFIG);
    }

    @Test
    void returnsTheTailAndFlagsTruncation() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-1.log\"}");
        properties.setGcLogMaxBytes(1024);
        String text = "gc line\n".repeat(500);
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(new ExecResult(text, "", 0, false));

        InstanceLogsView view = service.gcLog("app-1", 50);

        assertThat(view.status()).isEqualTo(InstanceLogsService.OK);
        assertThat(view.path()).isEqualTo("/app/lab-snapshots/gc-app-1.log");
        assertThat(view.truncated()).isTrue();
        assertThat(view.content()).endsWith("gc line\n");
    }

    @Test
    void missingFileIsNoDataNotAnError() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-1.log\"}");
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(new ExecResult("", "tail: cannot open", 1, false));

        InstanceLogsView view = service.gcLog("app-1", 20);

        assertThat(view.status()).isEqualTo(InstanceLogsService.NO_DATA);
        assertThat(view.note()).contains("cannot open");
    }

    @Test
    void rejectsAStoredPathOutsideTheRootAndBadLineBounds() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,\"gcLogPath\":\"/etc/passwd\"}");

        assertThatThrownBy(() -> service.gcLog("app-1", null))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("outside");

        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-1.log\"}");
        assertThatThrownBy(() -> service.gcLog("app-1", properties.getGcLogMaxLines() + 1))
                .isInstanceOf(InvalidInstanceConfigException.class)
                .hasMessageContaining("lines");
    }

    @Test
    void fallsBackToTheJdk8RotationCurrentFile() {
        store("{\"jdkVersion\":8,\"gcLogEnabled\":true,\"gcLogFileCount\":3,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-jdk8.log\"}");
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenAnswer(invocation -> {
                    java.util.List<?> command = invocation.getArgument(1);
                    String target = String.valueOf(command.get(command.size() - 1));
                    if (target.equals("/app/lab-snapshots/gc-app-jdk8.log.0.current")) {
                        return new ExecResult("GC pause (G1 Evacuation Pause)", "", 0, false);
                    }
                    return new ExecResult("", "tail: cannot open '" + target + "'", 1, false);
                });

        InstanceLogsView view = service.gcLog("app-1", 20);

        assertThat(view.status()).isEqualTo(InstanceLogsService.OK);
        assertThat(view.path()).isEqualTo("/app/lab-snapshots/gc-app-jdk8.log.0.current");
        assertThat(view.content()).contains("GC pause");
    }

    @Test
    void missingRotationFilesStayNoDataAndNeverFakeContent() {
        store("{\"jdkVersion\":8,\"gcLogEnabled\":true,\"gcLogFileCount\":2,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-jdk8.log\"}");
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(new ExecResult("", "tail: cannot open", 1, false));

        InstanceLogsView view = service.gcLog("app-1", 20);

        assertThat(view.status()).isEqualTo(InstanceLogsService.NO_DATA);
        assertThat(view.content()).isNull();
    }

    @Test
    void usesTheConfiguredTailTimeout() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-1.log\"}");
        properties.setGcLogTailTimeoutSeconds(7);
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(new ExecResult("one line\n", "", 0, false));

        service.gcLog("app-1", 10);

        org.mockito.ArgumentCaptor<Integer> timeout =
                org.mockito.ArgumentCaptor.forClass(Integer.class);
        org.mockito.Mockito.verify(gateway).execInContainer(anyString(), anyList(), anyList(),
                any(), timeout.capture());
        assertThat(timeout.getValue()).isEqualTo(7);
    }

    @Test
    void byteCapKeepsATailWithinTheBudgetForMultibyteContent() {
        store("{\"jdkVersion\":21,\"gcLogEnabled\":true,"
                + "\"gcLogPath\":\"/app/lab-snapshots/gc-app-1.log\"}");
        properties.setGcLogMaxBytes(32);
        String text = "字".repeat(40); // 120 UTF-8 bytes
        when(gateway.execInContainer(anyString(), anyList(), anyList(), any(), anyInt()))
                .thenReturn(new ExecResult(text, "", 0, false));

        InstanceLogsView view = service.gcLog("app-1", 50);

        assertThat(view.status()).isEqualTo(InstanceLogsService.OK);
        assertThat(view.truncated()).isTrue();
        assertThat(view.content().getBytes(java.nio.charset.StandardCharsets.UTF_8).length)
                .isLessThanOrEqualTo(32);
        // A split multi-byte lead must not leak a replacement character.
        assertThat(view.content()).doesNotContain("\uFFFD");
    }
}
