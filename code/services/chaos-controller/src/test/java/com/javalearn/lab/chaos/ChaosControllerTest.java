package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.action.ChaosActionRequest;
import com.javalearn.lab.chaos.action.ChaosActionService;
import com.javalearn.lab.chaos.action.ChaosActionView;
import com.javalearn.lab.chaos.action.StopAllResult;
import com.javalearn.lab.chaos.error.ChaosConflictException;
import com.javalearn.lab.chaos.error.ContainerNotAllowedException;
import com.javalearn.lab.chaos.error.ContainerNotFoundException;
import com.javalearn.lab.chaos.error.InvalidChaosActionException;
import com.javalearn.lab.chaos.web.ChaosController;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = ChaosController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc(addFilters = false)
class ChaosControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ChaosActionService chaosActionService;

    @Test
    void validActionReturns202WithView() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any(ChaosActionRequest.class)))
                .thenReturn(new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":30}"))
                .andExpect(status().isAccepted())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.id").value("id-1"))
                .andExpect(jsonPath("$.data.target").value("jl-app-3"))
                .andExpect(jsonPath("$.data.action").value("kill"))
                .andExpect(jsonPath("$.data.ttlSeconds").value(30))
                .andExpect(jsonPath("$.data.revertAction").value("start"))
                .andExpect(jsonPath("$.data.status").value("active"));

        verify(chaosActionService).execute(eq("jl-app-3"), any(ChaosActionRequest.class));
    }

    @Test
    void oneShotActionWithoutBodyTtlReturns202() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenReturn(new ChaosActionView("id-2", "jl-app-3", "restart", null, null, null, "completed"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"restart\"}"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.data.action").value("restart"))
                .andExpect(jsonPath("$.data.status").value("completed"));
    }

    @Test
    void invalidActionReturns400() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenThrow(new InvalidChaosActionException("Unsupported action: explode (allowed: kill|start|restart|freeze|resume)"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"explode\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.success").value(false));
    }

    @Test
    void malformedBodyReturns400WithoutCallingService() throws Exception {
        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(chaosActionService, never()).execute(any(), any());
    }

    @Test
    void unknownContainerReturns404() throws Exception {
        when(chaosActionService.execute(eq("jl-missing"), any()))
                .thenThrow(new ContainerNotFoundException("Container not found: jl-missing"));

        mockMvc.perform(post("/chaos/instances/jl-missing/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":5}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void nonLabContainerReturns403() throws Exception {
        when(chaosActionService.execute(eq("redis"), any()))
                .thenThrow(new ContainerNotAllowedException("Container is not part of the lab: redis"));

        mockMvc.perform(post("/chaos/instances/redis/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"kill\",\"ttlSeconds\":5}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("40301"));
    }

    @Test
    void duplicateActiveActionReturns409() throws Exception {
        when(chaosActionService.execute(eq("jl-app-3"), any()))
                .thenThrow(new ChaosConflictException("Target jl-app-3 already has active chaos action id-1"));

        mockMvc.perform(post("/chaos/instances/jl-app-3/actions")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"freeze\",\"ttlSeconds\":5}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value(org.hamcrest.Matchers.containsString("id-1")));
    }

    @Test
    void listReturnsActiveActions() throws Exception {
        when(chaosActionService.activeActions()).thenReturn(List.of(
                new ChaosActionView("id-1", "jl-app-3", "kill", 30,
                        Instant.parse("2026-10-26T10:00:30Z"), "start", "active")));

        mockMvc.perform(get("/chaos/actions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].target").value("jl-app-3"))
                .andExpect(jsonPath("$.data[0].revertAction").value("start"));
    }

    @Test
    void stopAllReturnsRevertedCount() throws Exception {
        when(chaosActionService.stopAll()).thenReturn(new StopAllResult(2, 0));

        mockMvc.perform(post("/chaos/stop-all"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.reverted").value(2))
                .andExpect(jsonPath("$.data.failed").value(0));
    }
}
