package com.javalearn.lab.seckill.admission.config;

import com.javalearn.lab.seckill.redis.RedisAccessProperties;
import org.junit.jupiter.api.Test;
import org.redisson.config.Config;
import org.redisson.config.ReadMode;
import org.redisson.config.SentinelServersConfig;
import org.redisson.config.SingleServerConfig;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The Redisson half of the Task M3-1b mode switch, asserted at the pure
 * {@link Config}-assembly level: no Redisson client is created, so no socket
 * is opened and no Sentinel/Redis needs to be running. Design §8.2 requires
 * {@code sentinelServersConfig} with master discovery BY NAME in the ha
 * profile; standalone keeps the historic single-server behaviour untouched.
 */
class SeckillRedissonModeTest {

    private static SeckillProperties seckillProperties() {
        // Mirrors the application.yml defaults; validate() runs the same
        // positive-value checks the container would run at boot.
        SeckillProperties properties = new SeckillProperties();
        SeckillProperties.Redisson redisson = properties.getRedisson();
        redisson.setConnectTimeoutMs(3000);
        redisson.setTimeoutMs(3000);
        redisson.setRetryAttempts(3);
        redisson.setRetryIntervalMs(1000);
        redisson.setConnectionMinimumIdleSize(2);
        redisson.setConnectionPoolSize(8);
        redisson.setLazyInitialization(true);
        redisson.setIdleConnectionTimeoutMs(10000);
        redisson.setScanIntervalMs(1000);
        redisson.setReadMode("MASTER");
        redisson.setMasterConnectionMinimumIdleSize(2);
        redisson.setMasterConnectionPoolSize(8);
        redisson.setSlaveConnectionMinimumIdleSize(2);
        redisson.setSlaveConnectionPoolSize(8);
        redisson.setSubscriptionConnectionPoolSize(8);
        properties.setAsyncEnabled(true);
        properties.getLock().setKeyPrefix("lock:seckill:");
        properties.getLock().setWaitMs(500);
        properties.getLock().setLeaseMs(3000);
        properties.getStock().setKeyPrefix("seckill:stock:");
        properties.getStock().setLuaPath("lua/seckill_stock.lua");
        properties.getStock().setSeedOnStartup(false);
        properties.getRequest().setKeyPrefix("seckill:request:");
        properties.getRequest().setTtlSeconds(1800);
        properties.getIdempotency().setKeyPrefix("seckill:idem:");
        properties.getIdempotency().setTtlSeconds(1800);
        properties.getMq().setExchange("seckill.order.exchange");
        properties.getMq().setQueue("seckill.order.queue");
        properties.getMq().setRoutingKey("seckill.order.requested");
        properties.getConsumer().setDelayMs(20);
        return properties;
    }

    private static RedisProperties redisProperties(boolean sentinelMode) {
        RedisProperties redis = new RedisProperties();
        redis.setHost("redis");
        redis.setPort(6379);
        if (sentinelMode) {
            redis.setSentinel(sentinel("mymaster",
                    List.of("sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379")));
            redis.setPassword("data-password");
        }
        return redis;
    }

    private static RedisProperties.Sentinel sentinel(String master, List<String> nodes) {
        RedisProperties.Sentinel sentinel = new RedisProperties.Sentinel();
        sentinel.setMaster(master);
        sentinel.setNodes(nodes);
        return sentinel;
    }

    private static RedisAccessProperties access(String mode) {
        RedisAccessProperties access = new RedisAccessProperties();
        access.setMode(mode);
        // Same defaults the YAML supplies for the discovery-log probe.
        access.getSentinel().setDiscoveryReportAttempts(12);
        access.getSentinel().setDiscoveryReportIntervalMs(5000);
        access.validate();
        return access;
    }

    @Test
    void standaloneModeKeepsTheSingleServerConfigUntouched() {
        Config config = SeckillRedissonConfig.buildConfig(
                seckillProperties(), redisProperties(false), access("standalone"));

        assertThat(config.isSingleConfig()).isTrue();
        assertThat(config.isSentinelConfig()).isFalse();
        SingleServerConfig single = config.useSingleServer();
        assertThat(single.getAddress()).isEqualTo("redis://redis:6379");
        assertThat(single.getConnectionPoolSize()).isEqualTo(8);
        assertThat(single.getConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(config.isLazyInitialization()).isTrue();
    }

    @Test
    void sentinelModeBuildsSentinelServersConfigWithMasterName() {
        Config config = SeckillRedissonConfig.buildConfig(
                seckillProperties(), redisProperties(true), access("sentinel"));

        assertThat(config.isSentinelConfig()).isTrue();
        assertThat(config.isSingleConfig()).isFalse();
        SentinelServersConfig servers = config.useSentinelServers();
        // Discovered BY NAME; the standalone host never appears.
        assertThat(servers.getMasterName()).isEqualTo("mymaster");
        assertThat(servers.getSentinelAddresses()).containsExactlyInAnyOrder(
                "redis://sentinel-1:26379", "redis://sentinel-2:26379", "redis://sentinel-3:26379");
        assertThat(servers.getDatabase()).isEqualTo(0);
        assertThat(servers.getPassword()).isEqualTo("data-password");
        assertThat(servers.getReadMode()).isEqualTo(ReadMode.MASTER);
        assertThat(servers.getScanInterval()).isEqualTo(1000);
        assertThat(servers.getIdleConnectionTimeout()).isEqualTo(10000);
        assertThat(servers.getMasterConnectionPoolSize()).isEqualTo(8);
        assertThat(servers.getMasterConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(servers.getSlaveConnectionPoolSize()).isEqualTo(8);
        assertThat(servers.getSlaveConnectionMinimumIdleSize()).isEqualTo(2);
        assertThat(servers.getSubscriptionConnectionPoolSize()).isEqualTo(8);
        assertThat(servers.getConnectTimeout()).isEqualTo(3000);
        assertThat(servers.getTimeout()).isEqualTo(3000);
        assertThat(servers.getRetryAttempts()).isEqualTo(3);
        assertThat(servers.getRetryInterval()).isEqualTo(1000);
        assertThat(config.isLazyInitialization()).isTrue();
    }

    @Test
    void sentinelModeWithoutNameOrNodesNeverFallsBackToSingleServer() {
        RedisProperties noMaster = redisProperties(true);
        noMaster.getSentinel().setMaster("");
        assertThatThrownBy(() -> SeckillRedissonConfig.buildConfig(
                seckillProperties(), noMaster, access("sentinel")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BY NAME");

        RedisProperties noNodes = redisProperties(true);
        noNodes.getSentinel().setNodes(List.of());
        assertThatThrownBy(() -> SeckillRedissonConfig.buildConfig(
                seckillProperties(), noNodes, access("sentinel")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATA_REDIS_SENTINEL_NODES");

        // No Sentinel subtree at all (REDIS_MODE flipped without the ha
        // overlay): a clear refusal, not a NullPointerException.
        RedisProperties bare = new RedisProperties();
        bare.setSentinel(null);
        assertThatThrownBy(() -> SeckillRedissonConfig.buildConfig(
                seckillProperties(), bare, access("sentinel")))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.sentinel.master");
    }

    @Test
    void modeSelectsTheClientTypeEvenWhenSentinelKeysArePresent() {
        RedisProperties redis = redisProperties(true);
        Config config = SeckillRedissonConfig.buildConfig(
                seckillProperties(), redis, access("standalone"));

        // Keys alone do not switch: only the mode does (both stacks agree).
        assertThat(config.isSingleConfig()).isTrue();
        assertThat(config.useSingleServer().getAddress()).isEqualTo("redis://redis:6379");
    }

    @Test
    void unknownModeAndBadSentinelKnobsAreRejectedByValidation() {
        RedisAccessProperties bad = new RedisAccessProperties();
        bad.setMode("SENTINEL"); // exact lowercase only; no silent normalisation
        assertThatThrownBy(bad::validate).isInstanceOf(IllegalStateException.class);

        SeckillProperties properties = seckillProperties();
        properties.getRedisson().setReadMode("REPLICA");
        assertThatThrownBy(properties::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("read-mode");

        SeckillProperties oversized = seckillProperties();
        oversized.getRedisson().setSlaveConnectionMinimumIdleSize(99);
        assertThatThrownBy(oversized::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("slave-connection-minimum-idle-size");
    }
}
