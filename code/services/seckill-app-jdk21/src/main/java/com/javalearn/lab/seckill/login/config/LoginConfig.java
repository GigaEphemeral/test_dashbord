package com.javalearn.lab.seckill.login.config;

import com.javalearn.lab.seckill.login.app.LoginService;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * Wires the mock-login token store on the existing Redis connection.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(LoginProperties.class)
public class LoginConfig {

    @Bean
    public LoginService loginService(StringRedisTemplate redisTemplate, LoginProperties properties) {
        return new LoginService(redisTemplate, properties);
    }
}
