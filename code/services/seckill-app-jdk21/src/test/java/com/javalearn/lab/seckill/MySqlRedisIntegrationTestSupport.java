package com.javalearn.lab.seckill;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-context support for tests that need both MySQL, RabbitMQ and the Redis
 * L2 cache.
 *
 * <p>The MySQL/RabbitMQ singletons come from
 * {@link MySqlRabbitIntegrationTestSupport}. Redis properties are registered
 * here so the Spring context (including the actuator Redis health indicator)
 * points at the Testcontainers instance rather than the compose hostname.</p>
 */
public abstract class MySqlRedisIntegrationTestSupport extends MySqlRabbitIntegrationTestSupport {

    @DynamicPropertySource
    static void redisProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", RedisTestContainer::host);
        registry.add("spring.data.redis.port", RedisTestContainer::port);
    }
}
