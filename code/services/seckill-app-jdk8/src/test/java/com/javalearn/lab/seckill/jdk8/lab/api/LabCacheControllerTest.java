package com.javalearn.lab.seckill.jdk8.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.jdk8.lab.cache.CacheExperimentDataService;
import com.javalearn.lab.seckill.jdk8.lab.config.LabProperties;
import com.javalearn.lab.seckill.jdk8.lab.config.LabCacheProperties;
import com.javalearn.lab.seckill.jdk8.lab.config.TestLabCacheProperties;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.jdk8.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasKey;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
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
        mockMvc = MockMvcBuilders.standaloneSetup(
                        controller, new LabCacheKeysController(experimentData))
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
        mockMvc.perform(get("/lab/cache/keys"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/lab/cache/key"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/lab/cache/reset"))
                .andExpect(status().isUnauthorized());

        verify(experimentData, never()).seed(any());
        verify(experimentData, never()).state();
        verify(experimentData, never()).scanKeys(any(), any(), any());
        verify(experimentData, never()).keyDetail(any(), any());
        verify(experimentData, never()).reset(anyBoolean());
    }

    @Test
    void modeIsReadableAndSwitchableAtRuntime() throws Exception {
        mockMvc.perform(get("/lab/cache/mode").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.mode").value("off"));

        for (String mode : Arrays.asList("null-cache", "bloom", "mutex", "logical-expire", "off")) {
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
        when(experimentData.seed(any())).thenReturn(new CacheSeedResponse(
                90_000L, 90_999L, 1_000L, 0L, 1_000L, 1_000L,
                200L, 60L, 90_999L, true, 1_020L, true, "null-cache"));

        mockMvc.perform(post("/lab/cache/seed").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.startId").value(90_000))
                .andExpect(jsonPath("$.data.endId").value(90_999))
                .andExpect(jsonPath("$.data.requestedProducts").value(1_000))
                .andExpect(jsonPath("$.data.inserted").value(0))
                .andExpect(jsonPath("$.data.alreadyPresent").value(1_000))
                .andExpect(jsonPath("$.data.avalancheKeysWritten").value(200))
                .andExpect(jsonPath("$.data.avalancheTtlSeconds").value(60))
                .andExpect(jsonPath("$.data.hotKeyProductId").value(90_999))
                .andExpect(jsonPath("$.data.bloomIdsAdded").value(1_020))
                .andExpect(jsonPath("$.data.mode").value("null-cache"));

        verify(experimentData).seed((Long) null);
    }

    @Test
    void seedForwardsAnExplicitTargetCount() throws Exception {
        when(experimentData.seed(250L)).thenReturn(new CacheSeedResponse(
                90_000L, 90_249L, 250L, 250L, 0L, 250L,
                200L, 60L, 90_999L, false, 260L, true, "off"));

        mockMvc.perform(post("/lab/cache/seed")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"count\":250}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.requestedProducts").value(250))
                .andExpect(jsonPath("$.data.inserted").value(250));

        verify(experimentData).seed(250L);
    }

    @Test
    void keysForwardsPrefixCursorAndLimit() throws Exception {
        when(experimentData.scanKeys("cache:product:", "42", 5))
                .thenReturn(new CacheKeysPageResponse("0",
                        Arrays.asList(new CacheKeysPageResponse.Item("cache:product:1", 120L)), 1, 5));

        mockMvc.perform(get("/lab/cache/keys")
                        .param("prefix", "cache:product:")
                        .param("cursor", "42")
                        .param("limit", "5")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.cursor").value("0"))
                .andExpect(jsonPath("$.data.limit").value(5))
                .andExpect(jsonPath("$.data.scanned").value(1))
                .andExpect(jsonPath("$.data.items[0].key").value("cache:product:1"))
                .andExpect(jsonPath("$.data.items[0].ttlSeconds").value(120));
    }

    @Test
    void keyDetailAcceptsEitherShapeAndResetEchoesTheFlag() throws Exception {
        when(experimentData.keyDetail("cache:product:1", null))
                .thenReturn(new CacheKeyDetailResponse("cache:product:1", 1L, true, true,
                        true, 300L, "{\"id\":1}", false, 8, 2_048));
        when(experimentData.reset(true))
                .thenReturn(new CacheExperimentResetResponse(3L, true));

        mockMvc.perform(post("/lab/cache/key")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"cache:product:1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.productId").value(1))
                .andExpect(jsonPath("$.data.truncated").value(false));

        mockMvc.perform(post("/lab/cache/reset")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"includeBusiness\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deletedKeys").value(3))
                .andExpect(jsonPath("$.data.includeBusiness").value(true));
    }

    @Test
    void stateExposesDefenseDatasetKeysBloomAndNullCacheWithoutSecrets() throws Exception {
        when(experimentData.state()).thenReturn(new CacheStateResponse(
                new CacheStateResponse.Defense("bloom", true),
                new CacheStateResponse.Breakdown("bloom", false, false, 200L, 2_000L, "db",
                        30L, 120L, 2, 100, 0L, 0L),
                new CacheStateResponse.Avalanche("bloom", false, 20.0D, false, 10L, 60L,
                        0L, 0L, false, 50L, 1_000L, 0L, 0L),
                new CacheStateResponse.Dataset(90_000L, 90_999L, 1_000L, 1_000L, 0L, 200L, 60L, 90_999L),
                new CacheStateResponse.Keys(true, 12L, 201L, 200L, true),
                new CacheStateResponse.Bloom("bloom:product-ids", true, 1_020L, 10_000L, 0.01D),
                new CacheStateResponse.NullCache("null:product:", 10L, 5L, 3L), true));

        String body = mockMvc.perform(get("/lab/cache/state").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data", hasKey("defense")))
                .andExpect(jsonPath("$.data", hasKey("breakdown")))
                .andExpect(jsonPath("$.data", hasKey("avalanche")))
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
                .andExpect(jsonPath("$.data.avalanche.strategy").value("bloom"))
                .andExpect(jsonPath("$.data.avalanche.ttlJitterActive").value(false))
                .andExpect(jsonPath("$.data.avalanche.ttlJitterPercent").value(20.0D))
                .andExpect(jsonPath("$.data.avalanche.l1StaleWindowSeconds").value(60))
                .andExpect(jsonPath("$.data.avalanche.limiterPermits").value(50))
                .andExpect(jsonPath("$.data.dataset.rowsInRange").value(1_000))
                .andExpect(jsonPath("$.data.dataset.missingRows").value(0))
                .andExpect(jsonPath("$.data.keys.redisReachable").value(true))
                .andExpect(jsonPath("$.data.keys.l2AvalancheKeysAlive").value(200))
                .andExpect(jsonPath("$.data.bloom.size").value(1_020))
                .andExpect(jsonPath("$.data.nullCache.liveMarkerKeys").value(3))
                .andExpect(jsonPath("$.data.l1Enabled").value(true))
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
                new CacheStateResponse.Avalanche("off", false, 20.0D, false, 10L, 60L,
                        0L, 0L, false, 50L, 1_000L, 0L, 0L),
                new CacheStateResponse.Dataset(90_000L, 90_999L, 1_000L, 0L, 1_000L, 200L, 60L, 90_999L),
                new CacheStateResponse.Keys(false, 0L, 0L, 0L, false),
                new CacheStateResponse.Bloom("bloom:product-ids", false, 0L, 10_000L, 0.01D),
                new CacheStateResponse.NullCache("null:product:", 10L, 5L, 0L), false));

        mockMvc.perform(get("/lab/cache/state").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.keys.redisReachable").value(false))
                .andExpect(jsonPath("$.data.bloom.initialised").value(false))
                .andExpect(content().string(not(containsString("secret"))));
    }
}
