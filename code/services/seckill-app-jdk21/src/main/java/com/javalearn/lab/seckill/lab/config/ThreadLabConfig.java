package com.javalearn.lab.seckill.lab.config;

import com.javalearn.lab.seckill.lab.thread.ExecutorFaultTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.FaultTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties;
import com.javalearn.lab.seckill.lab.thread.ThreadFaultService;
import com.javalearn.lab.seckill.lab.thread.ThreadLabMetrics;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the fault-thread lab (Task M5-1). Kept as its own {@code @Configuration}
 * so the shared {@link LabConfig} is untouched and the merge surface stays small.
 *
 * <p>The TTL scheduler is a daemon-backed {@link ExecutorFaultTtlScheduler} closed
 * on context shutdown, so a lab revert timer can never keep the JVM alive. Metrics
 * use the app's auto-configured {@link MeterRegistry}; the service reads wall-clock
 * time from a system clock (tests inject a fixed clock directly instead).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabThreadProperties.class)
public class ThreadLabConfig {

    @Bean
    public FaultTtlScheduler faultTtlScheduler() {
        return new ExecutorFaultTtlScheduler();
    }

    @Bean
    public ThreadLabMetrics threadLabMetrics(MeterRegistry meterRegistry) {
        return new ThreadLabMetrics(meterRegistry);
    }

    @Bean
    public ThreadFaultService threadFaultService(LabThreadProperties properties,
                                                 FaultTtlScheduler scheduler,
                                                 ThreadLabMetrics metrics) {
        return new ThreadFaultService(properties, Clock.systemUTC(), scheduler, metrics);
    }
}
