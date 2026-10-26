package com.javalearn.lab.seckill.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.lab.cache.CacheExperimentDataService;
import com.javalearn.lab.seckill.lab.config.LabProperties;
import com.javalearn.lab.seckill.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.containsString;

/**
 * HTTP contract of {@code /lab/cache/**}: the token gate inherited from
 * {@code LabTokenFilter} (no second auth path), the runtime mode switch with its
 * {@code 40001} rejections, and the seed/state payloads.
 *
 * <p>The generator is mocked so the assertions stay about the wire shape;
 * {@link com.javalearn.lab.seckill.lab.cache.CacheExperimentDataServiceTest} owns
 * the dataset semantics.</p>
 */
class LabCacheControllerTest {

    private static final String TOKEN = "test-lab-token";

    private MockMvc mockMvc;
    private CacheDefenseModeService modeService;
    private CacheExperimentDataService experimentData;

    @BeforeEach
    void setUp() {
        LabCacheProperties properties = TestLabCacheProperties.defaults();
        modeService = new CacheDefenseModeService(properties);
        experimentData = mock(CacheExperimentDataService.class);

        LabProperties labProperties = new LabProperties();
        labProperties.setToken(TOKEN);
        LabCacheController controller = new LabCacheController(modeService, experimentData);
        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .addFilters(new LabTokenFilter(labProperties, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void everyEndpointRequiresTheLabToken() throws Exception {
        mockMvc.perform(get("/lab/cache/mode"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
        mockMvc.perform(post("/lab/cache/seed"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
        mockMvc.perform(get("/lab/cache/state").header(LabTokenFilter.TOKEN_HEADER, "wrong"))
                .andExpect(status().isUnauthorized());

        verify(experimentData, never()).seed();
        verify(experimentData, never()).state();
    }

    @Test
    void modeIsReadableAndSwitchableAtRuntime() throws Exception {
        mockMvc.perform(get("/lab/cache/mode").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.mode").value("off"));

        for (String mode : List.of("null-cache", "bloom", "mutex", "logical-expire", "off")) {
            mockMvc.perform(post("/lab/cache/mode")
                            .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"mode\":\"" + mode + "\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mode").value(mode));

            mockMvc.perform(get("/lab/cache/mode").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.mode").value(mode));
        }
    }

    @Test
    void anUnknownModeIsAFourZeroZeroZeroOneAndChangesNothing() throws Exception {
        mockMvc.perform(post("/lab/cache/mode")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mode\":\"布隆\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Unknown mode: 布隆"));

        assertThat(modeService.current().key()).isEqualTo("off");
    }

    @Test
    void aMissingOrBlankModeIsRejected() throws Exception {
        mockMvc.perform(post("/lab/cache/mode")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Request body is required"));

        mockMvc.perform(post("/lab/cache/mode")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("Mode is required"));

        assertThat(modeService.current().key()).isEqualTo("off");
    }

    @Test
    void seedReturnsTheDatasetItJustGenerated() throws Exception {
        when(experimentData.seed()).thenReturn(new CacheSeedResponse(
                90_000L, 90_999L, 1_000L, 0L, 1_000L, 1_000L,
                200L, 60L, 90_999L, true, 1_020L, true, "null-cache"));

        mockMvc.perform(post("/lab/cache/seed").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.startId").value(90_000))
                .andExpect(jsonPath("$.data.endId").value(90_999))
                .andExpect(jsonPath("$.data.inserted").value(0))
                .andExpect(jsonPath("$.data.alreadyPresent").value(1_000))
                .andExpect(jsonPath("$.data.avalancheKeysWritten").value(200))
                .andExpect(jsonPath("$.data.avalancheTtlSeconds").value(60))
                .andExpect(jsonPath("$.data.hotKeyProductId").value(90_999))
                .andExpect(jsonPath("$.data.bloomIdsAdded").value(1_020))
                .andExpect(jsonPath("$.data.mode").value("null-cache"));

        verify(experimentData).seed();
    }

    @Test
    void stateExposesDefenseDatasetKeysBloomAndNullCacheWithoutSecrets() throws Exception {
        when(experimentData.state()).thenReturn(new CacheStateResponse(
                new CacheStateResponse.Defense("bloom", true),
                new CacheStateResponse.Breakdown("bloom", false, false, 200L, 2_000L, "db",
                        30L, 120L, 2, 100, 0L, 0L),
                new CacheStateResponse.Dataset(90_000L, 90_999L, 1_000L, 1_000L, 0L, 200L, 60L, 90_999L),
                new CacheStateResponse.Keys(true, 12L, 201L, 200L, true),
                new CacheStateResponse.Bloom("bloom:product-ids", true, 1_020L, 10_000L, 0.01D),
                new CacheStateResponse.NullCache("null:product:", 10L, 5L, 3L)));

        String body = mockMvc.perform(get("/lab/cache/state").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data", hasKey("defense")))
                .andExpect(jsonPath("$.data", hasKey("breakdown")))
                .andExpect(jsonPath("$.data", hasKey("dataset")))
                .andExpect(jsonPath("$.data", hasKey("keys")))
                .andExpect(jsonPath("$.data", hasKey("bloom")))
                .andExpect(jsonPath("$.data", hasKey("nullCache")))
                .andExpect(jsonPath("$.data.defense.mode").value("bloom"))
                .andExpect(jsonPath("$.data.defense.cacheToggleEnabled").value(true))
                .andExpect(jsonPath("$.data.breakdown.strategy").value("bloom"))
                .andExpect(jsonPath("$.data.breakdown.mutexActive").value(false))
                .andExpect(jsonPath("$.data.breakdown.logicalExpireActive").value(false))
                .andExpect(jsonPath("$.data.breakdown.mutexOnLockTimeout").value("db"))
                .andExpect(jsonPath("$.data.breakdown.refreshPoolSize").value(2))
                .andExpect(jsonPath("$.data.dataset.rowsInRange").value(1_000))
                .andExpect(jsonPath("$.data.dataset.missingRows").value(0))
                .andExpect(jsonPath("$.data.keys.redisReachable").value(true))
                .andExpect(jsonPath("$.data.keys.l2AvalancheKeysAlive").value(200))
                .andExpect(jsonPath("$.data.bloom.size").value(1_020))
                .andExpect(jsonPath("$.data.nullCache.liveMarkerKeys").value(3))
                .andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(TOKEN);
        assertThat(body).doesNotContain("password");
    }

    @Test
    void stateKeepsWorkingWhenRedisIsDown() throws Exception {
        when(experimentData.state()).thenReturn(new CacheStateResponse(
                new CacheStateResponse.Defense("off", true),
                new CacheStateResponse.Breakdown("off", false, false, 200L, 2_000L, "db",
                        30L, 120L, 2, 100, 0L, 0L),
                new CacheStateResponse.Dataset(90_000L, 90_999L, 1_000L, 0L, 1_000L, 200L, 60L, 90_999L),
                new CacheStateResponse.Keys(false, 0L, 0L, 0L, false),
                new CacheStateResponse.Bloom("bloom:product-ids", false, 0L, 10_000L, 0.01D),
                new CacheStateResponse.NullCache("null:product:", 10L, 5L, 0L)));

        mockMvc.perform(get("/lab/cache/state").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keys.redisReachable").value(false))
                .andExpect(jsonPath("$.data.bloom.initialised").value(false))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
