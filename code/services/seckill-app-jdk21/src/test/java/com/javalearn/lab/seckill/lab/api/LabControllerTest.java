package com.javalearn.lab.seckill.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.lab.config.CacheEffectiveConfigReader;
import com.javalearn.lab.seckill.lab.config.LabProperties;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import com.javalearn.lab.seckill.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.mock.env.MockPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Standalone MVC test for the internal lab API: token gate, toggles contract
 * and the secret-free config view.
 */
class LabControllerTest {

    private static final String TOKEN = "test-lab-token";

    private MockMvc mockMvc;
    private LabToggleService toggleService;

    @BeforeEach
    void setUp() {
        LabToggleProperties toggleProperties = new LabToggleProperties();
        toggleProperties.setLock(true);
        toggleProperties.setIdempotency(true);
        toggleProperties.setMq(true);
        toggleProperties.setRatelimit(true);
        toggleProperties.setCircuitbreaker(true);
        toggleProperties.setCache(true);
        toggleService = new LabToggleService(toggleProperties);

        LabProperties labProperties = new LabProperties();
        labProperties.setToken(TOKEN);
        ObjectMapper objectMapper = new ObjectMapper();

        LabController controller = new LabController(
                toggleService, new LabInfoProvider("seckill-app", "test", "app-1"),
                new CacheEffectiveConfigReader(stubEnvironment()));
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new LabTokenFilter(labProperties, objectMapper))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void togglesWithoutTokenReturn401() throws Exception {
        mockMvc.perform(get("/lab/toggles"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(TOKEN))));
    }

    @Test
    void wrongTokenReturns401() throws Exception {
        mockMvc.perform(get("/lab/toggles").header(LabTokenFilter.TOKEN_HEADER, "wrong-token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
    }

    @Test
    void optionsBypassTheTokenCheck() throws Exception {
        int status = mockMvc.perform(options("/lab/toggles"))
                .andReturn().getResponse().getStatus();
        assertThat(status).isNotEqualTo(401);
    }

    @Test
    void authorizedGetReturnsExactlyTheSixToggles() throws Exception {
        mockMvc.perform(get("/lab/toggles").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data", hasKey("lock")))
                .andExpect(jsonPath("$.data", hasKey("idempotency")))
                .andExpect(jsonPath("$.data", hasKey("mq")))
                .andExpect(jsonPath("$.data", hasKey("ratelimit")))
                .andExpect(jsonPath("$.data", hasKey("circuitbreaker")))
                .andExpect(jsonPath("$.data", hasKey("cache")))
                .andExpect(jsonPath("$.data.lock").value(true))
                .andExpect(jsonPath("$.data.cache").value(true));
    }

    @Test
    void authorizedPostUpdatesOneToggleAndReturnsTheSnapshot() throws Exception {
        mockMvc.perform(post("/lab/toggles")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"cache\",\"value\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.cache").value(false));

        mockMvc.perform(get("/lab/toggles").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cache").value(false))
                .andExpect(jsonPath("$.data.lock").value(true));

        mockMvc.perform(post("/lab/toggles")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"cache\",\"value\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cache").value(true));
    }

    @Test
    void unknownKeyReturns400() throws Exception {
        mockMvc.perform(post("/lab/toggles")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"circuit\",\"value\":false}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Unknown toggle: circuit"));
    }

    @Test
    void missingValueReturns400() throws Exception {
        mockMvc.perform(post("/lab/toggles")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"cache\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value(
                        "Toggle value is required for 'cache'"));
    }

    @Test
    void emptyBodyReturns400() throws Exception {
        mockMvc.perform(post("/lab/toggles")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Request body is required"));
    }

    @Test
    void configExposesAppEnvironmentInstanceAndJvmWithoutSecrets() throws Exception {
        String body = mockMvc.perform(get("/lab/config")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.app").value("seckill-app"))
                .andExpect(jsonPath("$.data.env").value("test"))
                .andExpect(jsonPath("$.data.instance").value("app-1"))
                .andExpect(jsonPath("$.data.jvmVersion").isNotEmpty())
                .andExpect(jsonPath("$.data.toggles.cache").value(true))
                .andExpect(jsonPath("$.data.toggles", hasKey("circuitbreaker")))
                // B0-04 effective-value read-back: value + env + declared default + source.
                .andExpect(jsonPath("$.data.cacheConfig[0].configKey")
                        .value("app.lab.cache.l2.ttl-seconds"))
                .andExpect(jsonPath("$.data.cacheConfig[0].env")
                        .value("LAB_CACHE_L2_TTL_SECONDS"))
                .andExpect(jsonPath("$.data.cacheConfig[0].value").value("300"))
                .andExpect(jsonPath("$.data.cacheConfig[0].defaultValue").value("300"))
                .andExpect(jsonPath("$.data.cacheConfig[0].source").value("mockSource"))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN);
        assertThat(body).doesNotContain("password");
        assertThat(toggleService.snapshot()).hasSize(6);
    }

    /**
     * One cache key declared exactly the way {@code application.yml} declares it:
     * an env placeholder with a default, in a source named {@code mockSource} so
     * the payload can prove the reader reports the winning source rather than a
     * hard-coded label. The override semantics themselves are covered by
     * {@code CacheEffectiveConfigReaderTest}.
     */
    private static MockEnvironment stubEnvironment() {
        MockEnvironment environment = new MockEnvironment();
        environment.getPropertySources().remove("mockProperties");
        environment.getPropertySources().addFirst(new MockPropertySource("mockSource")
                .withProperty("app.lab.cache.l2.ttl-seconds", "${LAB_CACHE_L2_TTL_SECONDS:300}"));
        return environment;
    }
}
