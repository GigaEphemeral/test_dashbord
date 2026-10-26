package com.javalearn.lab.seckill;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis 7.4.2 container shared by the cache unit/IT tests.
 *
 * <p>Started once per Surefire JVM (same rationale as
 * {@link MySqlIntegrationTestSupport}: Spring context caching keeps a fixed
 * host/port for every test class, and Ryuk removes the container on exit).</p>
 */
public final class RedisTestContainer {

    private static final int REDIS_PORT = 6379;

    private static final GenericContainer<?> REDIS =
            new GenericContainer<>(DockerImageName.parse("redis:7.4.2-alpine"))
                    .withExposedPorts(REDIS_PORT)
                    .waitingFor(Wait.forListeningPort());

    static {
        REDIS.start();
    }

    private RedisTestContainer() {
    }

    public static String host() {
        return REDIS.getHost();
    }

    public static int port() {
        return REDIS.getMappedPort(REDIS_PORT);
    }

    /** A template connected to the shared container; caller owns the lifecycle. */
    public static StringRedisTemplate newStringRedisTemplate() {
        LettuceConnectionFactory connectionFactory = new LettuceConnectionFactory(host(), port());
        connectionFactory.afterPropertiesSet();
        StringRedisTemplate template = new StringRedisTemplate(connectionFactory);
        template.afterPropertiesSet();
        return template;
    }
}
