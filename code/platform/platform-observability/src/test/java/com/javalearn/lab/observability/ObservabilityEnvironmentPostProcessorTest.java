package com.javalearn.lab.observability;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.StandardEnvironment;

import static org.assertj.core.api.Assertions.assertThat;

class ObservabilityEnvironmentPostProcessorTest {

    private static final String EXPOSURE = "management.endpoints.web.exposure.include";
    private static final String HISTOGRAM =
            "management.metrics.distribution.percentiles-histogram.http.server.requests";
    private static final String SLO = "management.metrics.distribution.slo.http.server.requests";
    private static final String MINIMUM =
            "management.metrics.distribution.minimum-expected-value.http.server.requests";
    private static final String MAXIMUM =
            "management.metrics.distribution.maximum-expected-value.http.server.requests";

    private final ObservabilityEnvironmentPostProcessor postProcessor =
            new ObservabilityEnvironmentPostProcessor();

    @Test
    void addsObservabilityDefaults() {
        StandardEnvironment environment = new StandardEnvironment();

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(EXPOSURE))
                .isEqualTo("health,info,metrics,prometheus,threaddump,env");
        assertThat(environment.getProperty(HISTOGRAM)).isEqualTo("true");
        assertThat(environment.getProperty(SLO))
                .isEqualTo("50ms,100ms,200ms,300ms,500ms,800ms,1s,2s");
        assertThat(environment.getProperty(MINIMUM)).isEqualTo("50ms");
        assertThat(environment.getProperty(MAXIMUM)).isEqualTo("2s");
        assertThat(postProcessor.getOrder()).isEqualTo(Ordered.LOWEST_PRECEDENCE);
    }

    @Test
    void doesNotOverrideUserDefinedValues() {
        StandardEnvironment environment = new StandardEnvironment();
        environment.getPropertySources().addFirst(new MapPropertySource("test-user",
                Map.of(EXPOSURE, "health")));

        postProcessor.postProcessEnvironment(environment, new SpringApplication());

        assertThat(environment.getProperty(EXPOSURE)).isEqualTo("health");
        assertThat(environment.getProperty(HISTOGRAM)).isEqualTo("true");
    }
}
