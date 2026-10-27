package com.javalearn.lab.seckill.jdk8.lab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.HttpGrafanaSnapshotAnnotator;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.JvmCommandExecutor;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.ProcessJvmCommandExecutor;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.SnapshotAnnotator;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.SnapshotMetrics;
import com.javalearn.lab.seckill.jdk8.lab.snapshot.ThreadSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

import java.time.Clock;

/**
 * Wires the JDK 8 snapshot bundle (design §9.3): properties, the
 * {@code jstack}/{@code jmap} executor, the bundle service, its metrics and the
 * lab token filter.
 *
 * <p>The executor spawns the JDK tools against this JVM's own pid; the runtime
 * image is a JDK (module Dockerfile) and both tool paths are externalized, so a
 * non-container run can point them anywhere. The token filter is registered for
 * {@code /lab/*} only, mirroring the JDK 21 {@code LabConfig} so MVC slice tests
 * and {@code /api/**} never load it.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LabProperties.class, SnapshotProperties.class})
public class LabSnapshotConfig {

    @Bean
    public SnapshotMetrics snapshotMetrics(MeterRegistry meterRegistry) {
        return new SnapshotMetrics(meterRegistry);
    }

    /** Complete argv per call (jstack/jmap put the pid last), no docker exec at capture time. */
    @Bean
    public JvmCommandExecutor jvmCommandExecutor() {
        return new ProcessJvmCommandExecutor();
    }

    @Bean
    public ThreadSnapshotService threadSnapshotService(SnapshotProperties properties,
                                                       @Value("${observability.instance}") String instanceId,
                                                       JvmCommandExecutor jvmCommandExecutor,
                                                       ObjectMapper objectMapper,
                                                       SnapshotMetrics snapshotMetrics) {
        // Same best-effort Grafana marker as the JDK 21 shell, but over
        // HttpURLConnection (Java 8 has no java.net.http). A blank URL is OFF.
        SnapshotAnnotator annotator = new HttpGrafanaSnapshotAnnotator(
                properties.getAnnotation(), instanceId, objectMapper, snapshotMetrics);
        return new ThreadSnapshotService(properties, instanceId,
                ThreadSnapshotService.currentPid(), Clock.systemUTC(),
                jvmCommandExecutor, objectMapper, snapshotMetrics, annotator);
    }

    /** Registers the token gate for {@code /lab/*} only; {@code /api/**} stays public. */
    @Bean
    public FilterRegistrationBean<LabTokenFilter> labTokenFilterRegistration(
            LabProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<LabTokenFilter> registration =
                new FilterRegistrationBean<LabTokenFilter>(new LabTokenFilter(properties, objectMapper));
        registration.addUrlPatterns("/lab/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
