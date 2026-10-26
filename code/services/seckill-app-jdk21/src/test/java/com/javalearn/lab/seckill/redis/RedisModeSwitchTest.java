package com.javalearn.lab.seckill.redis;

import com.javalearn.lab.seckill.admission.config.SeckillProperties;
import com.javalearn.lab.seckill.admission.config.SeckillRedissonConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration;
import org.springframework.boot.autoconfigure.data.redis.RedisProperties;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.core.env.SystemEnvironmentPropertySource;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.connection.RedisSentinelConfiguration;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceClientConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.boot.env.YamlPropertySourceLoader;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Task M3-1b config contract, verified WITHOUT any live Redis/Sentinel:
 *
 * <ol>
 *   <li>{@code application.yml} plus the environment bind BOTH modes
 *       (standalone default = dev; the ha profile supplies the standard
 *       {@code SPRING_DATA_REDIS_SENTINEL_*} properties, which compose.ha.yml
 *       maps from the lab-friendly REDIS_HA_MASTER_NAME / REDIS_SENTINEL_NODES).</li>
 *   <li>The mode switch — not the mere presence of Sentinel keys — is what
 *       selects the client type, for the Lettuce factory and (via the same
 *       bound properties) the Redisson builder.</li>
 *   <li>Sentinel mode can never fall back to the standalone host: missing
 *       master name or nodes fail fast, and the standalone host key is not
 *       even consulted.</li>
 *   <li>DEV REGRESSION GUARD: the rendered dev environment carries NO
 *       {@code spring.data.redis.sentinel.*} key at all. Boot's
 *       {@code RedisConnectionConfiguration} constructor evaluates
 *       {@code determineMode()} even when its factory bean backs off, and a
 *       present-but-empty master name aborts every startup (dev and all ITs)
 *       with "Sentinel Master Id must not be null or empty" - that is why the
 *       keys are injected per profile instead of defaulted in the yaml.</li>
 *   <li>REVIEW FIX M3-1b: the custom factory must not silently kill the Boot
 *       Lettuce tuning keys it now owns — {@code spring.data.redis.timeout}
 *       and {@code lettuce.shutdown-timeout} reach the active
 *       {@code LettuceClientConfiguration} in both modes.</li>
 * </ol>
 */
class RedisModeSwitchTest {

    private static final String SENTINEL_NODES =
            "sentinel-1:26379,sentinel-2:26379,sentinel-3:26379";

    /** application.yml as the source of truth, plus simulated env variables. */
    private static StandardEnvironment environmentWith(Map<String, Object> envVars) {
        StandardEnvironment environment = new StandardEnvironment();
        try {
            new YamlPropertySourceLoader()
                    .load("application.yml", new ClassPathResource("application.yml"))
                    .forEach(source -> environment.getPropertySources().addFirst(source));
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
        // Boot applies the SPRING_DATA_REDIS_SENTINEL_MASTER ->
        // spring.data.redis.sentinel.master relaxed mapping ONLY to the property
        // source named "systemEnvironment" (measured: a same-typed source under
        // any other name binds scalars but not the nested Sentinel subtree).
        // So the simulation replaces the real OS-environment source, which is
        // exactly the source a Docker container's env vars arrive through, and
        // also makes the test hermetic (no host env leakage).
        environment.getPropertySources().replace(
                StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                new SystemEnvironmentPropertySource(
                        StandardEnvironment.SYSTEM_ENVIRONMENT_PROPERTY_SOURCE_NAME,
                        new HashMap<>(envVars)));
        return environment;
    }

    private static RedisProperties bindRedisProperties(Map<String, Object> envVars) {
        StandardEnvironment environment = environmentWith(envVars);
        return Binder.get(environment).bind("spring.data.redis", RedisProperties.class).get();
    }

    private static RedisAccessProperties bindAccessProperties(Map<String, Object> envVars) {
        StandardEnvironment environment = environmentWith(envVars);
        return Binder.get(environment)
                .bind("app.redis", RedisAccessProperties.class).get();
    }

    @Test
    void devDefaultsBindStandaloneAndLeaveNoSentinelKeyBehind() {
        // The Boot landmine this guards: RedisConnectionConfiguration's
        // constructor runs determineMode() even when its factory backs off to
        // ours, and spring.data.redis.sentinel.master="" throws
        // "Sentinel Master Id must not be null or empty" for EVERY context.
        StandardEnvironment environment = environmentWith(Map.of());
        assertThat(environment.containsProperty("spring.data.redis.sentinel")).isFalse();
        assertThat(environment.containsProperty("spring.data.redis.sentinel.master")).isFalse();
        assertThat(environment.containsProperty("spring.data.redis.sentinel.nodes")).isFalse();

        RedisProperties redis = Binder.get(environment)
                .bind("spring.data.redis", RedisProperties.class).get();
        RedisAccessProperties access = bindAccessProperties(Map.of());

        assertThat(access.getMode()).isEqualTo(RedisAccessProperties.MODE_STANDALONE);
        // No Sentinel subtree at all - not an empty one.
        assertThat(redis.getSentinel()).isNull();
        access.validate();

        LettuceConnectionFactory factory =
                RedisClientConfig.buildConnectionFactory(redis, access);
        assertThat(factory.getSentinelConfiguration()).isNull();
        assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("redis");
        assertThat(factory.getStandaloneConfiguration().getPort()).isEqualTo(6379);
    }

    @Test
    void haEnvVariablesBindIntoTheSentinelKeys() {
        Map<String, Object> env = Map.of(
                "REDIS_MODE", "sentinel",
                "SPRING_DATA_REDIS_SENTINEL_MASTER", "mymaster",
                "SPRING_DATA_REDIS_SENTINEL_NODES", SENTINEL_NODES,
                "REDIS_PASSWORD", "lab-only-data-password");
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);

        assertThat(access.isSentinel()).isTrue();
        assertThat(redis.getSentinel().getMaster()).isEqualTo("mymaster");
        assertThat(redis.getSentinel().getNodes()).containsExactly(
                "sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379");
        assertThat(redis.getPassword()).isEqualTo("lab-only-data-password");

        LettuceConnectionFactory factory =
                RedisClientConfig.buildConnectionFactory(redis, access);
        RedisSentinelConfiguration sentinel = factory.getSentinelConfiguration();
        assertThat(sentinel).isNotNull();
        // The master is addressed BY NAME; the standalone host must not leak in.
        assertThat(sentinel.getMaster().getName()).isEqualTo("mymaster");
        assertThat(sentinel.getSentinels()).hasSize(3);
        assertThat(RedisClientConfig.sentinelAddresses(sentinel))
                .containsExactlyInAnyOrder(
                        "sentinel-1:26379", "sentinel-2:26379", "sentinel-3:26379");
        // A documented Spring Data quirk: even the Sentinel-typed constructor
        // leaves a vestigial DEFAULT RedisStandaloneConfiguration behind the
        // getter, so "no fallback to the standalone host" is asserted on the
        // ACTIVE configuration the factory builds connections from.
        assertThat(factory.isRedisSentinelAware()).isTrue();
        RedisStandaloneConfiguration vestigial = factory.getStandaloneConfiguration();
        if (vestigial != null) {
            // localhost:6379 default from the framework, never our bound host.
            assertThat(vestigial.getHostName()).isNotEqualTo("redis");
        }
        assertThat(String.valueOf(sentinel.getPassword().get()))
                .isEqualTo("lab-only-data-password");
    }

    @Test
    void sentinelModeWithoutNodesFailsInsteadOfFallingBackToTheHost() {
        Map<String, Object> env = Map.of(
                "REDIS_MODE", "sentinel",
                "SPRING_DATA_REDIS_SENTINEL_MASTER", "mymaster");
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);

        // Fail fast: the ONLY acceptable outcome is an exception naming the
        // missing env key - building a factory over spring.data.redis.host
        // would be the hardcoded-address fallback the design forbids.
        assertThatThrownBy(() -> RedisClientConfig.buildConnectionFactory(redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATA_REDIS_SENTINEL_NODES")
                .hasMessageContaining("hardcoded");
    }

    @Test
    void sentinelModeWithoutMasterNameFails() {
        Map<String, Object> env = Map.of(
                "REDIS_MODE", "sentinel",
                "SPRING_DATA_REDIS_SENTINEL_NODES", SENTINEL_NODES);
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);

        assertThatThrownBy(() -> RedisClientConfig.buildConnectionFactory(redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("master NAME");
    }

    @Test
    void sentinelModeWithoutAnySentinelConfigFailsBothClientsNotNpe() {
        // The realistic half-configured case: someone flips REDIS_MODE without
        // the ha overlay (or forgets SPRING_DATA_REDIS_SENTINEL_* when running
        // the jar by hand). Both stacks must refuse, naming the keys.
        Map<String, Object> env = Map.of("REDIS_MODE", "sentinel");
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);
        assertThat(redis.getSentinel()).isNull();

        assertThatThrownBy(() -> RedisClientConfig.buildConnectionFactory(redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("SPRING_DATA_REDIS_SENTINEL_MASTER");

        SeckillProperties seckill = Binder.get(environmentWith(env))
                .bind("app.seckill", SeckillProperties.class).get();
        assertThatThrownBy(() -> SeckillRedissonConfig.buildConfig(seckill, redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("spring.data.redis.sentinel.master");
    }

    @Test
    void modeIsTheSelectorAndSentinelKeysAloneNeverSwitchTheClient() {
        // Nodes present but REDIS_MODE unset (a stray env in the dev rendering):
        // the factory must stay standalone - the switch is the mode, not keys.
        Map<String, Object> env = Map.of(
                "SPRING_DATA_REDIS_SENTINEL_MASTER", "mymaster",
                "SPRING_DATA_REDIS_SENTINEL_NODES", SENTINEL_NODES);
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);

        LettuceConnectionFactory factory =
                RedisClientConfig.buildConnectionFactory(redis, access);
        assertThat(factory.getSentinelConfiguration()).isNull();
        assertThat(factory.getStandaloneConfiguration().getHostName()).isEqualTo("redis");
    }

    @Test
    void unknownModeIsRejected() {
        RedisAccessProperties access =
                bindAccessProperties(Map.of("REDIS_MODE", "cluster"));
        assertThatThrownBy(access::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("REDIS_MODE");
    }

    @Test
    void sentinelNodeWithoutExplicitPortFailsInsteadOfDefaulting() {
        // The Sentinel port must live in the node list (REDIS_SENTINEL_NODES ->
        // SPRING_DATA_REDIS_SENTINEL_NODES), never in a Java default - 工程规约 §1.
        Map<String, Object> env = Map.of(
                "REDIS_MODE", "sentinel",
                "SPRING_DATA_REDIS_SENTINEL_MASTER", "mymaster",
                "SPRING_DATA_REDIS_SENTINEL_NODES", "sentinel-1,sentinel-2:26380");
        RedisProperties redis = bindRedisProperties(env);
        RedisAccessProperties access = bindAccessProperties(env);

        assertThatThrownBy(() -> RedisClientConfig.buildConnectionFactory(redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host:port");

        SeckillProperties seckill = Binder.get(environmentWith(env))
                .bind("app.seckill", SeckillProperties.class).get();
        assertThatThrownBy(() -> SeckillRedissonConfig.buildConfig(seckill, redis, access))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("host:port");
    }

    @Test
    void redissonKnobsFallBackToTheGenericPoolKeys() {
        // Standalone defaults from application.yml (nothing set): sentinel
        // knobs exist, are positive, and read MASTER - strongly consistent
        // demo semantics; pools ride the existing SECKILL_REDISSON_* defaults.
        Map<String, Object> env = Map.of();
        assertThat(bindAccessProperties(env).isSentinel()).isFalse();
        assertThat(bindRedissonDefaults(env)).isEqualTo(List.of("MASTER", 1000, 10000));

        Map<String, Object> poolEnv = Map.of("SECKILL_REDISSON_POOL_SIZE", "16",
                "SECKILL_REDISSON_READ_MODE", "MASTER_SLAVE");
        assertThat(bindRedissonDefaults(poolEnv)).isEqualTo(List.of("MASTER_SLAVE", 1000, 10000));
        StandardEnvironment environment = environmentWith(poolEnv);
        var bound = Binder.get(environment)
                .bind("app.seckill", com.javalearn.lab.seckill.admission.config.SeckillProperties.class)
                .get();
        assertThat(bound.getRedisson().getMasterConnectionPoolSize()).isEqualTo(16);
        assertThat(bound.getRedisson().getSlaveConnectionPoolSize()).isEqualTo(16);
        assertThat(bound.getRedisson().getSubscriptionConnectionPoolSize()).isEqualTo(16);
        assertThat(bound.getRedisson().getConnectionPoolSize()).isEqualTo(16);

        // The dedicated override wins over the fallback.
        StandardEnvironment overrideEnv = environmentWith(Map.of(
                "SECKILL_REDISSON_POOL_SIZE", "16", "SECKILL_REDISSON_SLAVE_POOL_SIZE", "4"));
        var overridden = Binder.get(overrideEnv)
                .bind("app.seckill", com.javalearn.lab.seckill.admission.config.SeckillProperties.class)
                .get();
        assertThat(overridden.getRedisson().getSlaveConnectionPoolSize()).isEqualTo(4);
        assertThat(overridden.getRedisson().getMasterConnectionPoolSize()).isEqualTo(16);
    }

    private static List<Object> bindRedissonDefaults(Map<String, Object> env) {
        StandardEnvironment environment = environmentWith(env);
        var bound = Binder.get(environment)
                .bind("app.seckill", com.javalearn.lab.seckill.admission.config.SeckillProperties.class)
                .get();
        return List.of(bound.getRedisson().getReadMode(),
                bound.getRedisson().getScanIntervalMs(),
                bound.getRedisson().getIdleConnectionTimeoutMs());
    }

    @Test
    void contextLevelSwitchReporterOnlyExistsInSentinelMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisClientConfig.class);

        // Boot's auto-configured factory backs off to OUR mode-aware factory,
        // and no discovery reporter bean exists in standalone mode.
        runner.withPropertyValues("app.redis.mode=standalone", "spring.data.redis.host=redis")
                .run(context -> {
                    assertThat(context).hasSingleBean(LettuceConnectionFactory.class);
                    assertThat(context.getBean(LettuceConnectionFactory.class)
                            .getSentinelConfiguration()).isNull();
                    assertThat(context).doesNotHaveBean(SentinelMasterDiscoveryReporter.class);
                });

        runner.withPropertyValues(
                        "app.redis.mode=sentinel",
                        "spring.data.redis.sentinel.master=mymaster",
                        "spring.data.redis.sentinel.nodes=sentinel-1:26379,sentinel-2:26379",
                        "app.redis.sentinel.discovery-report-attempts=1",
                        "app.redis.sentinel.discovery-report-interval-ms=1")
                .run(context -> {
                    assertThat(context).hasSingleBean(LettuceConnectionFactory.class);
                    assertThat(context.getBean(LettuceConnectionFactory.class)
                            .getSentinelConfiguration()).isNotNull();
                    // The bean exists; its probe runs on a daemon thread and
                    // never blocks or fails the context when nothing listens.
                    assertThat(context).hasSingleBean(SentinelMasterDiscoveryReporter.class);
                    assertThat(context).hasNotFailed();
                });
    }

    @Test
    void standaloneModeContextStillStartsWithoutRedis() {
        // The lazy contract: creating the factory must not connect, so an
        // unreachable host is NOT a boot failure (RedisOutageCacheIT covers
        // the end-to-end degradation; this pins the bean-creation half).
        new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(RedisAutoConfiguration.class))
                .withUserConfiguration(RedisClientConfig.class)
                .withPropertyValues(
                        "app.redis.mode=standalone",
                        "spring.data.redis.host=127.0.0.1",
                        "spring.data.redis.port=1")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(LettuceConnectionFactory.class);
                });
    }

    @Test
    void bootLettuceTimeoutKeysReachTheClientConfiguration() {
        // Review finding M3-1b: the custom factory replaces Boot's
        // RedisLettuceConnectionConfiguration, so spring.data.redis.timeout and
        // spring.data.redis.lettuce.shutdown-timeout would otherwise be
        // set-but-silently-dead (工程规约 §1). They must reach the active
        // LettuceClientConfiguration for BOTH modes.
        Map<String, Object> env = Map.of(
                "SPRING_DATA_REDIS_TIMEOUT", "3s",
                "SPRING_DATA_REDIS_LETTUCE_SHUTDOWN_TIMEOUT", "500ms");
        RedisProperties redis = bindRedisProperties(env);
        assertThat(redis.getTimeout()).isEqualTo(Duration.ofSeconds(3));
        assertThat(redis.getLettuce().getShutdownTimeout()).isEqualTo(Duration.ofMillis(500));

        for (String mode : List.of(RedisAccessProperties.MODE_STANDALONE,
                RedisAccessProperties.MODE_SENTINEL)) {
            Map<String, Object> modeEnv = new HashMap<>(env);
            modeEnv.put("REDIS_MODE", mode);
            if (RedisAccessProperties.MODE_SENTINEL.equals(mode)) {
                modeEnv.put("SPRING_DATA_REDIS_SENTINEL_MASTER", "mymaster");
                modeEnv.put("SPRING_DATA_REDIS_SENTINEL_NODES", SENTINEL_NODES);
            }
            LettuceConnectionFactory factory = RedisClientConfig.buildConnectionFactory(
                    bindRedisProperties(modeEnv), bindAccessProperties(modeEnv));
            assertThat(factory.getClientConfiguration().getCommandTimeout())
                    .as("command timeout in " + mode + " mode")
                    .isEqualTo(Duration.ofSeconds(3));
            assertThat(factory.getClientConfiguration().getShutdownTimeout())
                    .as("shutdown timeout in " + mode + " mode")
                    .isEqualTo(Duration.ofMillis(500));
        }

        // Unset keys keep the framework defaults - asserted AGAINST the builder
        // default object, never a Java literal, so the comparison stays honest
        // when Spring Data changes its own defaults.
        LettuceClientConfiguration defaults = LettuceClientConfiguration.builder().build();
        LettuceConnectionFactory untouched = RedisClientConfig.buildConnectionFactory(
                bindRedisProperties(Map.of()), bindAccessProperties(Map.of()));
        assertThat(untouched.getClientConfiguration().getCommandTimeout())
                .isEqualTo(defaults.getCommandTimeout());
        assertThat(untouched.getClientConfiguration().getShutdownTimeout())
                .isEqualTo(defaults.getShutdownTimeout());
    }
}
