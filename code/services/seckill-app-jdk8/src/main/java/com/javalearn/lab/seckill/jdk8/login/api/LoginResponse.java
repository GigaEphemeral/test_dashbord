package com.javalearn.lab.seckill.jdk8.login.api;

/** Response of POST /api/login; same JSON shape as the JDK 21 shell. */
public class LoginResponse {

    private final String token;
    private final long expiresInSeconds;

    public LoginResponse(String token, long expiresInSeconds) {
        this.token = token;
        this.expiresInSeconds = expiresInSeconds;
    }

    public String getToken() {
        return token;
    }

    public long getExpiresInSeconds() {
        return expiresInSeconds;
    }
}
