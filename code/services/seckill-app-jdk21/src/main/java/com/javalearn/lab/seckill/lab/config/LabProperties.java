package com.javalearn.lab.seckill.lab.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Internal lab API settings. The token default lives in {@code application.yml}
 * ({@code ${LAB_TOKEN:lab-token}}) and matches the chaos-controller default.
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
