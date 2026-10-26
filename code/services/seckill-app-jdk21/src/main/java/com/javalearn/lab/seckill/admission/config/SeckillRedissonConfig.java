package com.javalearn.lab.seckill.admission.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.redisson.config.SingleServerConfig;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Redisson client for the per-product lock.
 *
 * <p>All sizes and timeouts come from {@code app.seckill.redisson.*}; the
 * address comes from the existing {@code spring.data.redis.*} properties so the
 * lock, the product cache and the stock store always share one Redis endpoint
 * (including Testcontainers dynamic properties).</p>
 *
 * <p>Lazy initialization is deliberate and configurable: creating the bean must
 * not open a connection at startup, otherwise a Redis outage would prevent the
 * app tier from booting and would contradict the Redis-outage degradation
 * contract from Task 14.</p>
 */
@Configuration(proxyBeanMethods = false)
public class SeckillRedissonConfig {

    @Bean(destroyMethod = "shutdown")
    public RedissonClient seckillRedissonClient(
            SeckillProperties properties,
            @Value("${spring.data.redis.host}") String host,
            @Value("${spring.data.redis.port}") int port,
            @Value("${spring.data.redis.database:0}") int database,
            @Value("${spring.data.redis.password:}") String password) {
        SeckillProperties.Redisson redisson = properties.getRedisson();
        Config config = new Config();
        config.setLazyInitialization(redisson.isLazyInitialization());
        SingleServerConfig single = config.useSingleServer()
                .setAddress("redis://" + host + ":" + port)
                .setDatabase(database)
                .setConnectTimeout(redisson.getConnectTimeoutMs())
                .setTimeout(redisson.getTimeoutMs())
                .setRetryAttempts(redisson.getRetryAttempts())
                .setRetryInterval(redisson.getRetryIntervalMs())
                .setConnectionMinimumIdleSize(redisson.getConnectionMinimumIdleSize())
                .setConnectionPoolSize(redisson.getConnectionPoolSize());
        if (password != null && !password.isEmpty()) {
            single.setPassword(password);
        }
        return Redisson.create(config);
    }
}
