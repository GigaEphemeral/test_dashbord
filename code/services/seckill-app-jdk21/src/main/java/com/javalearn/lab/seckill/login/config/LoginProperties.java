package com.javalearn.lab.seckill.login.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Mock-login token store settings ({@code app.login.*}). No Java defaults: the
 * YAML is the single source of defaults.
 */
@ConfigurationProperties(prefix = "app.login")
public class LoginProperties {

    private String tokenKeyPrefix;
    private long tokenTtlSeconds;

    @PostConstruct
    void validate() {
        if (tokenKeyPrefix == null || tokenKeyPrefix.trim().isEmpty()) {
            throw new IllegalStateException(
                    "Missing required configuration: app.login.token-key-prefix");
        }
        if (tokenTtlSeconds <= 0L) {
            throw new IllegalStateException(
                    "Configuration must be positive: app.login.token-ttl-seconds");
        }
    }

    public String getTokenKeyPrefix() {
        return tokenKeyPrefix;
    }

    public void setTokenKeyPrefix(String tokenKeyPrefix) {
        this.tokenKeyPrefix = tokenKeyPrefix;
    }

    public long getTokenTtlSeconds() {
        return tokenTtlSeconds;
    }

    public void setTokenTtlSeconds(long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }
}
