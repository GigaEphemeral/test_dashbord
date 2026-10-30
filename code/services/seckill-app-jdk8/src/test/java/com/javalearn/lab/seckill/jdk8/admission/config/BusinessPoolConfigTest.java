package com.javalearn.lab.seckill.jdk8.admission.config;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.RejectedExecutionHandler;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * B4-01 wiring contract of {@link BusinessPoolConfig}: the four configured names
 * are exactly the JDK policies (no custom semantics), every configured number
 * reaches the real executor, and a refusal is counted before it is delegated.
 */
class BusinessPoolConfigTest {

    @Test
    void fourPolicyNamesMapToTheJdkPolicyClasses() {
        assertThat(BusinessPoolConfig.jdkPolicy("AbortPolicy"))
                .isInstanceOf(ThreadPoolExecutor.AbortPolicy.class);
        assertThat(BusinessPoolConfig.jdkPolicy("CallerRunsPolicy"))
                .isInstanceOf(ThreadPoolExecutor.CallerRunsPolicy.class);
        assertThat(BusinessPoolConfig.jdkPolicy("DiscardPolicy"))
                .isInstanceOf(ThreadPoolExecutor.DiscardPolicy.class);
        assertThat(BusinessPoolConfig.jdkPolicy("DiscardOldestPolicy"))
                .isInstanceOf(ThreadPoolExecutor.DiscardOldestPolicy.class);
    }

    @Test
    void theBeanUsesEveryExternalizedNumber() {
        BusinessPoolProperties properties = properties();
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        ThreadPoolExecutor executor = new BusinessPoolConfig()
                .businessExecutor(properties, registry);
        try {
            assertThat(executor.getCorePoolSize()).isEqualTo(2);
            assertThat(executor.getMaximumPoolSize()).isEqualTo(3);
            assertThat(executor.getQueue().remainingCapacity()).isEqualTo(7);
            assertThat(executor.getKeepAliveTime(TimeUnit.SECONDS)).isEqualTo(30L);
            assertThat(registry.find("executor.rejected").counter()).isNotNull();
            // ExecutorServiceMetrics binds the standard executor_* series too.
            assertThat(registry.find("executor.active").tag("name", "business").gauge())
                    .isNotNull();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void abortPolicyIncrementsTheRejectedCounterBeforeRefusing() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        Counter rejected = Counter.builder("executor.rejected")
                .tag("name", "business")
                .register(registry);
        RejectedExecutionHandler handler =
                BusinessPoolConfig.rejectionPolicy("AbortPolicy", rejected);
        ThreadPoolExecutor executor = new ThreadPoolExecutor(
                1, 1, 0L, TimeUnit.SECONDS, new ArrayBlockingQueue<Runnable>(1));
        try {
            assertThatThrownBy(() -> handler.rejectedExecution(() -> {
            }, executor))
                    .isInstanceOf(RejectedExecutionException.class);
            assertThat(rejected.count()).isEqualTo(1.0d);
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void anUnknownPolicyIsRefusedDefensively() {
        assertThatThrownBy(() -> BusinessPoolConfig.jdkPolicy("ExplodePolicy"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Unsupported rejection policy");
    }

    private static BusinessPoolProperties properties() {
        BusinessPoolProperties properties = new BusinessPoolProperties();
        properties.setCorePoolSize(2);
        properties.setMaxPoolSize(3);
        properties.setQueueCapacity(7);
        properties.setKeepAliveSeconds(30L);
        properties.setRejectionPolicy("AbortPolicy");
        properties.setThreadNamePrefix("business-pool-test");
        return properties;
    }
}
