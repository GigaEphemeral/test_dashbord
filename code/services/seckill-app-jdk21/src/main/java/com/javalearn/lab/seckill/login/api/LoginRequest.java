package com.javalearn.lab.seckill.login.api;

/**
 * Body of {@code POST /api/login}, e.g. {@code {"userId":"u1"}}.
 */
public record LoginRequest(String userId) {
}
