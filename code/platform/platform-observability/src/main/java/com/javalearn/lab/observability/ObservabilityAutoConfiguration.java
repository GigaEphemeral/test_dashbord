package com.javalearn.lab.observability;

import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;

@AutoConfiguration
@ConditionalOnClass(MeterRegistry.class)
@EnableConfigurationProperties(ObservabilityProperties.class)
public class ObservabilityAutoConfiguration {

    @Bean
    MeterRegistryCustomizer<MeterRegistry> commonTagsCustomizer(ObservabilityProperties properties,
            @Value("${spring.application.name:app}") String applicationName) {
        return registry -> registry.config().commonTags(
                "app", applicationName,
                "env", properties.getEnv(),
                "instance", properties.getInstance(),
                "jvm.version", System.getProperty("java.version"));
    }
}
