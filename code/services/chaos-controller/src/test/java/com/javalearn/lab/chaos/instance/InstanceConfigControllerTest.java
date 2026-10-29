package com.javalearn.lab.chaos.instance;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.error.InstanceConfigConflictException;
import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.error.InvalidInstanceConfigException;
import com.javalearn.lab.chaos.error.LedgerUnavailableException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static com.javalearn.lab.chaos.instance.InstanceViews.EffectiveInstanceView;
import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceConfigView;
import static com.javalearn.lab.chaos.instance.InstanceViews.InstanceValidationView;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B4-01 HTTP routing/status mapping (service stubbed). */
class InstanceConfigControllerTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private InstanceConfigService service;
    private MockMvc mvc;
    private InstanceConfig config;

    @BeforeEach
    void setUp() throws Exception {
        service = mock(InstanceConfigService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InstanceConfigController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
        config = MAPPER.readValue("{\"jdkVersion\":21,\"xmxMb\":256}", InstanceConfig.class);
    }

    @Test
    void getReturnsStoredConfigAndGeneratedArgs() throws Exception {
        when(service.get("app-1")).thenReturn(view());

        mvc.perform(get("/lab/instances/app-1/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.instance").value("app-1"))
                .andExpect(jsonPath("$.data.status").value("DRAFT"))
                .andExpect(jsonPath("$.data.revision").value(2))
                .andExpect(jsonPath("$.data.config.jdkVersion").value(21))
                .andExpect(jsonPath("$.data.jvmArgs[0]").value("-Xmx256m"))
                .andExpect(jsonPath("$.data.limitedObservability").value(false));
    }

    @Test
    void putSavesADraftWithoutAnyApplyFlag() throws Exception {
        when(service.save(eq("app-1"), any())).thenReturn(view());

        mvc.perform(put("/lab/instances/app-1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdkVersion\":21,\"xmxMb\":256}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("DRAFT"));
    }

    @Test
    void validateIsADryRunEndpoint() throws Exception {
        when(service.validate(eq("app-1"), any())).thenReturn(new InstanceValidationView(
                "app-1", true, config, List.of("-Xmx256m"), List.of("note"), false, 640, 768));

        mvc.perform(post("/lab/instances/app-1/config/validate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdkVersion\":21,\"xmxMb\":256}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.valid").value(true))
                .andExpect(jsonPath("$.data.jvmArgs[0]").value("-Xmx256m"))
                .andExpect(jsonPath("$.data.requiredMemoryMb").value(640));
    }

    @Test
    void effectiveReturnsTheReadbackStatus() throws Exception {
        when(service.effective("app-1")).thenReturn(new EffectiveInstanceView("app-1", "jl-app-1",
                "jdk21", "sha256:abc", "-Xmx256m", "app-1", "OK", "FULL-OBSERVABILITY",
                List.of()));

        mvc.perform(get("/lab/instances/app-1/effective"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.readback").value("OK"))
                .andExpect(jsonPath("$.data.javaToolOptions").value("-Xmx256m"));
    }

    @Test
    void unknownInstanceIs404() throws Exception {
        when(service.get("app-9")).thenThrow(new InstanceNotFoundException("unknown instance app-9"));

        mvc.perform(get("/lab/instances/app-9/config"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void invalidConfigIs400With40001() throws Exception {
        when(service.save(eq("app-1"), any()))
                .thenThrow(new InvalidInstanceConfigException("xmxMb must be >= xmsMb"));

        mvc.perform(put("/lab/instances/app-1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdkVersion\":21,\"xmsMb\":512,\"xmxMb\":256}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("xmxMb must be >= xmsMb"));
    }

    @Test
    void startupChangeDuringARunningRunIs409With40906() throws Exception {
        when(service.save(eq("app-1"), any())).thenThrow(new InstanceConfigConflictException(
                "run run-20261030-001 is RUNNING; JVM/GC/GC-log startup fields cannot change"));

        mvc.perform(put("/lab/instances/app-1/config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"jdkVersion\":21,\"xmxMb\":384}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40906"))
                .andExpect(jsonPath("$.message").value(
                        org.hamcrest.Matchers.containsString("run-20261030-001")));
    }

    @Test
    void degradedLedgerIs503With50302() throws Exception {
        when(service.get("app-1")).thenThrow(new LedgerUnavailableException(
                "ledger state is DEGRADED: mysql down"));

        mvc.perform(get("/lab/instances/app-1/config"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("50302"));
    }

    private InstanceConfigView view() {
        return new InstanceConfigView("app-1", config, "DRAFT", 2, List.of("-Xmx256m"),
                List.of(), false, 640, 768, "2026-10-30T10:00:00Z", null, null);
    }
}
