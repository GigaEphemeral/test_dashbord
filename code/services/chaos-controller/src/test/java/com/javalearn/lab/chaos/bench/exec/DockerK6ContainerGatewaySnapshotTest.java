package com.javalearn.lab.chaos.bench.exec;

import com.github.dockerjava.api.command.InspectContainerResponse;
import com.github.dockerjava.api.model.ContainerConfig;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Stage2 B2-03a: the inspect projection must surface the traffic attribution
 * labels so {@code GET /lab/runs/{id}/k6} can report them. No Docker daemon:
 * {@code toSnapshot} is driven with mocked docker-java responses.
 */
class DockerK6ContainerGatewaySnapshotTest {

    @Test
    void snapshotCarriesTrafficModeAndTargetInstanceLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("lab", "java-learn");
        labels.put("lab.role", "k6");
        labels.put("lab.scenario", "load");
        labels.put(K6ContainerGateway.LABEL_TRAFFIC_MODE, "target-instance");
        labels.put(K6ContainerGateway.LABEL_TARGET_INSTANCE, "app-2");

        K6ContainerGateway.Snapshot snapshot = DockerK6ContainerGateway.toSnapshot(
                inspected(labels), "jl-k6-run-1");

        assertThat(snapshot.status()).isEqualTo("RUNNING");
        assertThat(snapshot.scenario()).isEqualTo("load");
        assertThat(snapshot.trafficMode()).isEqualTo("target-instance");
        assertThat(snapshot.targetInstance()).isEqualTo("app-2");
    }

    @Test
    void aPreAttributionContainerHasNullTrafficLabels() {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("lab", "java-learn");
        labels.put("lab.role", "k6");
        labels.put("lab.scenario", "smoke");

        K6ContainerGateway.Snapshot snapshot = DockerK6ContainerGateway.toSnapshot(
                inspected(labels), "jl-k6-run-1");

        assertThat(snapshot.scenario()).isEqualTo("smoke");
        assertThat(snapshot.trafficMode()).isNull();
        assertThat(snapshot.targetInstance()).isNull();
    }

    private static InspectContainerResponse inspected(Map<String, String> labels) {
        InspectContainerResponse.ContainerState state = mock(
                InspectContainerResponse.ContainerState.class);
        when(state.getStatus()).thenReturn("running");
        when(state.getExitCodeLong()).thenReturn(0L);
        when(state.getStartedAt()).thenReturn("2026-10-28T10:00:00Z");
        when(state.getFinishedAt()).thenReturn("2026-10-28T10:05:00Z");

        ContainerConfig config = mock(ContainerConfig.class);
        when(config.getImage()).thenReturn("grafana/k6:1.3.0");
        when(config.getLabels()).thenReturn(labels);

        InspectContainerResponse inspected = mock(InspectContainerResponse.class);
        when(inspected.getState()).thenReturn(state);
        when(inspected.getConfig()).thenReturn(config);
        when(inspected.getId()).thenReturn("cid-1");
        when(inspected.getName()).thenReturn("/jl-k6-run-1");
        return inspected;
    }
}
