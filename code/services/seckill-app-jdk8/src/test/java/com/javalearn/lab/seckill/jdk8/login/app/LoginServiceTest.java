package com.javalearn.lab.seckill.jdk8.login.app;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.seckill.jdk8.login.api.LoginResponse;
import com.javalearn.lab.seckill.jdk8.login.config.LoginProperties;
import com.javalearn.lab.seckill.jdk8.support.FakeRedis;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The mock-login token must land in the SHARED Redis exactly like the JDK 21
 * peers: {@code verify.sh} logs in through Nginx (app-3 included) and asserts
 * {@code login:token:<token> -> userId} with a positive TTL.
 */
class LoginServiceTest {

    private final FakeRedis redis = new FakeRedis();

    private LoginProperties properties(String prefix, long ttlSeconds) {
        LoginProperties properties = new LoginProperties();
        properties.setTokenKeyPrefix(prefix);
        properties.setTokenTtlSeconds(ttlSeconds);
        return properties;
    }

    @Test
    void storesTokenToUserWithConfiguredTtl() {
        LoginService service = new LoginService(redis.template(),
                properties("login:token:", 1800L));

        LoginResponse response = service.login("u1");

        assertThat(response.getToken()).isNotBlank();
        assertThat(UUID.fromString(response.getToken())).isNotNull();
        assertThat(response.getExpiresInSeconds()).isEqualTo(1800L);
        String key = "login:token:" + response.getToken();
        assertThat(redis.get(key)).isEqualTo("u1");
        assertThat(redis.template().getExpire(key)).isBetween(1799L, 1800L);
    }

    @Test
    void blankUserIdIsRejectedWithoutWritingAnything() {
        LoginService service = new LoginService(redis.template(),
                properties("login:token:", 1800L));

        assertThatThrownBy(() -> service.login("   "))
                .isInstanceOf(BizException.class)
                .hasMessage("userId is required");
        assertThatThrownBy(() -> service.login(null))
                .isInstanceOf(BizException.class)
                .hasMessage("userId is required");
        redis.flushDb();
    }

    @Test
    void validateRejectsMissingPrefixAndNonPositiveTtl() {
        LoginProperties missingPrefix = new LoginProperties();
        missingPrefix.setTokenTtlSeconds(1800L);
        assertThatThrownBy(missingPrefix::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.login.token-key-prefix");

        LoginProperties zeroTtl = new LoginProperties();
        zeroTtl.setTokenKeyPrefix("login:token:");
        zeroTtl.setTokenTtlSeconds(0L);
        assertThatThrownBy(zeroTtl::validate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("app.login.token-ttl-seconds");
    }
}
