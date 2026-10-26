package com.javalearn.lab.seckill.login.api;

/**
 * Response of {@code POST /api/login}: the opaque token and its TTL in seconds.
 */
public record LoginResponse(String token, long expiresInSeconds) {
}
