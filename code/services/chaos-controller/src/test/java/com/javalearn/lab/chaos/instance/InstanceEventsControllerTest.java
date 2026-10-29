package com.javalearn.lab.chaos.instance;

import com.javalearn.lab.chaos.error.InstanceNotFoundException;
import com.javalearn.lab.chaos.web.LabExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** B5-04b HTTP shape of GET /lab/instances/{instance}/events. */
class InstanceEventsControllerTest {

    private InstanceEventsService service;
    private MockMvc mvc;

    @BeforeEach
    void setUp() {
        service = mock(InstanceEventsService.class);
        mvc = MockMvcBuilders.standaloneSetup(new InstanceEventsController(service))
                .setControllerAdvice(new LabExceptionHandler())
                .build();
    }

    @Test
    void eventsReturnTheMatchedLinesAndNote() throws Exception {
        when(service.events("app-1")).thenReturn(new InstanceViews.InstanceEventsView("app-1",
                List.of(new InstanceViews.InstanceEvent("OOM",
                        "java.lang.OutOfMemoryError: Java heap space", "OutOfMemoryError")),
                InstanceEventsService.OK));

        mvc.perform(get("/lab/instances/app-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.instance").value("app-1"))
                .andExpect(jsonPath("$.data.note").value("OK"))
                .andExpect(jsonPath("$.data.events[0].kind").value("OOM"))
                .andExpect(jsonPath("$.data.events[0].observed").value("OutOfMemoryError"))
                .andExpect(jsonPath("$.data.events[0].line")
                        .value("java.lang.OutOfMemoryError: Java heap space"));
    }

    @Test
    void noMatchIsAnEmptyListWithNoData() throws Exception {
        when(service.events("app-1")).thenReturn(new InstanceViews.InstanceEventsView(
                "app-1", List.of(), InstanceEventsService.NO_DATA));

        mvc.perform(get("/lab/instances/app-1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.events").isEmpty())
                .andExpect(jsonPath("$.data.note").value("NO_DATA"));
    }

    @Test
    void unknownInstanceIs404() throws Exception {
        when(service.events("app-9")).thenThrow(new InstanceNotFoundException("unknown"));

        mvc.perform(get("/lab/instances/app-9/events"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }
}
