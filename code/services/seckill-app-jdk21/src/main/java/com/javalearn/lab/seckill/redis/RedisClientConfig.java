package com.javalearn.lab.seckill.redis;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConfiguration;
import org.springframework.data.redis.connection.RedisNode;
import org.springframework.data.redis.connection.RedisPassword;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Mode-aware Spring Data Redis / Lettuce connection factory (Task M3-1b).
 *
 * <p>Declaring this bean backs off Boot's auto-configured factory, so ONE
 * switch ({@code app.redis.mode}, from the {@code REDIS_MODE} env) selects the
 * endpoint style for the StringRedisTemplate used by the product cache L2, the
 * seckill stores, login tokens and the penetration guards. The Redisson client
 * branches on the same property (see {@code SeckillRedissonConfig}), which is
 * what keeps the two stacks moving together.</p>
 *
 * <p>Endpoints stay in the existing {@code spring.data.redis.*} keys — for the
 * standalone mode that is {@code spring.data.redis.host/port} (unchanged, so
 * the Testcontainers dynamic properties keep working); for the sentinel mode
 * it is {@code spring.data.redis.sentinel.master/nodes/password}, each node
 * entry carrying its own port (Boot 3.5 exposes no sentinel.port property).
 * Those Sentinel keys are supplied by the environment (compose.ha.yml maps
 * {@code REDIS_HA_MASTER_NAME}/{@code REDIS_SENTINEL_NODES} onto
 * {@code SPRING_DATA_REDIS_SENTINEL_MASTER}/{@code _NODES}) and deliberately
 * never defaulted in application.yml: Boot's own
 * {@code RedisConnectionConfiguration#determineMode()} builds a
 * {@code RedisSentinelConfiguration} as soon as such a key exists, so an empty
 * master name would break every startup, dev included. The master is discovered
 * BY NAME at runtime: in sentinel mode the address is never read from the
 * standalone host key, and a missing master/nodes list is a startup failure
 * instead of a silent fallback to {@code redis:6379}.</p>
 *
 * <p>Like the auto-configured factory, the connection is only opened on first
 * use: a Redis or Sentinel outage must not block boot (Task 14 degradation
 * contract, RedisOutageCacheIT).</p>
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
     * Sentinel-mode only: logs the master address the Sentinels report for the
     * configured master NAME, which is the discovery evidence the failover drill
     * needs in the app logs.
     */
    @Bean
    @ConditionalOnProperty(
            name = "app.redis.mode", havingValue = RedisAccessProperties.MODE_SENTINEL)
    public SentinelMasterDiscoveryReporter sentinelMasterDiscoveryReporter(
            LettuceConnectionFactory redisConnectionFactory,
            RedisProperties redisProperties,
            RedisAccessProperties access) {
        return new SentinelMasterDiscoveryReporter(
                redisConnectionFactory, redisProperties, access);
    }

    /**
     * Pure builder, exposed for tests: binds the factory WITHOUT calling
     * afterPropertiesSet, so no network is touched at assertion time. The
     * type-specific LettuceConnectionFactory constructors are used so the
     * ACTIVE configuration (isRedisSentinelAware()/getSentinelConfiguration(),
     * the one the connection provider is built from) always reflects the mode;
     * note the factory still exposes a vestigial default standalone object in
     * sentinel mode, which is why tests assert the active side, not that getter.
     */
    static LettuceConnectionFactory buildConnectionFactory(RedisProperties redisProperties,
                                                           RedisAccessProperties access) {
        if (access.isSentinel()) {
            return new LettuceConnectionFactory(
                    sentinelConfiguration(redisProperties, access));
        }
        return new LettuceConnectionFactory(standaloneConfiguration(redisProperties));
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

    private static RedisSentinelConfiguration sentinelConfiguration(RedisProperties redisProperties,
                                                                     RedisAccessProperties access) {
        RedisProperties.Sentinel sentinel = redisProperties.getSentinel();
        String masterName = sentinel == null ? null : sentinel.getMaster();
        if (!StringUtils.hasText(masterName)) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires a master NAME"
                    + " (spring.data.redis.sentinel.master, env SPRING_DATA_REDIS_SENTINEL_MASTER,"
                    + " mapped by compose.ha.yml from REDIS_HA_MASTER_NAME); the app must"
                    + " discover the master by name and never fall back to the standalone"
                    + " spring.data.redis.host address");
        }
        if (sentinel.getNodes() == null || sentinel.getNodes().isEmpty()) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires at least one Sentinel"
                    + " endpoint (spring.data.redis.sentinel.nodes, env"
                    + " SPRING_DATA_REDIS_SENTINEL_NODES as host:port,..., mapped from"
                    + " REDIS_SENTINEL_NODES); without it there is nothing to discover the"
                    + " master from and starting with the hardcoded standalone host is forbidden");
        }

        RedisSentinelConfiguration configuration = new RedisSentinelConfiguration();
        configuration.master(masterName);
        for (String node : sentinel.getNodes()) {
            configuration.sentinel(parseNode(node));
        }
        configuration.setDatabase(redisProperties.getDatabase());
        // spring.data.redis.password authenticates against the DATA nodes
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
     * externalised value (REDIS_SENTINEL_NODES), never a Java default, so a
     * portless entry fails loudly instead of silently using 26379.
     */
    static RedisNode parseNode(String node) {
        String trimmed = node.trim();
        int colon = trimmed.lastIndexOf(':');
        if (colon <= 0 || colon == trimmed.length() - 1) {
            throw new IllegalStateException("Invalid Sentinel endpoint '" + node
                    + "'; spring.data.redis.sentinel.nodes entries must be host:port");
        }
        try {
            return new RedisNode(trimmed.substring(0, colon),
                    Integer.parseInt(trimmed.substring(colon + 1)));
        } catch (NumberFormatException ex) {
            throw new IllegalStateException("Invalid Sentinel endpoint '" + node
                    + "'; expected host:port", ex);
        }
    }

    /** Host/Port view of the configured Sentinel nodes, for tests and the reporter. */
    static List<String> sentinelAddresses(RedisSentinelConfiguration configuration) {
        List<String> addresses = new ArrayList<>();
        configuration.getSentinels().forEach(n ->
                addresses.add(n.getHost() + ":" + n.getPort()));
        return addresses;
    }
}
