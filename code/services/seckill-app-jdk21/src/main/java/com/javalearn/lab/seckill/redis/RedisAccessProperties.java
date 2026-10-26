package com.javalearn.lab.seckill.redis;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * The single mode switch for every Redis client stack in the app (Task M3-1b).
 *
 * <p>Design §3.3/§8.2: the dev profile uses the standalone Redis and the ha
 * profile discovers the master through Sentinel; the switch is an environment
 * variable ({@code REDIS_MODE}), never a code branch. Both the Spring Data
 * Redis / Lettuce path (product cache L2, stock/idempotency stores, login
 * tokens, bloom/null-marker storage share the StringRedisTemplate) and the
 * Redisson path (product lock, Lua stock script, bloom filter) read this mode,
 * so they always move together — a half-switched app is exactly the confusing
 * state the lab must not produce.</p>
 *
 * <p>The endpoint values themselves stay in the existing {@code spring.data.redis.*}
 * keys (standalone host/port and {@code sentinel.master}/{@code sentinel.nodes});
 * this class owns only the mode plus the Sentinel-mode evidence-report knobs, so
 * no parallel address/pool keys are introduced (工程规约 §1).</p>
 */
@ConfigurationProperties(prefix = "app.redis")
public class RedisAccessProperties {

    public static final String MODE_STANDALONE = "standalone";
    public static final String MODE_SENTINEL = "sentinel";

    /** {@code standalone} or {@code sentinel}; no other value is accepted. */
    private String mode;

    private final Sentinel sentinel = new Sentinel();

    @PostConstruct
    public void validate() {
        if (!MODE_STANDALONE.equals(mode) && !MODE_SENTINEL.equals(mode)) {
            throw new IllegalStateException("app.redis.mode must be '" + MODE_STANDALONE
                    + "' or '" + MODE_SENTINEL + "' but was: " + mode
                    + " (set the REDIS_MODE environment variable)");
        }
        if (isSentinel()) {
            requirePositive(sentinel.discoveryReportAttempts,
                    "app.redis.sentinel.discovery-report-attempts");
            requirePositive(sentinel.discoveryReportIntervalMs,
                    "app.redis.sentinel.discovery-report-interval-ms");
        }
    }

    private static void requirePositive(long value, String key) {
        if (value <= 0L) {
            throw new IllegalStateException("Configuration must be positive: " + key);
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

    public Sentinel getSentinel() {
        return sentinel;
    }

    /** Startup master-discovery evidence report (Sentinel mode only). */
    public static class Sentinel {

        /**
         * Size of the startup discovery OBSERVATION window: this many
         * {@code SENTINEL masters} probes (answered by the monitored master NAME),
         * each logged when the reported address changes. They run on a daemon
         * thread so a down Sentinel group never blocks or fails the boot
         * (Task 14 degradation contract).
         */
        private int discoveryReportAttempts;

        /** Sleep between the probes above. */
        private long discoveryReportIntervalMs;

        public int getDiscoveryReportAttempts() {
            return discoveryReportAttempts;
        }

        public void setDiscoveryReportAttempts(int discoveryReportAttempts) {
            this.discoveryReportAttempts = discoveryReportAttempts;
        }

        public long getDiscoveryReportIntervalMs() {
            return discoveryReportIntervalMs;
        }

        public void setDiscoveryReportIntervalMs(long discoveryReportIntervalMs) {
            this.discoveryReportIntervalMs = discoveryReportIntervalMs;
        }
    }
}
