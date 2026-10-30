package com.javalearn.lab.seckill.jdk8.support;

import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

/**
 * Singleton Redis 7.4.2 container shared by the JDK 8 integration tests.
 *
 * <p>Started once per Surefire JVM; Ryuk removes the container on exit. The
 * image tag matches the lab's compose Redis so the L2 text/TTL assertions run
 * against the same server version the demo uses. This mirrors the JDK 21 shell's
 * {@code RedisTestContainer}; the quick unit tests keep using {@link FakeRedis}
 * so they stay container-free.</p>
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
