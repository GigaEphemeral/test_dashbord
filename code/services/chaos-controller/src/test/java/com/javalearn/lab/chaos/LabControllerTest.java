package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.DockerUnavailableException;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.model.ContainerView;
import com.javalearn.lab.chaos.web.LabController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = LabController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class LabControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ContainerGateway containerGateway;

    @Test
    void listReturnsResultShape() throws Exception {
        when(containerGateway.listLabContainers()).thenReturn(List.of(
                new ContainerView("jl-app-1", "running", "healthy"),
                new ContainerView("jl-chaos-controller", "running", "none")));

        mockMvc.perform(get("/containers"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.length()").value(2))
                .andExpect(jsonPath("$.data[0].name").value("jl-app-1"))
                .andExpect(jsonPath("$.data[0].state").value("running"))
                .andExpect(jsonPath("$.data[0].health").value("healthy"))
                .andExpect(jsonPath("$.data[1].health").value("none"));
    }

    @Test
    void logsUseDefaultTailOf200() throws Exception {
        when(containerGateway.tailLogs("jl-app-1", 200)).thenReturn("line-1\nline-2\n");

        mockMvc.perform(get("/logs/jl-app-1"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.TEXT_PLAIN))
                .andExpect(content().string("line-1\nline-2\n"));

        verify(containerGateway).tailLogs("jl-app-1", 200);
    }

    @Test
    void logsClampTailAboveMaximum() throws Exception {
        when(containerGateway.tailLogs("jl-app-1", 1000)).thenReturn("clamped");

        mockMvc.perform(get("/logs/jl-app-1").param("tail", "5000"))
                .andExpect(status().isOk())
                .andExpect(content().string("clamped"));

        verify(containerGateway).tailLogs("jl-app-1", 1000);
    }

    @Test
    void logsRejectTailBelowOne() throws Exception {
        mockMvc.perform(get("/logs/jl-app-1").param("tail", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(containerGateway, never()).tailLogs(anyString(), anyInt());
    }

    @Test
    void logsRejectNonNumericTail() throws Exception {
        mockMvc.perform(get("/logs/jl-app-1").param("tail", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(containerGateway, never()).tailLogs(anyString(), anyInt());
    }

    @Test
    void unknownContainerReturns404ViaAdvice() throws Exception {
        when(containerGateway.tailLogs("jl-missing", 200))
                .thenThrow(new ContainerNotFoundException("Container not found: jl-missing"));

        mockMvc.perform(get("/logs/jl-missing"))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("40401"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void nonWhitelistedContainerReturns403ViaAdvice() throws Exception {
        when(containerGateway.tailLogs("redis", 200))
                .thenThrow(new ContainerNotAllowedException("Container is not part of the lab: redis"));

        mockMvc.perform(get("/logs/redis"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("40301"));
    }

    @Test
    void dockerFailureReturns503ViaAdvice() throws Exception {
        when(containerGateway.listLabContainers())
                .thenThrow(new DockerUnavailableException("Docker daemon unavailable"));

        mockMvc.perform(get("/containers"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50301"));
    }
}
