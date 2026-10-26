package com.javalearn.lab.seckill.login.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.seckill.RedisTestContainer;
import com.javalearn.lab.seckill.login.api.LoginResponse;
import com.javalearn.lab.seckill.login.config.LoginProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Token store proof against a real Redis 7.4.2: value, prefix, TTL and unique
 * tokens per login.
 */
class LoginServiceIT {

    private static final String PREFIX = "login:it:token:";
    private static final long TTL_SECONDS = 120L;

    private StringRedisTemplate redisTemplate;
    private LoginService loginService;

    @BeforeEach
    void setUp() {
        redisTemplate = RedisTestContainer.newStringRedisTemplate();
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushDb();

        LoginProperties properties = new LoginProperties();
        properties.setTokenKeyPrefix(PREFIX);
        properties.setTokenTtlSeconds(TTL_SECONDS);
        loginService = new LoginService(redisTemplate, properties);
    }

    @Test
    void tokenIsStoredWithTheConfiguredTtl() {
        LoginResponse response = loginService.login("u1");

        assertThat(response.token()).isNotBlank();
        assertThat(response.expiresInSeconds()).isEqualTo(TTL_SECONDS);
        assertThat(redisTemplate.opsForValue().get(PREFIX + response.token())).isEqualTo("u1");

        Long ttl = redisTemplate.getExpire(PREFIX + response.token(), TimeUnit.SECONDS);
        assertThat(ttl).isNotNull().isPositive().isLessThanOrEqualTo(TTL_SECONDS);
    }

    @Test
    void everyLoginGetsADistinctToken() {
        String first = loginService.login("u1").token();
        String second = loginService.login("u1").token();

        assertThat(first).isNotEqualTo(second);
        assertThat(redisTemplate.opsForValue().get(PREFIX + first)).isEqualTo("u1");
        assertThat(redisTemplate.opsForValue().get(PREFIX + second)).isEqualTo("u1");
    }

    @Test
    void blankUserIdIsRejectedBeforeWritingToRedis() {
        assertThatThrownBy(() -> loginService.login(" "))
                .isInstanceOfSatisfying(BizException.class, exception -> {
                    assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.INVALID_ARGUMENT);
                    assertThat(exception.getMessage()).isEqualTo("userId is required");
                });
        assertThat(redisTemplate.keys(PREFIX + "*")).isEmpty();
    }
}
