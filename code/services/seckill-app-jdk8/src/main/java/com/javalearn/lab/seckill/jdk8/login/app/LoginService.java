package com.javalearn.lab.seckill.jdk8.login.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.jdk8.login.api.LoginResponse;
import com.javalearn.lab.seckill.jdk8.login.config.LoginProperties;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

/**
 * Mock login for the k6/demo flows. Java 8 port of the JDK 21 service.
 *
 * <p>There is no user table and no authorization enforcement yet: the endpoint
 * accepts any non-blank userId, issues a UUID token and stores
 * {@code login:token:<token> -> userId} with the configured TTL. The token
 * store is shared on purpose: verify.sh logs in through Nginx, where app-3
 * receives one third of the requests, and asserts the key exists in Redis.</p>
 */
public final class LoginService {

    private final StringRedisTemplate redisTemplate;
    private final LoginProperties properties;

    public LoginService(StringRedisTemplate redisTemplate, LoginProperties properties) {
        this.redisTemplate = Objects.requireNonNull(redisTemplate, "redisTemplate");
        this.properties = Objects.requireNonNull(properties, "properties");
    }

    public LoginResponse login(String userId) {
        if (userId == null || userId.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "userId is required");
        }
        String token = UUID.randomUUID().toString();
        redisTemplate.opsForValue().set(
                properties.getTokenKeyPrefix() + token,
                userId,
                Duration.ofSeconds(properties.getTokenTtlSeconds()));
        return new LoginResponse(token, properties.getTokenTtlSeconds());
    }
}
