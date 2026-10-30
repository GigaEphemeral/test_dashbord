package com.javalearn.lab.seckill.jdk8.redis;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * The single mode switch for every Redis client stack in the JDK 8 shell
 * (design §3.3/§8.2, parity with the JDK 21 module).
 *
 * <p>Both the Spring Data Redis / Lettuce path (StringRedisTemplate used by the
 * stock, idempotency and request stores) and the Redisson path (per-product
 * lock) read this mode, so they always move together. Endpoint values stay in
 * the standard {@code spring.redis.*} keys (standalone host/port and
 * {@code sentinel.master}/{@code sentinel.nodes}); this class owns only the mode.
 * The JDK 21 Sentinel discovery-log reporter is not ported, so its
 * {@code discovery-report-*} knobs are deliberately not declared either.</p>
 */
@ConfigurationProperties(prefix = "app.redis")
public class RedisAccessProperties {

    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_SENTINEL = "sentinel";

    /** {@code standalone} or {@code sentinel}; no other value is accepted. */
    private String mode;

    @PostConstruct
    public void validate() {
        if (!MODE_STANDALONE.equals(mode) && !MODE_SENTINEL.equals(mode)) {
            throw new IllegalStateException("app.redis.mode must be '" + MODE_STANDALONE
                    + "' or '" + MODE_SENTINEL + "' but was: " + mode
                    + " (set the REDIS_MODE environment variable)");
        }
    }

    public boolean isSentinel() {
        return MODE_SENTINEL.equals(mode);
    }

    public String getMode() {
        return mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }
}
