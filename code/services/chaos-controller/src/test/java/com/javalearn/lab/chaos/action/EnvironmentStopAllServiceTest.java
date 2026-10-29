package com.javalearn.lab.chaos.action;

import com.javalearn.lab.chaos.appproxy.AppProxyService;
import com.javalearn.lab.chaos.appproxy.ProxyResponse;
import com.javalearn.lab.chaos.bench.exec.BenchRunExecutor;
import com.javalearn.lab.chaos.bench.exec.K6ContainerGateway;
import com.javalearn.lab.chaos.config.ChaosProperties;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.schedule.FaultScheduler;
import com.javalearn.lab.chaos.traffic.TrafficService;
import com.javalearn.lab.chaos.traffic.TrafficStateView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * B2-04b1 orchestration: container/toxiproxy rollback first, then traffic,
 * thread lab and k6. Every sub-step is best-effort; failures are reported in
 * {@link StopAllResult}, never thrown.
 */
class EnvironmentStopAllServiceTest {

    private static final NetworkResetReport NETWORK =
            new NetworkResetReport(NetworkResetReport.STATUS_RESET, 2, 2, List.of());

    private ChaosActionService chaosActionService;
    private TrafficService trafficService;
    private AppProxyService appProxyService;
    private BenchRunExecutor benchRunExecutor;
    private EnvironmentStopAllService service;

    @BeforeEach
    void setUp() {
        chaosActionService = mock(ChaosActionService.class);
        trafficService = mock(TrafficService.class);
        appProxyService = mock(AppProxyService.class);
        benchRunExecutor = mock(BenchRunExecutor.class);

        ChaosProperties properties = new ChaosProperties();
        properties.getAppProxy().setInstances(List.of("app-1", "app-2", "app-3"));
        service = new EnvironmentStopAllService(chaosActionService, trafficService,
                appProxyService, benchRunExecutor, properties);

        when(chaosActionService.stopAll()).thenReturn(new StopAllResult(2, 1, 1, NETWORK));
        when(trafficService.stop())
                .thenReturn(new TrafficStateView("jl-traffic", "exited", "none", false));
        when(appProxyService.forward(anyString(), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(200, "{\"code\":\"0\"}"));
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of());
    }

    @Test
    void everySubStepSucceedsAndTheContainerCountsArePreserved() {
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of(
                new K6ContainerGateway.ContainerSummary("jl-k6-run-a", "cid-a", "run-a",
                        K6ContainerGateway.STATUS_RUNNING),
                new K6ContainerGateway.ContainerSummary("jl-k6-run-b", "cid-b", "run-b",
                        K6ContainerGateway.STATUS_EXITED)));
        when(benchRunExecutor.stop("run-a")).thenReturn(true);
        when(benchRunExecutor.cleanup("run-a")).thenReturn(true);
        when(benchRunExecutor.cleanup("run-b")).thenReturn(true);

        StopAllResult result = service.stopAll();

        assertThat(result.reverted()).isEqualTo(2);
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.pending()).isEqualTo(1);
        assertThat(result.toxiproxy()).isEqualTo(NETWORK);
        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_STOPPED);
        assertThat(result.threads()).containsExactlyInAnyOrderEntriesOf(
                Map.of("app-1", "ok", "app-2", "ok", "app-3", "ok"));
        assertThat(result.k6Stopped()).isEqualTo(1);
        assertThat(result.k6Cleaned()).isEqualTo(2);
        assertThat(result.k6Failed()).isZero();

        verify(benchRunExecutor).sweepOrphans();
        verify(benchRunExecutor, never()).stop("run-b");
        verify(benchRunExecutor).cleanup("run-b");
    }

    @Test
    void stopAllCancelsPendingFaultSchedulesBeforeStoppingTheirK6Containers() {
        FaultScheduler faultScheduler = mock(FaultScheduler.class);
        ChaosProperties properties = new ChaosProperties();
        properties.getAppProxy().setInstances(List.of("app-1"));
        EnvironmentStopAllService withSchedule = new EnvironmentStopAllService(
                chaosActionService, trafficService, appProxyService, benchRunExecutor, properties,
                faultScheduler);
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of(
                new K6ContainerGateway.ContainerSummary("jl-k6-run-a", "cid-a", "run-a",
                        K6ContainerGateway.STATUS_RUNNING)));
        when(benchRunExecutor.stop("run-a")).thenReturn(true);
        when(benchRunExecutor.cleanup("run-a")).thenReturn(true);

        withSchedule.stopAll();

        verify(faultScheduler).cancel("run-a");
    }

    @Test
    void aThrowingTrafficStopIsReportedAsFailedWithoutSkippingTheRest() {
        doThrow(new IllegalStateException("docker daemon down")).when(trafficService).stop();

        StopAllResult result = service.stopAll();

        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_FAILED);
        assertThat(result.threads()).containsOnlyKeys("app-1", "app-2", "app-3");
        verify(appProxyService, times(3)).forward(anyString(), anyString(), anyString(), any(), any());
        verify(benchRunExecutor).sweepOrphans();
    }

    @Test
    void oneUnreachableInstanceIsFailedWhileTheOthersStillGetStopAll() {
        when(appProxyService.forward(eq("app-2"), anyString(), anyString(), any(), any()))
                .thenThrow(new IllegalStateException("app-2 unreachable"));
        when(appProxyService.forward(eq("app-3"), anyString(), anyString(), any(), any()))
                .thenReturn(new ProxyResponse(500, "{\"code\":\"50000\"}"));

        StopAllResult result = service.stopAll();

        assertThat(result.threads()).containsExactlyInAnyOrderEntriesOf(
                Map.of("app-1", "ok", "app-2", "failed", "app-3", "failed"));
        verify(appProxyService).forward(eq("app-1"), eq("POST"),
                eq(EnvironmentStopAllService.THREAD_STOP_ALL_PATH), any(), any());
        verify(appProxyService).forward(eq("app-2"), eq("POST"),
                eq(EnvironmentStopAllService.THREAD_STOP_ALL_PATH), any(), any());
        verify(appProxyService).forward(eq("app-3"), eq("POST"),
                eq(EnvironmentStopAllService.THREAD_STOP_ALL_PATH), any(), any());
    }

    @Test
    void k6StopAndCleanupErrorsAreCountedAndStillBestEffort() {
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of(
                new K6ContainerGateway.ContainerSummary("jl-k6-run-a", "cid-a", "run-a",
                        K6ContainerGateway.STATUS_RUNNING),
                new K6ContainerGateway.ContainerSummary("jl-k6-run-b", "cid-b", "run-b",
                        K6ContainerGateway.STATUS_EXITED)));
        when(benchRunExecutor.stop("run-a")).thenThrow(new IllegalStateException("stop failed"));
        when(benchRunExecutor.cleanup("run-a")).thenReturn(true);
        when(benchRunExecutor.cleanup("run-b")).thenThrow(new IllegalStateException("remove failed"));

        StopAllResult result = service.stopAll();

        assertThat(result.k6Stopped()).isZero();
        assertThat(result.k6Cleaned()).isEqualTo(1);
        assertThat(result.k6Failed()).isEqualTo(2);
        // Cleanup still runs after the stop of the same container failed.
        verify(benchRunExecutor).cleanup("run-a");
        verify(benchRunExecutor).cleanup("run-b");
    }

    @Test
    void aContainerWithoutARunIdIsSkippedAndCountedAsFailed() {
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of(
                new K6ContainerGateway.ContainerSummary("jl-k6-orphan", "cid-x", "  ",
                        K6ContainerGateway.STATUS_EXITED)));

        StopAllResult result = service.stopAll();

        assertThat(result.k6Stopped()).isZero();
        assertThat(result.k6Cleaned()).isZero();
        assertThat(result.k6Failed()).isEqualTo(1);
        verify(benchRunExecutor, never()).cleanup(anyString());
    }

    @Test
    void anUnreadableK6InventoryIsReportedNotThrown() {
        when(benchRunExecutor.sweepOrphans()).thenThrow(new IllegalStateException("docker down"));

        StopAllResult result = service.stopAll();

        assertThat(result.k6Failed()).isEqualTo(1);
        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_STOPPED);
    }

    @Test
    void anAbsentTrafficContainerIsNotRunningNotAFailure() {
        when(trafficService.stop()).thenThrow(new ContainerNotFoundException(
                "Background traffic container 'jl-traffic' is not created"));

        StopAllResult result = service.stopAll();

        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_NOT_RUNNING);
        assertThat(result.k6Stopped()).isZero();
        assertThat(result.k6Cleaned()).isZero();
        assertThat(result.k6Failed()).isZero();
    }

    @Test
    void aThrowingContainerRollbackStillRunsTheOtherSteps() {
        when(chaosActionService.stopAll()).thenThrow(new IllegalStateException("rollback bug"));

        StopAllResult result = service.stopAll();

        assertThat(result.reverted()).isZero();
        assertThat(result.failed()).isEqualTo(1);
        assertThat(result.toxiproxy().status()).isEqualTo(NetworkResetReport.STATUS_FAILED);
        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_STOPPED);
        assertThat(result.threads()).containsOnlyKeys("app-1", "app-2", "app-3");
        verify(benchRunExecutor).sweepOrphans();
    }

    @Test
    void noContainerAtAllIsAnEmptySuccessfulSweep() {
        when(trafficService.stop()).thenThrow(new ContainerNotFoundException("not created"));
        when(benchRunExecutor.sweepOrphans()).thenReturn(List.of());

        StopAllResult result = service.stopAll();
        assertThat(result.traffic()).isEqualTo(StopAllResult.TRAFFIC_NOT_RUNNING);
        assertThat(result.k6Stopped()).isZero();
        assertThat(result.k6Cleaned()).isZero();
        assertThat(result.k6Failed()).isZero();
    }
}
