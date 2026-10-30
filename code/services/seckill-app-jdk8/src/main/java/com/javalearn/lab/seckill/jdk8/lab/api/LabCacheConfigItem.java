package com.javalearn.lab.seckill.jdk8.lab.api;

/**
 * One effective (bound) value of the cache configuration family, as reported by
 * {@code GET /lab/config}.
 *
 * <p>The point of the read-back is the B0-04 acceptance rule: what the operator
 * configures, what the running JVM actually resolved and what the declared
 * default is must be visible in ONE place, so a {@code LAB_CACHE_*} override that
 * never took effect cannot be mistaken for a working change.</p>
 */
public final class LabCacheConfigItem {

    private final String configKey;
    private final String env;
    private final String value;
    private final String defaultValue;
    private final String source;

    public LabCacheConfigItem(String configKey,
                              String env,
                              String value,
                              String defaultValue,
                              String source) {
        this.configKey = configKey;
        this.env = env;
        this.value = value;
        this.defaultValue = defaultValue;
        this.source = source;
    }

    public String getConfigKey() {
        return configKey;
    }

    public String getEnv() {
        return env;
    }

    public String getValue() {
        return value;
    }

    public String getDefaultValue() {
        return defaultValue;
    }

    public String getSource() {
        return source;
    }

    public String configKey() {
        return configKey;
    }

    public String env() {
        return env;
    }

    public String value() {
        return value;
    }

    public String defaultValue() {
        return defaultValue;
    }

    public String source() {
        return source;
    }
}
