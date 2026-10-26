package com.javalearn.lab.chaos;

import com.javalearn.lab.chaos.error.GuideConflictException;
import com.javalearn.lab.chaos.error.InvalidScenarioException;
import com.javalearn.lab.chaos.error.ScenarioNotFoundException;
import com.javalearn.lab.chaos.guide.GuideController;
import com.javalearn.lab.chaos.guide.GuideScenarioSummary;
import com.javalearn.lab.chaos.guide.GuideService;
import com.javalearn.lab.chaos.guide.GuideStateView;
import com.javalearn.lab.chaos.guide.GuideStepExplain;
import com.javalearn.lab.chaos.guide.GuideStepView;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GuideController.class, properties = "lab.token=test-token")
@AutoConfigureMockMvc
class GuideControllerTest {

    private static final String TOKEN_HEADER = "X-Lab-Token";
    private static final String TOKEN = "test-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private GuideService guideService;

    @Test
    void guideApiRequiresTheLabToken() throws Exception {
        mockMvc.perform(get("/lab/guide/state"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"))
                .andExpect(content().string(not(containsString(TOKEN))));
        verify(guideService, never()).state();
    }

    @Test
    void scenariosReturnsTheConfiguredList() throws Exception {
        when(guideService.scenarios()).thenReturn(List.of(
                new GuideScenarioSummary("quick-failover", "Quick", "desc", 1, 30.0)));

        mockMvc.perform(get("/lab/guide/scenarios").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data[0].id").value("quick-failover"))
                .andExpect(jsonPath("$.data[0].stepCount").value(1))
                .andExpect(content().string(not(containsString(TOKEN))));
    }

    @Test
    void stateReturnsTheGuideView() throws Exception {
        when(guideService.state()).thenReturn(view("running", 0));

        mockMvc.perform(get("/lab/guide/state").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.scenarioId").value("quick-failover"))
                .andExpect(jsonPath("$.data.status").value("running"))
                .andExpect(jsonPath("$.data.currentStep").value(0))
                .andExpect(jsonPath("$.data.totalSteps").value(2))
                .andExpect(jsonPath("$.data.steps.length()").value(2))
                .andExpect(jsonPath("$.data.steps[0].title").value("Stop app-3"))
                .andExpect(jsonPath("$.data.steps[0].explain.symptom").value("app-3 指标消失"))
                // A boolean getter on the record would leak as an "empty" property.
                .andExpect(jsonPath("$.data.steps[0].explain.empty").doesNotExist())
                .andExpect(jsonPath("$.data.steps[1].explain").value(nullValue()))
                .andExpect(jsonPath("$.data.activeActions").value(1))
                .andExpect(jsonPath("$.data.resetMode").value("manual"));
    }

    @Test
    void startLoadsTheScenario() throws Exception {
        when(guideService.start("quick-failover")).thenReturn(view("running", 0));

        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"quick-failover\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("running"))
                .andExpect(jsonPath("$.data.currentStep").value(0));

        verify(guideService).start("quick-failover");
    }

    @Test
    void stepPauseResumeAndResetReturnTheNewState() throws Exception {
        when(guideService.step()).thenReturn(view("completed", 2));
        when(guideService.skip()).thenReturn(view("running", 1));
        when(guideService.pause()).thenReturn(view("paused", 0));
        when(guideService.resume()).thenReturn(view("running", 0));
        when(guideService.reset()).thenReturn(idle());

        mockMvc.perform(post("/lab/guide/step").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("completed"));
        mockMvc.perform(post("/lab/guide/skip").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("running"))
                .andExpect(jsonPath("$.data.currentStep").value(1));
        mockMvc.perform(post("/lab/guide/pause").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("paused"));
        mockMvc.perform(post("/lab/guide/resume").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("running"));
        mockMvc.perform(post("/lab/guide/reset").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("idle"));
    }

    @Test
    void stepInWrongStateReturns409() throws Exception {
        when(guideService.step())
                .thenThrow(new GuideConflictException("Guide is idle; step is only allowed while running"));

        mockMvc.perform(post("/lab/guide/step").header(TOKEN_HEADER, TOKEN))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"))
                .andExpect(jsonPath("$.message").value(containsString("idle")));
    }

    @Test
    void startWhileRunningReturns409() throws Exception {
        when(guideService.start(any()))
                .thenThrow(new GuideConflictException("Guide is running; reset first"));

        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"quick-failover\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("40901"));
    }

    @Test
    void unknownScenarioReturns404() throws Exception {
        when(guideService.start("missing"))
                .thenThrow(new ScenarioNotFoundException("Unknown scenario: missing"));

        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"missing\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("40401"));
    }

    @Test
    void invalidScenarioReturns400() throws Exception {
        when(guideService.start("broken"))
                .thenThrow(new InvalidScenarioException("broken.json: schemaVersion must be 1"));

        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"scenarioId\":\"broken\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(containsString("schemaVersion")));
    }

    @Test
    void missingScenarioIdReturns400() throws Exception {
        when(guideService.start(null))
                .thenThrow(new InvalidScenarioException("scenarioId is required"));

        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));
    }

    @Test
    void malformedBodyReturns400WithoutCallingTheService() throws Exception {
        mockMvc.perform(post("/lab/guide/start").header(TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"));

        verify(guideService, never()).start(any());
    }

    private static final List<GuideStepView> STEPS = List.of(
            new GuideStepView(5, "kill", "jl-app-3", 15, "Stop app-3", "step one",
                    new GuideStepExplain("app-3 指标消失", "实例被停止", "Nginx 摘除", "P95 回落后恢复")),
            new GuideStepView(20, "start", "jl-app-3", null, "Start app-3", "step two", null));

    private static GuideStateView view(String status, int currentStep) {
        return new GuideStateView("quick-failover", "Quick failover", status, currentStep, 2,
                STEPS, 1, null, "manual");
    }

    private static GuideStateView idle() {
        return new GuideStateView(null, null, "idle", 0, 0, List.of(), 0, null, "manual");
    }
}
