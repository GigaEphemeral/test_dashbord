package com.javalearn.lab.chaos.topology;

import com.javalearn.lab.chaos.topology.TopologyViews.TopologyEdgeView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyMetricsView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyNodeView;
import com.javalearn.lab.chaos.topology.TopologyViews.TopologyView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B5-01a HTTP routing: {@code GET /lab/topology} and its two query parameters. */
class TopologyControllerTest {

    private TopologyService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(TopologyService.class);
        mvc = MockMvcBuilders.standaloneSetup(new TopologyController(service)).build();
    }

    @Test
    void defaultRequestReturnsTheEnvelopeAndDisablesSidecars() throws Exception {
        when(service.snapshot(isNull(), eq(false))).thenReturn(view(false));

        mvc.perform(get("/lab/topology"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.generatedAt").value("2026-10-29T12:00:00Z"))
                .andExpect(jsonPath("$.data.includeSidecars").value(false))
                .andExpect(jsonPath("$.data.enabledNodes[0]").value("app-1"))
                .andExpect(jsonPath("$.data.nodes[0].id").value("jl-app-1"))
                .andExpect(jsonPath("$.data.nodes[0].health").value("UP"))
                .andExpect(jsonPath("$.data.nodes[0].metrics.cpuPercent").value(5.0))
                .andExpect(jsonPath("$.data.edges[0].kind").value("http"));
    }

    @Test
    void queryParametersAreForwardedVerbatim() throws Exception {
        when(service.snapshot(eq("app-1,app-2"), eq(true))).thenReturn(view(true));

        mvc.perform(get("/lab/topology")
                        .param("includeSidecars", "true")
                        .param("enabled", "app-1,app-2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.includeSidecars").value(true));
    }

    private static TopologyView view(boolean includeSidecars) {
        TopologyNodeView node = new TopologyNodeView("jl-app-1", "app-1", "app-1", "java",
                true, false, "UP", "FULL-OBSERVABILITY", "21", "jdk21", "sha256:app1", "OK",
                new TopologyMetricsView(5.0, 1.0, 2.0, null, null, 3.0, 1.0, 2.0, 4.0,
                        0.0, 0.0, 1.5, 10.0, 8.0, 1_793_268_000L),
                List.of());
        return new TopologyView("2026-10-29T12:00:00Z", List.of("app-1"), includeSidecars,
                List.of(node), List.of(new TopologyEdgeView("jl-nginx", "jl-app-1", "http")));
    }
}
