package com.javalearn.lab.seckill.jdk8.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.jdk8.lab.cache.preheat.CachePreheatService;
import com.javalearn.lab.seckill.jdk8.lab.cache.preheat.PreheatTrigger;
import com.javalearn.lab.seckill.jdk8.lab.config.LabProperties;
import com.javalearn.lab.seckill.jdk8.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /lab/cache/preheat}: the inherited token gate, the
 * small console-facing GET snapshot, and a POST that runs MANUAL and returns
 * the post-run state. The service is mocked; its semantics are covered by
 * {@code CachePreheatServiceTest}.
 */
class LabCachePreheatControllerTest {

    private static final String TOKEN = "test-lab-token";

    private MockMvc mockMvc;
    private CachePreheatService preheatService;

    @BeforeEach
    void setUp() {
        preheatService = mock(CachePreheatService.class);
        LabProperties labProperties = new LabProperties();
        labProperties.setToken(TOKEN);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LabCachePreheatController(preheatService))
                .addFilters(new LabTokenFilter(labProperties, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void bothEndpointsRequireTheLabToken() throws Exception {
        mockMvc.perform(get("/lab/cache/preheat"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
        mockMvc.perform(post("/lab/cache/preheat"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));

        verify(preheatService, never()).state();
        verify(preheatService, never()).run(PreheatTrigger.MANUAL);
    }

    @Test
    void stateIsSmallAndConsoleFriendly() throws Exception {
        when(preheatService.state()).thenReturn(state());

        String body = mockMvc.perform(get("/lab/cache/preheat")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data", hasKey("enabled")))
                .andExpect(jsonPath("$.data", hasKey("cacheEnabled")))
                .andExpect(jsonPath("$.data", hasKey("hotListKey")))
                .andExpect(jsonPath("$.data", hasKey("topN")))
                .andExpect(jsonPath("$.data", hasKey("hotListSize")))
                .andExpect(jsonPath("$.data", hasKey("trackedKeys")))
                .andExpect(jsonPath("$.data", hasKey("running")))
                .andExpect(jsonPath("$.data", hasKey("lastRun")))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.cacheEnabled").value(true))
                .andExpect(jsonPath("$.data.hotListKey").value("hot:products"))
                .andExpect(jsonPath("$.data.topN").value(200))
                .andExpect(jsonPath("$.data.hotListSize").value(42))
                .andExpect(jsonPath("$.data.trackedKeys").value(7))
                .andExpect(jsonPath("$.data.running").value(false))
                .andExpect(jsonPath("$.data.lastRun.trigger").value("startup"))
                .andExpect(jsonPath("$.data.lastRun.outcome").value("success"))
                .andExpect(jsonPath("$.data.lastRun.loaded").value(40))
                .andExpect(jsonPath("$.data.lastRun.durationMs").value(123))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN);
        assertThat(body).doesNotContain("password");
    }

    @Test
    void postTriggersAManualRunAndReturnsThePostRunState() throws Exception {
        when(preheatService.state()).thenReturn(state());

        mockMvc.perform(post("/lab/cache/preheat")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.lastRun.trigger").value("startup"));

        verify(preheatService).run(PreheatTrigger.MANUAL);
    }

    @Test
    void aStateWithoutAnyRunYetSerialisesANullLastRun() throws Exception {
        when(preheatService.state()).thenReturn(new CachePreheatStateResponse(
                true, false, "hot:products", 200L, 0L, 0L, false, null));

        mockMvc.perform(get("/lab/cache/preheat").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cacheEnabled").value(false))
                .andExpect(jsonPath("$.data.lastRun").doesNotExist());
    }

    private static CachePreheatStateResponse state() {
        return new CachePreheatStateResponse(
                true, true, "hot:products", 200L, 42L, 7L, false,
                new CachePreheatStateResponse.LastRun(
                        "startup", "2026-10-27T00:00:00Z", "success", 123L,
                        42, 40, 1, 1, 0, null));
    }
}
