package com.javalearn.lab.seckill.admission.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.javalearn.lab.seckill.admission.app.SeckillAdmissionService;
import com.javalearn.lab.seckill.admission.app.SeckillIdempotencyStore;
import com.javalearn.lab.seckill.admission.app.SeckillLockGuard;
import com.javalearn.lab.seckill.admission.app.SeckillMessagePublisher;
import com.javalearn.lab.seckill.admission.app.SeckillMetrics;
import com.javalearn.lab.seckill.admission.app.SeckillOrderConsumer;
import com.javalearn.lab.seckill.admission.app.SeckillRequestStore;
import com.javalearn.lab.seckill.admission.app.SeckillResilienceGuard;
import com.javalearn.lab.seckill.admission.app.SeckillStockStore;
import com.javalearn.lab.seckill.admission.infra.RabbitSeckillMessagePublisher;
import com.javalearn.lab.seckill.admission.infra.RedissonSeckillLockGuard;
import com.javalearn.lab.seckill.admission.infra.RedisSeckillIdempotencyStore;
import com.javalearn.lab.seckill.admission.infra.RedisSeckillRequestStore;
import com.javalearn.lab.seckill.admission.infra.RedisSeckillStockStore;
import com.javalearn.lab.seckill.admission.infra.SeckillStockSeeder;
import com.javalearn.lab.seckill.lab.toggle.LabToggleService;
import com.javalearn.lab.seckill.order.app.OrderApplicationService;
import com.javalearn.lab.seckill.order.app.OrderQueryService;
import io.micrometer.core.instrument.MeterRegistry;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Duration;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Wires the seckill admission ports to their Redis/Redisson/RabbitMQ
 * implementations. All values are read from {@link SeckillProperties}, which is
 * bound from {@code app.seckill.*}.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(SeckillProperties.class)
public class SeckillConfig {

    @Bean
    public SeckillMetrics seckillMetrics(MeterRegistry meterRegistry) {
        return new SeckillMetrics(meterRegistry);
    }

    @Bean
    public SeckillLockGuard seckillLockGuard(RedissonClient seckillRedissonClient,
                                             SeckillProperties properties) {
        return new RedissonSeckillLockGuard(
                seckillRedissonClient,
                properties.getLock().getKeyPrefix(),
                properties.getLock().getWaitMs(),
                properties.getLock().getLeaseMs());
    }

    @Bean
    public SeckillStockStore seckillStockStore(StringRedisTemplate redisTemplate,
                                               SeckillProperties properties) {
        return new RedisSeckillStockStore(
                redisTemplate,
                properties.getStock().getKeyPrefix(),
                properties.getStock().getLuaPath());
    }

    @Bean
    public SeckillIdempotencyStore seckillIdempotencyStore(StringRedisTemplate redisTemplate,
                                                           SeckillProperties properties) {
        return new RedisSeckillIdempotencyStore(
                redisTemplate,
                properties.getIdempotency().getKeyPrefix(),
                Duration.ofSeconds(properties.getIdempotency().getTtlSeconds()));
    }

    @Bean
    public SeckillRequestStore seckillRequestStore(StringRedisTemplate redisTemplate,
                                                   ObjectMapper objectMapper,
                                                   SeckillProperties properties) {
        return new RedisSeckillRequestStore(
                redisTemplate,
                objectMapper,
                properties.getRequest().getKeyPrefix(),
                Duration.ofSeconds(properties.getRequest().getTtlSeconds()));
    }

    @Bean
    public SeckillMessagePublisher seckillMessagePublisher(RabbitTemplate rabbitTemplate,
                                                           SeckillProperties properties) {
        return new RabbitSeckillMessagePublisher(
                rabbitTemplate,
                properties.getMq().getExchange(),
                properties.getMq().getRoutingKey());
    }

    @Bean
    public SeckillAdmissionService seckillAdmissionService(
            SeckillLockGuard seckillLockGuard,
            SeckillIdempotencyStore seckillIdempotencyStore,
            SeckillStockStore seckillStockStore,
            SeckillRequestStore seckillRequestStore,
            SeckillMessagePublisher seckillMessagePublisher,
            OrderApplicationService orderApplicationService,
            SeckillMetrics seckillMetrics,
            LabToggleService labToggleService,
            SeckillResilienceGuard seckillResilienceGuard) {
        return new SeckillAdmissionService(
                seckillLockGuard,
                seckillIdempotencyStore,
                seckillStockStore,
                seckillRequestStore,
                seckillMessagePublisher,
                orderApplicationService,
                seckillMetrics,
                labToggleService,
                seckillResilienceGuard);
    }

    @Bean
    public SeckillOrderConsumer seckillOrderConsumer(
            SeckillProperties properties,
            SeckillRequestStore seckillRequestStore,
            SeckillStockStore seckillStockStore,
            OrderApplicationService orderApplicationService,
            OrderQueryService orderQueryService,
            SeckillMetrics seckillMetrics,
            @Qualifier("businessExecutor") ThreadPoolExecutor businessExecutor) {
        return new SeckillOrderConsumer(
                properties,
                seckillRequestStore,
                seckillStockStore,
                orderApplicationService,
                orderQueryService,
                seckillMetrics,
                businessExecutor);
    }

    @Bean
    public SeckillStockSeeder seckillStockSeeder(SeckillProperties properties,
                                                 JdbcTemplate jdbcTemplate,
                                                 SeckillStockStore seckillStockStore) {
        return new SeckillStockSeeder(properties, jdbcTemplate, seckillStockStore);
    }
}
