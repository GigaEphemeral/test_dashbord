package com.javalearn.lab.seckill.lab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.thread.ExecutorFaultTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.FaultTtlScheduler;
import com.javalearn.lab.seckill.lab.thread.FileLifecycleSnapshotSink;
import com.javalearn.lab.seckill.lab.thread.HttpGrafanaSnapshotAnnotator;
import com.javalearn.lab.seckill.lab.thread.JvmCommandExecutor;
import com.javalearn.lab.seckill.lab.thread.LabThreadProperties;
import com.javalearn.lab.seckill.lab.thread.LifecycleDemoMetrics;
import com.javalearn.lab.seckill.lab.thread.LifecycleDemoService;
import com.javalearn.lab.seckill.lab.thread.LifecycleSnapshotSink;
import com.javalearn.lab.seckill.lab.thread.ProcessJvmCommandExecutor;
import com.javalearn.lab.seckill.lab.thread.SnapshotAnnotator;
import com.javalearn.lab.seckill.lab.thread.ThreadFaultService;
import com.javalearn.lab.seckill.lab.thread.ThreadLabMetrics;
import com.javalearn.lab.seckill.lab.thread.ThreadSnapshotMetrics;
import com.javalearn.lab.seckill.lab.thread.ThreadSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Wires the fault-thread lab (Task M5-1), the single-thread lifecycle demo
 * (§9.2) and its snapshot bundle (§9.3).
 *
 * <p>The TTL scheduler is a daemon-backed {@link ExecutorFaultTtlScheduler} closed
 * on context shutdown, so a lab revert timer can never keep the JVM alive. Metrics
 * use the app's auto-configured {@link MeterRegistry}; the services read wall-clock
 * time from a system clock (tests inject a fixed clock directly instead).</p>
 *
 * <p>The snapshot executor spawns {@code jcmd} against this JVM's own pid; the
 * runtime image is a JDK (module Dockerfile) and {@code jcmd-path} is externalized,
 * so a test or a non-container run can point it anywhere. The Grafana annotator is
 * best-effort and OFF until a URL is supplied (design §9.3).</p>
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

    @Bean
    public ThreadSnapshotMetrics threadSnapshotMetrics(MeterRegistry meterRegistry) {
        return new ThreadSnapshotMetrics(meterRegistry);
    }

    /** Spawns {@code jcmd <own-pid> ...}: no container name, no docker exec at capture time. */
    @Bean
    public JvmCommandExecutor jvmCommandExecutor(LabThreadProperties properties) {
        return new ProcessJvmCommandExecutor(properties.getSnapshot().getJcmdPath(),
                ProcessHandle.current().pid());
    }

    @Bean
    public ThreadSnapshotService threadSnapshotService(LabThreadProperties properties,
                                                       @Value("${observability.instance}") String instanceId,
                                                       JvmCommandExecutor jvmCommandExecutor,
                                                       ObjectMapper objectMapper,
                                                       ThreadSnapshotMetrics threadSnapshotMetrics) {
        SnapshotAnnotator annotator = new HttpGrafanaSnapshotAnnotator(
                properties.getSnapshot().getAnnotation(), instanceId, objectMapper,
                threadSnapshotMetrics);
        return new ThreadSnapshotService(properties, instanceId, Clock.systemUTC(),
                jvmCommandExecutor, objectMapper, threadSnapshotMetrics, annotator);
    }

    /** The real §9.3 sink: every freeze writes the timestamped bundle. */
    @Bean
    public LifecycleSnapshotSink lifecycleSnapshotSink(ThreadSnapshotService snapshots) {
        return new FileLifecycleSnapshotSink(snapshots);
    }

    @Bean
    public LifecycleDemoMetrics lifecycleDemoMetrics(MeterRegistry meterRegistry) {
        return new LifecycleDemoMetrics(meterRegistry);
    }

    /**
     * The single-thread lifecycle demo (design §9.2). It reuses the fault lab's
     * daemon scheduler for auto-advance, and is bound to its metrics after
     * construction because the gauges read the live thread through the service.
     */
    @Bean
    public LifecycleDemoService lifecycleDemoService(LabThreadProperties properties,
                                                     FaultTtlScheduler scheduler,
                                                     LifecycleDemoMetrics metrics,
                                                     LifecycleSnapshotSink snapshotSink) {
        LifecycleDemoService service = new LifecycleDemoService(
                properties, Clock.systemUTC(), scheduler, metrics, snapshotSink);
        metrics.bind(service);
        return service;
    }
}
