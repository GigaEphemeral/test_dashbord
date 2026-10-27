package com.javalearn.lab.seckill.lab.api;

/**
 * One effective (bound) value of the cache configuration family, as reported by
 * {@code GET /lab/config}.
 *
 * <p>The point of the read-back is the B0-04 acceptance rule: what the operator
 * configures, what the running JVM actually resolved and what the declared
 * default is must be visible in ONE place, so a {@code LAB_CACHE_*} override that
 * never took effect cannot be mistaken for a working change.</p>
 *
 * @param configKey    the Spring property key, e.g.
 *                     {@code app.lab.cache.l2.ttl-seconds}
 * @param env          the environment variable the yml placeholder reads
 *                     ({@code LAB_CACHE_L2_TTL_SECONDS}), {@code null} when the
 *                     key is declared without an env override
 * @param value        the effective value resolved by THIS running instance
 * @param defaultValue the default declared in {@code application.yml}; equal to
 *                     {@code value} while the env override is not set
 * @param source       the property source whose value won (Boot's resolution
 *                     order): {@code systemEnvironment} when the env var is set,
 *                     the {@code applicationConfig} resource otherwise
 */
public record LabCacheConfigItem(String configKey,
                                 String env,
                                 String value,
                                 String defaultValue,
                                 String source) {
}
