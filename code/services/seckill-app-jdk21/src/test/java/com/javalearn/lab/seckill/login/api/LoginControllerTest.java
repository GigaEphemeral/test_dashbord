package com.javalearn.lab.seckill.login.api;

import com.javalearn.lab.seckill.login.app.LoginService;
import com.javalearn.lab.seckill.login.config.LoginProperties;
import com.javalearn.lab.seckill.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * HTTP contract of the mock login: token shape, expiresInSeconds and a 400
 * envelope for a blank or missing userId.
 */
class LoginControllerTest {

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        ValueOperations<String, String> operations = mock(ValueOperations.class);
        when(redisTemplate.opsForValue()).thenReturn(operations);

        LoginProperties properties = new LoginProperties();
        properties.setTokenKeyPrefix("login:test:token:");
        properties.setTokenTtlSeconds(120L);
        LoginService loginService = new LoginService(redisTemplate, properties);

        mockMvc = MockMvcBuilders
                .standaloneSetup(new LoginController(loginService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .build();
    }

    @Test
    void loginReturnsTokenAndExpiresInSeconds() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("0"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.expiresInSeconds").value(120));
    }

    @Test
    void blankUserIdReturns400() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"   \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("userId is required"));
    }

    @Test
    void missingUserIdReturns400() throws Exception {
        mockMvc.perform(post("/api/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("40001"))
                .andExpect(jsonPath("$.message").value("userId is required"));
    }
}
