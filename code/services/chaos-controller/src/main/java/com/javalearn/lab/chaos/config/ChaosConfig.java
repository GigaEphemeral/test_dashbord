package com.javalearn.lab.chaos.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.chaos.annotation.ChaosAnnotator;
import com.javalearn.lab.chaos.annotation.HttpGrafanaAnnotator;
import com.javalearn.lab.chaos.annotation.NoOpChaosAnnotator;
import com.javalearn.lab.chaos.cluster.MysqlClusterMonitor;
import com.javalearn.lab.chaos.gateway.ContainerGateway;
import com.javalearn.lab.chaos.toxiproxy.HttpToxiproxyGateway;
import com.javalearn.lab.chaos.toxiproxy.ToxiproxyGateway;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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

    @Bean(destroyMethod = "shutdownNow")
    public ScheduledExecutorService chaosActionScheduler(ChaosProperties properties) {
        int threads = Math.max(1, properties.getSchedulerThreads());
        AtomicInteger sequence = new AtomicInteger();
        ThreadFactory factory = runnable -> {
            Thread thread = new Thread(runnable, "chaos-action-" + sequence.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
        return Executors.newScheduledThreadPool(threads, factory);
    }

    @Bean
    public ChaosAnnotator chaosAnnotator(ChaosProperties properties, ObjectMapper objectMapper) {
        if (!properties.getAnnotation().isEnabled()) {
            return new NoOpChaosAnnotator();
        }
        return new HttpGrafanaAnnotator(properties.getAnnotation(), objectMapper);
    }

    /**
     * Toxiproxy control client. Always created: the service owns the
     * {@code enabled} switch, so a disabled layer answers 503/50301 instead of
     * failing context startup when the {@code chaos-proxy} profile is absent.
     */
    @Bean
    public ToxiproxyGateway toxiproxyGateway(ChaosProperties properties, ObjectMapper objectMapper) {
        return new HttpToxiproxyGateway(properties.getToxiproxy(), objectMapper);
    }

    /**
     * MySQL cluster membership probe (design §8.3 / D11). Only created when the
     * Docker gateway exists; the monitor owns a dedicated single-thread scheduler
     * so a hung exec can never delay TTL reverts or readiness probes.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(name = "lab.docker.enabled", havingValue = "true", matchIfMissing = true)
    public MysqlClusterMonitor mysqlClusterMonitor(ContainerGateway gateway,
                                                   ChaosProperties properties,
                                                   MeterRegistry meterRegistry) {
        return new MysqlClusterMonitor(gateway, properties, meterRegistry);
    }
}
