package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.NetworkResetReport;
import com.javalearn.lab.chaos.error.InvalidToxiproxyRequestException;
import com.javalearn.lab.chaos.error.ToxiproxyConflictException;
import com.javalearn.lab.chaos.error.ToxiproxyNotFoundException;
import com.javalearn.lab.chaos.error.ToxiproxyUnavailableException;
import com.javalearn.lab.chaos.toxiproxy.ProxyEnabledView;
import com.javalearn.lab.chaos.toxiproxy.ProxyResetView;
import com.javalearn.lab.chaos.toxiproxy.ProxyStateView;
import com.javalearn.lab.chaos.toxiproxy.ToxicRequest;
import com.javalearn.lab.chaos.toxiproxy.ToxicView;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyService;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyStateView;
import com.javalearn.lab.chaos.web.ToxiproxyController;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ToxiproxyController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class ToxiproxyControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ToxiproxyService toxiproxyService;

    private static final ToxicView LATENCY = new ToxicView("latency", "latency",
            Map.of("latency", 500, "jitter", 50), 1.0);

    @Test
    void addToxicReturns202WithTheCreatedView() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any(ToxicRequest.class))).thenReturn(LATENCY);

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\",\"name\":\"latency\",\"params\":{\"latencyMs\":500,\"jitterMs\":50}}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.name").value("latency"))
                .andExpect(jsonPath("$.data.type").value("latency"))
                .andExpect(jsonPath("$.data.attributes.latency").value(500))
                .andExpect(jsonPath("$.data.toxicity").value(1.0));

        ArgumentCaptor<ToxicRequest> captor = ArgumentCaptor.forClass(ToxicRequest.class);
        verify(toxiproxyService).addToxic(eq("redis"), captor.capture());
        assertThat(captor.getValue().type()).isEqualTo("latency");
        assertThat(captor.getValue().params()).containsEntry("latencyMs", 500);
    }

    @Test
    void addToxicWithoutBodyIsAcceptedByMvcAndRejectedByTheService() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new InvalidToxiproxyRequestException("toxic type is required"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void removeToxicReturnsTheStandardEnvelope() throws Exception {
        mockMvc.perform(delete("/chaos/toxiproxy/redis/toxics/latency"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.success").value(true));

        verify(toxiproxyService).removeToxic("redis", "latency");
    }

    @Test
    void resetReturnsRemovedCountAndEnabledFlag() throws Exception {
        when(toxiproxyService.reset("redis")).thenReturn(new ProxyResetView("redis", 2, true));

        mockMvc.perform(post("/chaos/toxiproxy/redis/reset"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proxy").value("redis"))
                .andExpect(jsonPath("$.data.toxicsRemoved").value(2))
                .andExpect(jsonPath("$.data.enabled").value(true));
    }

    @Test
    void disableAndEnableReturnTheNewState() throws Exception {
        when(toxiproxyService.setEnabled("mysql", false)).thenReturn(new ProxyEnabledView("mysql", false));
        when(toxiproxyService.setEnabled("mysql", true)).thenReturn(new ProxyEnabledView("mysql", true));

        mockMvc.perform(post("/chaos/toxiproxy/mysql/disable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.proxy").value("mysql"))
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(post("/chaos/toxiproxy/mysql/enable"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true));

        verify(toxiproxyService).setEnabled("mysql", false);
        verify(toxiproxyService).setEnabled("mysql", true);
    }

    @Test
    void stateListsProxiesAndToxics() throws Exception {
        when(toxiproxyService.state()).thenReturn(new ToxiproxyStateView(true,
                List.of("redis", "mysql"),
                List.of(new ProxyStateView("redis", true, true, List.of(LATENCY)),
                        ProxyStateView.missing("mysql"))));

        mockMvc.perform(get("/chaos/toxiproxy/state"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.allowedProxies[0]").value("redis"))
                .andExpect(jsonPath("$.data.proxies[0].present").value(true))
                .andExpect(jsonPath("$.data.proxies[0].toxics[0].name").value("latency"))
                .andExpect(jsonPath("$.data.proxies[1].present").value(false));
    }

    @Test
    void resetAllReturnsTheNetworkReport() throws Exception {
        when(toxiproxyService.resetAll())
                .thenReturn(new NetworkResetReport(NetworkResetReport.STATUS_RESET, 2, 2, List.of()));

        mockMvc.perform(post("/chaos/toxiproxy/reset-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("reset"))
                .andExpect(jsonPath("$.data.proxiesReset").value(2))
                .andExpect(jsonPath("$.data.proxiesTotal").value(2));
    }

    @Test
    void unknownProxyReturns404() throws Exception {
        when(toxiproxyService.addToxic(eq("postgres"), any()))
                .thenThrow(new ToxiproxyNotFoundException("Unknown toxiproxy proxy 'postgres'"));

        mockMvc.perform(post("/chaos/toxiproxy/postgres/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void disabledLayerReturns503() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new ToxiproxyUnavailableException("Toxiproxy support is disabled"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("disabled")));
    }

    @Test
    void invalidToxicReturns400() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new InvalidToxiproxyRequestException("Unsupported toxic type: explode"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"explode\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void duplicateToxicReturns409() throws Exception {
        when(toxiproxyService.addToxic(eq("redis"), any()))
                .thenThrow(new ToxiproxyConflictException("Toxiproxy conflict for toxic 'latency'"));

        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"latency\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"));
    }

    @Test
    void malformedBodyReturns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/chaos/toxiproxy/redis/toxics")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(toxiproxyService, never()).addToxic(any(), any());
    }
}
