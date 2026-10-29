package com.javalearn.lab.chaos.metrics;

import com.javalearn.lab.chaos.error.InvalidMetricsRequestException;
import com.javalearn.lab.chaos.error.LedgerRunNotFoundException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.javalearn.lab.chaos.metrics.MetricsViews.LinksView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.MetricsQueryView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.SeriesView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.StatsView;
import static com.javalearn.lab.chaos.metrics.MetricsViews.WindowView;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Routing, envelope and status mapping for the two B1-03 endpoints. */
class MetricsControllerTest {

    private MetricsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(MetricsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new MetricsController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void runMetricsReturnsTheAggregationEnvelope() throws Exception {
        when(service.queryRun(eq("run-1"), any(), any(), eq("http.rps"), eq("adhoc")))
                .thenReturn(view("run", "run-1", "OK"));

        mvc.perform(get("/lab/runs/run-1/metrics")
                        .param("keys", "http.rps")
                        .param("phase", "adhoc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.source").value("run"))
                .andExpect(jsonPath("$.data.runId").value("run-1"))
                .andExpect(jsonPath("$.data.quality").value("OK"))
                .andExpect(jsonPath("$.data.metrics[0].key").value("http.rps"))
                .andExpect(jsonPath("$.data.metrics[0].quality").value("OK"))
                .andExpect(jsonPath("$.data.metrics[0].series[0].labels.instance").value("app-1"))
                .andExpect(jsonPath("$.data.metrics[0].series[0].points[0][1]").value(2.5))
                .andExpect(jsonPath("$.data.links.grafanaUrl").value("http://grafana:3000/explore?from=1&to=2"));
    }

    @Test
    void windowEndpointForwardsFromToKeysAndPhase() throws Exception {
        when(service.queryWindow(eq("2026-10-28T10:00:00Z"), eq("2026-10-28T11:00:00Z"),
                eq("http.rps"), eq("adhoc"))).thenReturn(view("window", null, "NO_DATA"));

        mvc.perform(get("/lab/metrics/window")
                        .param("from", "2026-10-28T10:00:00Z")
                        .param("to", "2026-10-28T11:00:00Z")
                        .param("keys", "http.rps")
                        .param("phase", "adhoc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.source").value("window"))
                .andExpect(jsonPath("$.data.quality").value("NO_DATA"));

        verify(service).queryWindow("2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z",
                "http.rps", "adhoc");
    }

    @Test
    void invalidWindowOrKeyInputIs400() throws Exception {
        when(service.queryWindow(any(), any(), any(), any()))
                .thenThrow(new InvalidMetricsRequestException("Unknown metric key(s): nope"));

        mvc.perform(get("/lab/metrics/window")
                        .param("from", "2026-10-28T10:00:00Z")
                        .param("to", "2026-10-28T11:00:00Z")
                        .param("keys", "nope"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Unknown metric key(s): nope"));
    }

    @Test
    void unknownRunIs404() throws Exception {
        when(service.queryRun(any(), any(), any(), any(), any()))
                .thenThrow(new LedgerRunNotFoundException("Run 'run-x' was not found"));

        mvc.perform(get("/lab/runs/run-x/metrics"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value("Run 'run-x' was not found"));
    }

    private static MetricsQueryView view(String source, String runId, String quality) {
        StatsView stats = new StatsView(2.5, 2.5, 2.5, 2.5, 1);
        SeriesView series = new SeriesView(java.util.Map.of("instance", "app-1"),
                List.of(new double[]{1, 2.5}), stats);
        MetricView metric = new MetricView("http.rps", "RPS", "req/s", "sum(rate(x[15s]))",
                true, "OK", null, List.of(series));
        List<MetricView> metrics = "OK".equals(quality)
                ? List.of(metric)
                : List.of(new MetricView("http.rps", "RPS", "req/s", "sum(rate(x[15s]))",
                true, quality, "no series", List.of()));
        return new MetricsQueryView(source, runId, "adhoc",
                new WindowView("2026-10-28T10:00:00Z", "2026-10-28T11:00:00Z",
                        3600, 7200, 15, "15s"),
                quality, "reason", "2026-10-28T12:00:00Z", metrics,
                new LinksView("http://grafana:3000/explore?from=1&to=2",
                        "http://grafana:3000/explore?left=%7B%22datasource%22%3A%22tempo%22%7D"),
                List.of());
    }
}
