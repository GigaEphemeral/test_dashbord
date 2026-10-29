package com.javalearn.lab.seckill.lab.api;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.catalog.api.ProductResponse;
import com.javalearn.lab.seckill.catalog.cache.TwoLevelCacheManager;
import com.javalearn.lab.seckill.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.lab.config.LabProperties;
import com.javalearn.lab.seckill.lab.config.LabToggleProperties;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import com.javalearn.lab.seckill.web.GlobalExceptionHandler;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of {@code /lab/cache/l1} (B3-01): GET/POST share one shape,
 * POST adds {@code clearedEntries}, the same value clears nothing, and missing
 * or non-boolean bodies are {@code 40001}.
 */
class LabCacheL1ControllerTest {

    private static final String TOKEN = "test-lab-token";
    private static final ProductResponse PRODUCT = new ProductResponse(
            1L, "键盘", new BigDecimal("1.00"), "ON_SALE", "a");

    private final Map<String, String> l2 = new LinkedHashMap<>();

    private MockMvc mockMvc;
    private TwoLevelCacheManager manager;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redis.opsForValue()).thenReturn(operations);
        when(operations.get(anyString()))
                .thenAnswer(invocation -> l2.get(invocation.getArgument(0, String.class)));
        doAnswer(invocation -> {
            l2.put(invocation.getArgument(0, String.class),
                    invocation.getArgument(1, String.class));
            return null;
        }).when(operations).set(anyString(), anyString(), any(Duration.class));

        manager = new TwoLevelCacheManager(redis, new ObjectMapper(),
                new SimpleMeterRegistry(), toggles());
        manager.getCache(TwoLevelCacheManager.PRODUCT_CACHE).put(1L, PRODUCT);
        manager.getCache("other").put(2L, "b");

        LabProperties labProperties = new LabProperties();
        labProperties.setToken(TOKEN);
        mockMvc = MockMvcBuilders
                .standaloneSetup(new LabCacheL1Controller(manager, "app-1"))
                .addFilters(new LabTokenFilter(labProperties, new ObjectMapper()))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void getExposesEnabledCacheCountAndPerCacheSizes() throws Exception {
        mockMvc.perform(get("/lab/cache/l1").header(LabTokenFilter.TOKEN_HEADER, TOKEN))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.cacheCount").value(2))
                .andExpect(jsonPath("$.data.caches", hasSize(2)))
                .andExpect(jsonPath("$.data.caches[0].name").value("other"))
                .andExpect(jsonPath("$.data.caches[0].size").value(1))
                .andExpect(jsonPath("$.data.caches[1].name").value("product"))
                .andExpect(jsonPath("$.data.caches[1].size").value(1))
                .andExpect(jsonPath("$.data.instance").value("app-1"));
    }

    @Test
    void postDisablesClearsEntriesAndRepeatsIdempotently() throws Exception {
        mockMvc.perform(post("/lab/cache/l1")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.cacheCount").value(2))
                .andExpect(jsonPath("$.data.caches[0].size").value(0))
                .andExpect(jsonPath("$.data.caches[1].size").value(0))
                .andExpect(jsonPath("$.data.clearedEntries").value(2));

        assertThat(manager.isL1Enabled()).isFalse();
        // Disabling must not delete L2/Redis values.
        assertThat(l2).containsKeys("cache:product:1", "cache:other:2");

        mockMvc.perform(post("/lab/cache/l1")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.clearedEntries").value(0));

        mockMvc.perform(post("/lab/cache/l1")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":true}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(true))
                .andExpect(jsonPath("$.data.clearedEntries").value(0));
        assertThat(manager.isL1Enabled()).isTrue();
    }

    @Test
    void missingOrNonBooleanBodyIsAFourZeroZeroZeroOne() throws Exception {
        mockMvc.perform(post("/lab/cache/l1")
                        .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                        .contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("Request body is required"));

        for (String body : new String[] {
                "{}", "{\"enabled\":null}", "{\"enabled\":\"on\"}", "{\"enabled\":1}"}) {
            mockMvc.perform(post("/lab/cache/l1")
                            .header(LabTokenFilter.TOKEN_HEADER, TOKEN)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("40001"))
                    .andExpect(jsonPath("$.message")
                            .value("Field 'enabled' is required and must be a boolean"));
        }

        assertThat(manager.isL1Enabled()).isTrue();
    }

    @Test
    void everyEndpointRequiresTheLabToken() throws Exception {
        mockMvc.perform(get("/lab/cache/l1"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("40101"));
        mockMvc.perform(post("/lab/cache/l1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"enabled\":false}"))
                .andExpect(status().isUnauthorized());
        assertThat(manager.isL1Enabled()).isTrue();
    }

    private static LabToggleService toggles() {
        LabToggleProperties properties = new LabToggleProperties();
        properties.setLock(true);
        properties.setIdempotency(true);
        properties.setMq(true);
        properties.setRatelimit(true);
        properties.setCircuitbreaker(true);
        properties.setCache(true);
        return new LabToggleService(properties);
    }
}
