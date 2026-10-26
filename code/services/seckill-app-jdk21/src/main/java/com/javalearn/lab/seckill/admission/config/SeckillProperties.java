package com.javalearn.lab.seckill.admission.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Externalized configuration for the async seckill path.
 *
 * <p>Every path, timeout, TTL, key prefix, exchange/queue name, consumer delay
 * and connection pool size comes from {@code app.seckill.*} in
 * {@code application.yml}, with environment-overridable defaults. This class
 * deliberately declares no Java defaults: the YAML is the single source of
 * defaults, and {@link #validate()} fails startup when a required key is
 * missing or nonsensical instead of silently using a hidden value.</p>
 */
@ConfigurationProperties(prefix = "app.seckill")
public class SeckillProperties {

    private boolean asyncEnabled;
    private final Lock lock = new Lock();
    private final Stock stock = new Stock();
    private final Request request = new Request();
    private final Idempotency idempotency = new Idempotency();
    private final Mq mq = new Mq();
    private final Consumer consumer = new Consumer();
    private final Redisson redisson = new Redisson();

    @PostConstruct
    void validate() {
        requireText(lock.keyPrefix, "app.seckill.lock.key-prefix");
        requirePositive(lock.waitMs, "app.seckill.lock.wait-ms");
        requirePositive(lock.leaseMs, "app.seckill.lock.lease-ms");
        requireText(stock.keyPrefix, "app.seckill.stock.key-prefix");
        requireText(stock.luaPath, "app.seckill.stock.lua-path");
        requireText(request.keyPrefix, "app.seckill.request.key-prefix");
        requirePositive(request.ttlSeconds, "app.seckill.request.ttl-seconds");
        requireText(idempotency.keyPrefix, "app.seckill.idempotency.key-prefix");
        requirePositive(idempotency.ttlSeconds, "app.seckill.idempotency.ttl-seconds");
        requireText(mq.exchange, "app.seckill.mq.exchange");
        requireText(mq.queue, "app.seckill.mq.queue");
        requireText(mq.routingKey, "app.seckill.mq.routing-key");
        requireNonNegative(consumer.delayMs, "app.seckill.consumer.delay-ms");
        requirePositive(redisson.connectTimeoutMs, "app.seckill.redisson.connect-timeout-ms");
        requirePositive(redisson.timeoutMs, "app.seckill.redisson.timeout-ms");
        requireNonNegative(redisson.retryAttempts, "app.seckill.redisson.retry-attempts");
        requirePositive(redisson.retryIntervalMs, "app.seckill.redisson.retry-interval-ms");
        requireNonNegative(redisson.connectionMinimumIdleSize,
                "app.seckill.redisson.connection-minimum-idle-size");
        requirePositive(redisson.connectionPoolSize, "app.seckill.redisson.connection-pool-size");
        if (redisson.connectionMinimumIdleSize > redisson.connectionPoolSize) {
            throw new IllegalStateException("app.seckill.redisson.connection-minimum-idle-size"
                    + " must not exceed app.seckill.redisson.connection-pool-size");
        }
    }

    private static void requireText(String value, String key) {
        if (value == null || value.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration: " + key);
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalStateException("Configuration must be positive: " + key);
        }
    }

    private static void requireNonNegative(long value, String key) {
        if (value < 0L) {
            throw new IllegalStateException("Configuration must not be negative: " + key);
        }
    }

    public boolean isAsyncEnabled() {
        return asyncEnabled;
    }

    public void setAsyncEnabled(boolean asyncEnabled) {
        this.asyncEnabled = asyncEnabled;
    }

    public Lock getLock() {
        return lock;
    }

    public Stock getStock() {
        return stock;
    }

    public Request getRequest() {
        return request;
    }

    public Idempotency getIdempotency() {
        return idempotency;
    }

    public Mq getMq() {
        return mq;
    }

    public Consumer getConsumer() {
        return consumer;
    }

    public Redisson getRedisson() {
        return redisson;
    }

    public static class Lock {

        private String keyPrefix;
        private long waitMs;
        private long leaseMs;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getWaitMs() {
            return waitMs;
        }

        public void setWaitMs(long waitMs) {
            this.waitMs = waitMs;
        }

        public long getLeaseMs() {
            return leaseMs;
        }

        public void setLeaseMs(long leaseMs) {
            this.leaseMs = leaseMs;
        }
    }

    public static class Stock {

        private String keyPrefix;
        private boolean seedOnStartup;
        private String luaPath;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public boolean isSeedOnStartup() {
            return seedOnStartup;
        }

        public void setSeedOnStartup(boolean seedOnStartup) {
            this.seedOnStartup = seedOnStartup;
        }

        public String getLuaPath() {
            return luaPath;
        }

        public void setLuaPath(String luaPath) {
            this.luaPath = luaPath;
        }
    }

    public static class Request {

        private String keyPrefix;
        private long ttlSeconds;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class Idempotency {

        private String keyPrefix;
        private long ttlSeconds;

        public String getKeyPrefix() {
            return keyPrefix;
        }

        public void setKeyPrefix(String keyPrefix) {
            this.keyPrefix = keyPrefix;
        }

        public long getTtlSeconds() {
            return ttlSeconds;
        }

        public void setTtlSeconds(long ttlSeconds) {
            this.ttlSeconds = ttlSeconds;
        }
    }

    public static class Mq {

        private String exchange;
        private String queue;
        private String routingKey;

        public String getExchange() {
            return exchange;
        }

        public void setExchange(String exchange) {
            this.exchange = exchange;
        }

        public String getQueue() {
            return queue;
        }

        public void setQueue(String queue) {
            this.queue = queue;
        }

        public String getRoutingKey() {
            return routingKey;
        }

        public void setRoutingKey(String routingKey) {
            this.routingKey = routingKey;
        }
    }

    public static class Consumer {

        private long delayMs;

        public long getDelayMs() {
            return delayMs;
        }

        public void setDelayMs(long delayMs) {
            this.delayMs = delayMs;
        }
    }

    public static class Redisson {

        private int connectTimeoutMs;
        private int timeoutMs;
        private int retryAttempts;
        private int retryIntervalMs;
        private int connectionMinimumIdleSize;
        private int connectionPoolSize;
        private boolean lazyInitialization;

        public int getConnectTimeoutMs() {
            return connectTimeoutMs;
        }

        public void setConnectTimeoutMs(int connectTimeoutMs) {
            this.connectTimeoutMs = connectTimeoutMs;
        }

        public int getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(int timeoutMs) {
            this.timeoutMs = timeoutMs;
        }

        public int getRetryAttempts() {
            return retryAttempts;
        }

        public void setRetryAttempts(int retryAttempts) {
            this.retryAttempts = retryAttempts;
        }

        public int getRetryIntervalMs() {
            return retryIntervalMs;
        }

        public void setRetryIntervalMs(int retryIntervalMs) {
            this.retryIntervalMs = retryIntervalMs;
        }

        public int getConnectionMinimumIdleSize() {
            return connectionMinimumIdleSize;
        }

        public void setConnectionMinimumIdleSize(int connectionMinimumIdleSize) {
            this.connectionMinimumIdleSize = connectionMinimumIdleSize;
        }

        public int getConnectionPoolSize() {
            return connectionPoolSize;
        }

        public void setConnectionPoolSize(int connectionPoolSize) {
            this.connectionPoolSize = connectionPoolSize;
        }

        public boolean isLazyInitialization() {
            return lazyInitialization;
        }

        public void setLazyInitialization(boolean lazyInitialization) {
            this.lazyInitialization = lazyInitialization;
        }
    }
}
