package com.javalearn.lab.seckill.jdk8.login.api;

import com.javalearn.lab.common.BizException;
import com.javalearn.lab.common.CommonErrorCode;
import com.javalearn.lab.common.Result;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Mock login for k6 and the demo. The JDK 8 peer keeps the same path and JSON
 * envelope as the JDK 21 shell; it does not need Redis because the lab does not
 * enforce the bearer token yet.
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    private final long tokenTtlSeconds;

    public LoginController(@Value("${app.login.token-ttl-seconds:3600}") long tokenTtlSeconds) {
        this.tokenTtlSeconds = tokenTtlSeconds;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody(required = false) LoginRequest request) {
        String userId = request == null ? null : request.getUserId();
        if (userId == null || userId.trim().isEmpty()) {
            throw new BizException(CommonErrorCode.INVALID_ARGUMENT, "userId is required");
        }
        return Result.success(new LoginResponse(UUID.randomUUID().toString(), tokenTtlSeconds));
    }
}
