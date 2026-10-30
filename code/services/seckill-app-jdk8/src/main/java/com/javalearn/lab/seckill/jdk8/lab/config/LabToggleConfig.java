package com.javalearn.lab.seckill.jdk8.lab.config;

import com.javalearn.lab.seckill.jdk8.lab.toggle.LabToggleService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Wires the per-instance runtime toggles into the JDK 8 shell so the async
 * seckill admission reads the same {@code app.toggles.*} seeds as the JDK 21
 * peers. The HTTP toggle surface is deliberately not duplicated here.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabToggleProperties.class)
public class LabToggleConfig {

    @Bean
    public LabToggleService labToggleService(LabToggleProperties properties) {
        return new LabToggleService(properties);
    }
}
