package com.javalearn.lab.seckill.lab.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.lab.api.LabInfoProvider;
import com.javalearn.lab.seckill.lab.auth.LabTokenFilter;
import com.javalearn.lab.seckill.lab.toggle.CacheDefenseModeService;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * Wires the runtime toggle store, the cache defense mode store and the
 * non-secret metadata shown by {@code GET /lab/config}. The token filter is
 * registered only for {@code /lab/*} through a {@link FilterRegistrationBean}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({LabProperties.class, LabToggleProperties.class, LabCacheProperties.class})
public class LabConfig {

    @Bean
    public LabToggleService labToggleService(LabToggleProperties properties) {
        return new LabToggleService(properties);
    }

    /**
     * Same per-instance, runtime-mutable semantics as {@link LabToggleService}:
     * seeded from configuration, switched through {@code POST /lab/cache/mode}.
     */
    @Bean
    public CacheDefenseModeService cacheDefenseModeService(LabCacheProperties properties) {
        return new CacheDefenseModeService(properties);
    }

    @Bean
    public LabInfoProvider labInfoProvider(@Value("${spring.application.name}") String app,
                                           @Value("${observability.env}") String env,
                                           @Value("${observability.instance}") String instance) {
        return new LabInfoProvider(app, env, instance);
    }

    /**
     * Restricts the token filter to the internal lab paths. Registering it here
     * instead of annotating the filter keeps MVC slice tests independent and
     * guarantees {@code /api/**} never runs lab authentication.
     */
    @Bean
    public FilterRegistrationBean<LabTokenFilter> labTokenFilterRegistration(
            LabProperties properties, ObjectMapper objectMapper) {
        FilterRegistrationBean<LabTokenFilter> registration =
                new FilterRegistrationBean<>(new LabTokenFilter(properties, objectMapper));
        registration.addUrlPatterns("/lab/*");
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 20);
        return registration;
    }
}
