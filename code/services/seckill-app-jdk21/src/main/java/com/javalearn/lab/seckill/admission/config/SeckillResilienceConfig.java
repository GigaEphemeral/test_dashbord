package com.javalearn.lab.seckill.admission.config;

import com.javalearn.lab.seckill.admission.app.SeckillMetrics;
import com.javalearn.lab.seckill.admission.app.SeckillResilienceGuard;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.ratelimiter.RateLimiterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Resolves the Resilience4j instances configured under
 * {@code resilience4j.ratelimiter.instances.seckill} and
 * {@code resilience4j.circuitbreaker.instances.seckillBackend}.
 *
 * <p>Resolving both instances eagerly also registers them with the Micrometer
 * publishers at startup, so {@code resilience4j_ratelimiter_available_permissions}
 * and {@code resilience4j_circuitbreaker_state} exist before the first request.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SeckillResilienceConfig {

    @Bean
    public SeckillResilienceGuard seckillResilienceGuard(RateLimiterRegistry rateLimiterRegistry,
                                                         CircuitBreakerRegistry circuitBreakerRegistry,
                                                         LabToggleService labToggleService,
                                                         SeckillMetrics seckillMetrics) {
        return new SeckillResilienceGuard(
                labToggleService,
                rateLimiterRegistry.rateLimiter(SeckillResilienceGuard.RATE_LIMITER_NAME),
                circuitBreakerRegistry.circuitBreaker(SeckillResilienceGuard.CIRCUIT_BREAKER_NAME),
                seckillMetrics);
    }
}
