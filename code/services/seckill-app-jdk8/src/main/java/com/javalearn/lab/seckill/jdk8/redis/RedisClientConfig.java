package com.javalearn.lab.seckill.jdk8.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode-aware Spring Data Redis / Lettuce connection factory (parity with the
 * JDK 21 {@code RedisClientConfig}).
 *
 * <p>Declaring this bean backs off Boot's auto-configured factory, so ONE
 * switch ({@code app.redis.mode}, from the {@code REDIS_MODE} env) selects the
 * endpoint style for the StringRedisTemplate used by the seckill stock,
 * idempotency and request stores. The Redisson client branches on the same
 * property (see {@code SeckillRedissonConfig}), which keeps the two stacks
 * moving together.</p>
 *
 * <p>Endpoints stay in the existing {@code spring.redis.*} keys: for the
 * standalone mode that is {@code spring.redis.host/port}; for the sentinel mode
 * it is {@code spring.redis.sentinel.master/nodes/password}. Those Sentinel keys
 * are supplied by the environment and deliberately never defaulted in
 * application.yml: Boot's own connection configuration builds a
 * {@code RedisSentinelConfiguration} as soon as such a key exists, so an empty
 * master name would break every startup, demo included. A missing
 * master/nodes list is a startup failure instead of a silent fallback to
 * {@code redis:6379}.</p>
 *
 * <p>Like the auto-configured factory, the connection is only opened on first
 * use: a Redis or Sentinel outage must not block boot (Task 14 degradation
 * contract).</p>
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RedisAccessProperties.class)
public class RedisClientConfig {

    private static final Logger log = LoggerFactory.getLogger(RedisClientConfig.class);

    @Bean
    public LettuceConnectionFactory redisConnectionFactory(RedisProperties redisProperties,
                                                           RedisAccessProperties access) {
        // Logged once per instance; the ha drill reads this line to prove which
        // mode the container came up in.
        log.info("Redis access mode: {}", access.getMode());
        return buildConnectionFactory(redisProperties, access);
    }

    /**
     * Pure builder, exposed for tests: binds the factory WITHOUT calling
     * afterPropertiesSet, so no network is touched at assertion time. The
     * TYPE-SPECIFIC two-argument LettuceConnectionFactory constructors are used
     * so the ACTIVE configuration always reflects the mode AND the
     * {@code spring.redis} client timeouts.
     */
    static LettuceConnectionFactory buildConnectionFactory(RedisProperties redisProperties,
                                                           RedisAccessProperties access) {
        if (access.isSentinel()) {
            return new LettuceConnectionFactory(
                    sentinelConfiguration(redisProperties),
                    clientConfiguration(redisProperties));
        }
        return new LettuceConnectionFactory(
                standaloneConfiguration(redisProperties),
                clientConfiguration(redisProperties));
    }

    /**
     * Declaring our own factory backs off Boot's RedisLettuceConnectionConfiguration,
     * which would otherwise translate {@code spring.redis.timeout} and
     * {@code lettuce.shutdown-timeout} into Lettuce client options. Each value is
     * applied ONLY when configured, so unset behaviour keeps the framework
     * defaults. Still not honoured here (framework limitation at this scope):
     * {@code spring.redis.connect-timeout} and {@code spring.redis.lettuce.pool.*}.
     */
    private static LettuceClientConfiguration clientConfiguration(RedisProperties redisProperties) {
        LettuceClientConfiguration.LettuceClientConfigurationBuilder builder =
                LettuceClientConfiguration.builder();
        if (redisProperties.getTimeout() != null) {
            builder.commandTimeout(redisProperties.getTimeout());
        }
        RedisProperties.Lettuce lettuce = redisProperties.getLettuce();
        if (lettuce != null && lettuce.getShutdownTimeout() != null
                && !lettuce.getShutdownTimeout().isZero()) {
            // Same rule Boot itself applies (RedisLettuceConnectionConfiguration).
            builder.shutdownTimeout(lettuce.getShutdownTimeout());
        }
        return builder.build();
    }

    private static RedisStandaloneConfiguration standaloneConfiguration(
            RedisProperties redisProperties) {
        RedisStandaloneConfiguration standalone = new RedisStandaloneConfiguration(
                redisProperties.getHost(), redisProperties.getPort());
        standalone.setDatabase(redisProperties.getDatabase());
        if (StringUtils.hasText(redisProperties.getPassword())) {
            standalone.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        return standalone;
    }

    private static RedisSentinelConfiguration sentinelConfiguration(
            RedisProperties redisProperties) {
        RedisProperties.Sentinel sentinel = redisProperties.getSentinel();
        String masterName = sentinel == null ? null : sentinel.getMaster();
        if (!StringUtils.hasText(masterName)) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires a master NAME"
                    + " (spring.redis.sentinel.master, env SPRING_REDIS_SENTINEL_MASTER); the"
                    + " app must discover the master by name and never fall back to the"
                    + " standalone spring.redis.host address");
        }
        if (sentinel.getNodes() == null || sentinel.getNodes().isEmpty()) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires at least one Sentinel"
                    + " endpoint (spring.redis.sentinel.nodes, env"
                    + " SPRING_REDIS_SENTINEL_NODES as host:port,...); without it there is"
                    + " nothing to discover the master from and starting with the hardcoded"
                    + " standalone host is forbidden");
        }

        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
        configuration.master(masterName);
        for (String node : sentinel.getNodes()) {
            configuration.sentinel(parseNode(node));
        }
        configuration.setDatabase(redisProperties.getDatabase());
        // spring.redis.password authenticates against the DATA nodes
        // (requirepass); sentinel.password only against the Sentinel ports.
        if (StringUtils.hasText(redisProperties.getPassword())) {
            configuration.setPassword(RedisPassword.of(redisProperties.getPassword()));
        }
        if (StringUtils.hasText(sentinel.getPassword())) {
            configuration.setSentinelPassword(RedisPassword.of(sentinel.getPassword()));
        }
        log.info("Redis mode=sentinel: discovering master '{}' through {} Sentinel endpoints"
                        + " {} (standalone host key ignored, no address hardcoded)",
                masterName, sentinel.getNodes().size(), sentinel.getNodes());
        return configuration;
    }

    /**
     * Sentinel endpoints are {@code host:port} — the port is part of the
     * externalised value, never a Java default, so a portless entry fails loudly
     * instead of silently using 26379.
     */
    static RedisNode parseNode(String node) {
        String trimmed = node.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalStateException("Invalid Sentinel endpoint '" + node
                    + "'; spring.redis.sentinel.nodes entries must be host:port");
        }
        try {
            return new RedisNode(trimmed.substring(0, colon),
                    Integer.parseInt(trimmed.substring(colon + 1)));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid Sentinel endpoint '" + node
                    + "'; expected host:port", ex);
        }
    }

    /** Host/Port view of the configured Sentinel nodes, for tests. */
    static List<String> sentinelAddresses(RedisSentinelConfiguration configuration) {
        List<String> addresses = new ArrayList<>();
        for (RedisNode node : configuration.getSentinels()) {
            addresses.add(node.getHost() + ":" + node.getPort());
        }
        return addresses;
    }
}
