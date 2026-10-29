package com.javalearn.lab.seckill.lab.api;

import com.javalearn.lab.seckill.admission.config.BusinessPoolProperties;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * B4-01 readback contract of {@code GET /lab/pool}: the view reports what the
 * running executor really uses (not the console's wishes) plus the live
 * counters, and {@code rejectedTotal} comes from the same
 * {@code executor.rejected} counter Prometheus scrapes.
 */
class LabBusinessPoolControllerTest {

    private ThreadPoolExecutor executor;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        executor = new ThreadPoolExecutor(
                2, 4, 30L, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(7),
                new ThreadPoolExecutor.AbortPolicy());
        BusinessPoolProperties properties = new BusinessPoolProperties();
        properties.setCorePoolSize(2);
        properties.setMaxPoolSize(4);
        properties.setQueueCapacity(7);
        properties.setKeepAliveSeconds(30L);
        properties.setRejectionPolicy("AbortPolicy");
        properties.setThreadNamePrefix("business-pool-test");

        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter rejected = Counter.builder("executor.rejected")
                .tag("name", "business")
                .register(registry);
        rejected.increment();

        mockMvc = MockMvcBuilders
                .standaloneSetup(new LabBusinessPoolController(
                        executor, properties, registry, "app-1"))
                .build();
    }

    @AfterEach
    void shutdownExecutor() throws InterruptedException {
        executor.shutdownNow();
        executor.awaitTermination(2L, TimeUnit.SECONDS);
    }

    @Test
    void viewReportsEffectiveParametersAndLiveCounters() throws Exception {
        mockMvc.perform(get("/lab/pool"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.instance").value("app-1"))
                .andExpect(jsonPath("$.data.corePoolSize").value(2))
                .andExpect(jsonPath("$.data.maxPoolSize").value(4))
                .andExpect(jsonPath("$.data.queueCapacity").value(7))
                .andExpect(jsonPath("$.data.keepAliveSeconds").value(30))
                .andExpect(jsonPath("$.data.rejectionPolicy").value("AbortPolicy"))
                .andExpect(jsonPath("$.data.activeCount").value(0))
                .andExpect(jsonPath("$.data.poolSize").value(0))
                .andExpect(jsonPath("$.data.largestPoolSize").value(0))
                .andExpect(jsonPath("$.data.queueSize").value(0))
                .andExpect(jsonPath("$.data.queueRemaining").value(7))
                .andExpect(jsonPath("$.data.completedTaskCount").value(0))
                .andExpect(jsonPath("$.data.rejectedTotal").value(1));
    }
}
