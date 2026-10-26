package com.javalearn.lab.chaos.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.annotation.HttpGrafanaAnnotator;
import com.javalearn.lab.chaos.annotation.NoOpChaosAnnotator;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Wires the chaos action scheduler and the best-effort annotator.
 *
 * <p>The scheduler runs the per-action TTL revert, the watchdog sweep and
 * readiness probes. Threads are daemons so the JVM can always exit; the bean is
 * shut down with the context.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ChaosProperties.class)
public class ChaosConfig {

    /** TTL reverts + watchdog + bounded readiness probes run here. */
    static final int SCHEDULER_THREADS = 4;

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chaosActionScheduler() {
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "chaos-action-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(SCHEDULER_THREADS, factory);
    }

    @Bean
    public ChaosAnnotator chaosAnnotator(ChaosProperties properties, ObjectMapper objectMapper) {
        if (!properties.getAnnotation().isEnabled()) {
            return new NoOpChaosAnnotator();
        }
        return new HttpGrafanaAnnotator(properties.getAnnotation(), objectMapper);
    }
}
