package com.javalearn.lab.seckill;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * Full-context support with MySQL and RabbitMQ.
 *
 * <p>The async seckill listener starts with every full application context, so
 * a broker is required for all full-context tests — not only the seckill ones.
 * Tests that also need Redis extend {@link MySqlRedisIntegrationTestSupport}.</p>
 */
public abstract class MySqlRabbitIntegrationTestSupport extends MySqlIntegrationTestSupport {

    @DynamicPropertySource
    static void rabbitProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.rabbitmq.host", RabbitMqTestContainer::host);
        registry.add("spring.rabbitmq.port", RabbitMqTestContainer::amqpPort);
        registry.add("spring.rabbitmq.username", RabbitMqTestContainer::username);
        registry.add("spring.rabbitmq.password", RabbitMqTestContainer::password);
    }
}
