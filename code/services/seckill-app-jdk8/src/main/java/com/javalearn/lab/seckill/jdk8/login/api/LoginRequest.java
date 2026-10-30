package com.javalearn.lab.seckill.jdk8.login.api;

/** Body of POST /api/login; same field name as the JDK 21 shell. */
public class LoginRequest {

    private String userId;

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }
}
