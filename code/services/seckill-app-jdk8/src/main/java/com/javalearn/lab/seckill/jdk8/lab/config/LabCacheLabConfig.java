package com.javalearn.lab.seckill.jdk8.lab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.jdk8.lab.api.LabInfoProvider;
import com.javalearn.lab.seckill.jdk8.lab.toggle.CacheDefenseModeService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.ConfigurableEnvironment;

/**
 * Cache-lab wiring shared by the three business slots: the runtime defense mode
 * store, the effective {@code app.lab.cache.*} read-back used by
 * {@code GET /lab/config}, and the non-secret identity view.
 *
 * <p>Split out of the JDK 21 {@code LabConfig} because this shell already wires
 * its own {@code LabToggleService} ({@code LabToggleConfig}) and its token
 * filter ({@code LabSnapshotConfig}); duplicating those beans here would make
 * the context ambiguous. The cache properties are registered here so every
 * {@code app.lab.cache.*} value from {@code application.yml} becomes bindable
 * whether or not a particular lab bean is used.</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LabCacheProperties.class)
public class LabCacheLabConfig {

    /**
     * Same per-instance, runtime-mutable semantics as {@code LabToggleService}:
     * seeded from configuration, switched through {@code POST /lab/cache/mode}.
     */
    @Bean
    public CacheDefenseModeService cacheDefenseModeService(LabCacheProperties properties) {
        return new CacheDefenseModeService(properties);
    }

    /**
     * B0-04 effective-value read-back for {@code GET /lab/config}. Built from the
     * running {@link ConfigurableEnvironment}, so the payload is the wiring this
     * instance actually resolved (no second table of defaults that could drift).
     */
    @Bean
    public CacheEffectiveConfigReader cacheEffectiveConfigReader(
            ConfigurableEnvironment environment) {
        return new CacheEffectiveConfigReader(environment);
    }

    @Bean
    public LabInfoProvider labInfoProvider(@Value("${spring.application.name}") String app,
                                           @Value("${observability.env}") String env,
                                           @Value("${observability.instance}") String instance) {
        return new LabInfoProvider(app, env, instance);
    }
}
