package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.traffic.TrafficController;
import com.javalearn.lab.chaos.traffic.TrafficService;
import com.javalearn.lab.chaos.traffic.TrafficStateView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TrafficController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc
class TrafficControllerTest {

    private static final String TOKEN_HEADER = "X-Lab-Token";
    private static final String TOKEN = "test-token";
    private static final TrafficStateView RUNNING =
            new TrafficStateView("jl-traffic", "running", "none", true);
    private static final TrafficStateView STOPPED =
            new TrafficStateView("jl-traffic", "exited", "none", false);

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private TrafficService trafficService;

    @Test
    void trafficApiRequiresTheLabToken() throws Exception {
        mockMvc.perform(post("/lab/traffic/start"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"))
                .andExpect(content().string(not(containsString(TOKEN))));
        verify(trafficService, never()).start();
    }

    @Test
    void stateReportsTheTrafficContainer() throws Exception {
        when(trafficService.state()).thenReturn(RUNNING);

        mockMvc.perform(get("/lab/traffic/state").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.container").value("jl-traffic"))
                .andExpect(jsonPath("$.data.state").value("running"))
                .andExpect(jsonPath("$.data.running").value(true));
    }

    @Test
    void startAndStopReturnTheNewState() throws Exception {
        when(trafficService.start()).thenReturn(RUNNING);
        when(trafficService.stop()).thenReturn(STOPPED);

        mockMvc.perform(post("/lab/traffic/start").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(true));
        mockMvc.perform(post("/lab/traffic/stop").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.running").value(false));
    }

    @Test
    void missingTrafficContainerIsNotFound() throws Exception {
        when(trafficService.start())
                .thenThrow(new ContainerNotFoundException("Background traffic container is not created"));

        mockMvc.perform(post("/lab/traffic/start").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.message").value(containsString("not created")));
    }
}
