package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class InstanceLogsControllerTest {

    private InstanceLogsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InstanceLogsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InstanceLogsController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void returnsTheGcLogView() throws Exception {
        when(service.gcLog("app-1", 20)).thenReturn(new InstanceLogsView("app-1", "OK",
                "/app/lab-snapshots/gc-app-1.log", 20, false, "line", null));

        mvc.perform(get("/lab/instances/app-1/logs/gc").param("lines", "20"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("OK"))
                .andExpect(jsonPath("$.data.content").value("line"));

        verify(service).gcLog("app-1", 20);
    }

    @Test
    void unknownInstanceIsMappedByTheHandler() throws Exception {
        when(service.gcLog(any(), any())).thenThrow(new com.javalearn.lab.chaos.error
                .InstanceNotFoundException("unknown instance 'app-9'"));

        mvc.perform(get("/lab/instances/app-9/logs/gc"))
                .andExpect(status().isNotFound());
    }
}
