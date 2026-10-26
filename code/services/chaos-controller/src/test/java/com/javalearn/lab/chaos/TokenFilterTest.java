package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.actuate.observability.AutoConfigureObservability;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "lab.token=test-token",
        "lab.docker.enabled=false",
        "observability.env=test",
        "observability.instance=chaos-controller-test",
        "management.endpoints.web.base-path=/",
        "management.endpoints.web.path-mapping.prometheus=metrics",
        "management.endpoints.web.exposure.include=health,info,prometheus"
})
@AutoConfigureMockMvc
@AutoConfigureObservability
class TokenFilterTest {

    private static final String TOKEN_HEADER = "X-Lab-Token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContainerGateway containerGateway;

    @MockitoBean
    private ChaosActionService chaosActionService;

    @BeforeEach
    void setUp() {
        when(containerGateway.listLabContainers())
                .thenReturn(List.of(new ContainerView("jl-app-1", "running", "healthy")));
        when(chaosActionService.stopAll()).thenReturn(new StopAllResult(0, 0));
    }

    @Test
    void containersWithoutTokenReturns401() throws Exception {
        mockMvc.perform(get("/containers"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("40101"))
                .andExpect(jsonPath("$.message").value("Unauthorized"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void containersWithWrongTokenReturns401() throws Exception {
        mockMvc.perform(get("/containers").header(TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"))
                .andExpect(content().string(not(containsString("wrong-token"))));
    }

    @Test
    void containersWithCorrectTokenReturns200() throws Exception {
        mockMvc.perform(get("/containers").header(TOKEN_HEADER, "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].name").value("jl-app-1"))
                .andExpect(jsonPath("$.data[0].state").value("running"))
                .andExpect(jsonPath("$.data[0].health").value("healthy"));
    }

    @Test
    void healthBypassesToken() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void metricsBypassesToken() throws Exception {
        mockMvc.perform(get("/metrics"))
                .andExpect(status().isOk())
                .andExpect(content().string(containsString("jvm_")));
    }

    @Test
    void preflightOptionsBypassesToken() throws Exception {
        mockMvc.perform(options("/containers")
                        .header("Origin", "http://127.0.0.1:8080")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().is(not(401)));
    }

    @Test
    void chaosActionsWithoutTokenReturns401() throws Exception {
        mockMvc.perform(post("/chaos/instances/jl-app-1/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":5}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
    }

    @Test
    void chaosStopAllWithTokenReturns200() throws Exception {
        mockMvc.perform(post("/chaos/stop-all").header(TOKEN_HEADER, "test-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.reverted").value(0));
    }
}
