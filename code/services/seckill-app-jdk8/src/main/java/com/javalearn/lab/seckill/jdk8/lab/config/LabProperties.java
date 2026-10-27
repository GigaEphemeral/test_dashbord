package com.javalearn.lab.seckill.jdk8.lab.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import javax.annotation.PostConstruct;

/**
 * Internal lab API settings for the JDK 8 shell (design §9.3).
 *
 * <p>The JDK 8 shell has no {@code LabTokenFilter} of its own, so this class
 * provides the shared secret the new snapshot endpoints are guarded with. The
 * token default lives in {@code application.yml} ({@code ${LAB_TOKEN:lab-token}})
 * and matches the chaos-controller / JDK 21 default, which keeps one
 * {@code .env} value working for both runtimes.</p>
 */
@ConfigurationProperties(prefix = "app.lab")
public class LabProperties {

    private String token;

    @PostConstruct
    void validate() {
        if (token == null || token.trim().isEmpty()) {
            throw new IllegalStateException("Missing required configuration: app.lab.token");
        }
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }
}
