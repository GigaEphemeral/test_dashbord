package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.traffic.TrafficService;
import com.javalearn.lab.chaos.traffic.TrafficStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class TrafficServiceTest {

    private static final String TRAFFIC = "jl-traffic";

    private ContainerGateway gateway;
    private ChaosProperties properties;
    private TrafficService service;

    @BeforeEach
    void setUp() {
        gateway = mock(ContainerGateway.class);
        properties = new ChaosProperties();
        properties.getTraffic().setContainerName(TRAFFIC);
        properties.getTraffic().setStopGraceSeconds(7);
        service = new TrafficService(gateway, properties);
    }

    @Test
    void stateReportsTheContainerFromTheGateway() {
        when(gateway.inspectLabContainer(TRAFFIC)).thenReturn(new ContainerView(TRAFFIC, "running", "none"));

        TrafficStateView state = service.state();

        assertThat(state.container()).isEqualTo(TRAFFIC);
        assertThat(state.state()).isEqualTo("running");
        assertThat(state.running()).isTrue();
    }

    @Test
    void missingTrafficProfileContainerIsReportedAsMissingNotAsAnError() {
        when(gateway.inspectLabContainer(TRAFFIC)).thenThrow(new ContainerNotFoundException("nope"));

        TrafficStateView state = service.state();

        assertThat(state.state()).isEqualTo(TrafficStateView.MISSING);
        assertThat(state.running()).isFalse();
    }

    @Test
    void startUsesTheGatewayDirectlySoNoTtlRevertIsRegistered() {
        when(gateway.inspectLabContainer(TRAFFIC)).thenReturn(new ContainerView(TRAFFIC, "created", "none"));

        TrafficStateView started = service.start();

        verify(gateway).start(TRAFFIC);
        assertThat(started.state()).isEqualTo("created");
        assertThat(started.running()).isFalse();
    }

    @Test
    void stopAppliesTheExternalizedGracePeriod() {
        when(gateway.inspectLabContainer(TRAFFIC)).thenReturn(new ContainerView(TRAFFIC, "exited", "none"));

        TrafficStateView stopped = service.stop();

        verify(gateway).stop(TRAFFIC, 7);
        assertThat(stopped.running()).isFalse();
    }

    @Test
    void startWithoutTheTrafficContainerExplainsHowToInstallIt() {
        doThrow(new ContainerNotFoundException("Container not found: jl-traffic"))
                .when(gateway).start(TRAFFIC);

        assertThatThrownBy(service::start)
                .isInstanceOf(ContainerNotFoundException.class)
                .hasMessageContaining("traffic profile");
        verify(gateway, never()).stop(TRAFFIC, 7);
    }
}
