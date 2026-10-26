package com.javalearn.lab.seckill.admission.config;

import com.javalearn.lab.seckill.redis.RedisAccessProperties;
import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Redisson client for the per-product lock, the Redis idempotency store, the
 * Lua stock script and the M4-1 bloom filter.
 *
 * <p>All sizes and timeouts come from {@code app.seckill.redisson.*}; the
 * endpoint comes from the existing {@code spring.data.redis.*} properties so
 * the lock, the product cache and the stock store always share one Redis
 * endpoint (including Testcontainers dynamic properties).</p>
 *
 * <p>Task M3-1b: the SAME mode switch that selects the Lettuce path also
 * selects the Redisson server type — {@code app.redis.mode=standalone} keeps
 * the historic {@code useSingleServer()} behaviour, {@code sentinel} switches
 * to {@code sentinelServersConfig} (design §8.2), discovering the master BY
 * NAME. In sentinel mode the standalone host key is never consulted: a missing
 * master name or Sentinel list fails startup instead of silently pinning an
 * address.</p>
 *
 * <p>Lazy initialization is deliberate and configurable: creating the bean must
 * not open a connection at startup, otherwise a Redis/Sentinel outage would
 * prevent the app tier from booting and would contradict the Redis-outage
 * degradation contract from Task 14 (RedisOutageCacheIT).</p>
 */
@Configuration(proxyBeanMethods = false)
public class SeckillRedissonConfig {

    private static final Logger log = LoggerFactory.getLogger(SeckillRedissonConfig.class);

    @Bean(destroyMethod = "shutdown")
    public RedissonClient seckillRedissonClient(
            SeckillProperties properties,
            RedisProperties redisProperties,
            RedisAccessProperties accessProperties) {
        return Redisson.create(buildConfig(properties, redisProperties, accessProperties));
    }

    /**
     * Pure builder, exposed for tests (public on purpose: RedisModeSwitchTest
     * asserts BOTH clients refuse a half-configured sentinel mode, from the
     * redis package): assembles the Redisson {@code Config} for the bound mode
     * WITHOUT creating a client, so no network is touched.
     */
    public static Config buildConfig(SeckillProperties properties,
                                     RedisProperties redisProperties,
                                     RedisAccessProperties accessProperties) {
        SeckillProperties.Redisson redisson = properties.getRedisson();
        Config config = new Config();
        config.setLazyInitialization(redisson.isLazyInitialization());
        if (accessProperties.isSentinel()) {
            applySentinel(config, redisson, redisProperties);
        } else {
            applySingle(config, redisson, redisProperties);
        }
        return config;
    }

    private static void applySingle(Config config, SeckillProperties.Redisson redisson,
                                    RedisProperties redisProperties) {
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + redisProperties.getHost() + ":" + redisProperties.getPort())
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(redisson.getConnectTimeoutMs())
                .setTimeout(redisson.getTimeoutMs())
                .setRetryAttempts(redisson.getRetryAttempts())
                .setRetryInterval(redisson.getRetryIntervalMs())
                .setConnectionMinimumIdleSize(redisson.getConnectionMinimumIdleSize())
                .setConnectionPoolSize(redisson.getConnectionPoolSize());
        if (StringUtils.hasText(redisProperties.getPassword())) {
            single.setPassword(redisProperties.getPassword());
        }
    }

    private static void applySentinel(Config config, SeckillProperties.Redisson redisson,
                                      RedisProperties redisProperties) {
        RedisProperties.Sentinel sentinel = redisProperties.getSentinel();
        String masterName = sentinel == null ? null : sentinel.getMaster();
        List<String> addresses = sentinelAddresses(sentinel);
        // Mirrors RedisClientConfig: sentinel mode must never degrade into the
        // standalone address, so an incomplete discovery config fails startup.
        if (!StringUtils.hasText(masterName)) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires"
                    + " spring.data.redis.sentinel.master (env SPRING_DATA_REDIS_SENTINEL_MASTER,"
                    + " mapped by compose.ha.yml from REDIS_HA_MASTER_NAME): Redisson must find"
                    + " the lock/stock/bloom master BY NAME, not via the hardcoded standalone host");
        }
        if (addresses.isEmpty()) {
            throw new IllegalStateException("REDIS_MODE=sentinel requires"
                    + " spring.data.redis.sentinel.nodes (env SPRING_DATA_REDIS_SENTINEL_NODES"
                    + " as host:port,..., mapped from REDIS_SENTINEL_NODES) for the Redisson client");
        }

        SentinelServersConfig servers = config.useSentinelServers()
                .setMasterName(masterName)
                .addSentinelAddress(addresses.toArray(new String[0]))
                .setDatabase(redisProperties.getDatabase())
                .setConnectTimeout(redisson.getConnectTimeoutMs())
                .setTimeout(redisson.getTimeoutMs())
                .setRetryAttempts(redisson.getRetryAttempts())
                .setRetryInterval(redisson.getRetryIntervalMs())
                .setIdleConnectionTimeout(redisson.getIdleConnectionTimeoutMs())
                .setMasterConnectionMinimumIdleSize(redisson.getMasterConnectionMinimumIdleSize())
                .setMasterConnectionPoolSize(redisson.getMasterConnectionPoolSize())
                .setSlaveConnectionMinimumIdleSize(redisson.getSlaveConnectionMinimumIdleSize())
                .setSlaveConnectionPoolSize(redisson.getSlaveConnectionPoolSize())
                .setSubscriptionConnectionPoolSize(redisson.getSubscriptionConnectionPoolSize())
                .setScanInterval(redisson.getScanIntervalMs())
                .setReadMode(ReadMode.valueOf(redisson.getReadMode()));
        if (StringUtils.hasText(redisProperties.getPassword())) {
            servers.setPassword(redisProperties.getPassword());
        }
        if (StringUtils.hasText(sentinel.getPassword())) {
            servers.setSentinelPassword(sentinel.getPassword());
        }
        log.info("Redisson sentinelServersConfig: masterName={}, sentinels={}, readMode={}",
                masterName, addresses, servers.getReadMode());
    }

    /** Redisson wants scheme-qualified Sentinel addresses: redis://host:port. */
    static List<String> sentinelAddresses(RedisProperties.Sentinel sentinel) {
        List<String> addresses = new ArrayList<>();
        if (sentinel == null || sentinel.getNodes() == null) {
            return addresses;
        }
        for (String node : sentinel.getNodes()) {
            String trimmed = node.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            if (!trimmed.contains(":")) {
                throw new IllegalStateException("Invalid Sentinel endpoint '" + node
                        + "'; spring.data.redis.sentinel.nodes entries must be host:port"
                        + " (the port is externalised, never defaulted in Java)");
            }
            addresses.add("redis://" + trimmed);
        }
        return addresses;
    }
}
