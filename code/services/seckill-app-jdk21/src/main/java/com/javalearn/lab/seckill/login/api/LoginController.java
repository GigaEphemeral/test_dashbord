package com.javalearn.lab.seckill.login.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.login.app.LoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock login for the demo and k6. A blank/missing userId is a 400 through the
 * shared {@code Result} envelope.
 */
@RestController
@RequestMapping("/api")
public class LoginController {

    private final LoginService loginService;

    public LoginController(LoginService loginService) {
        this.loginService = loginService;
    }

    @PostMapping("/login")
    public Result<LoginResponse> login(@RequestBody(required = false) LoginRequest request) {
        return Result.success(loginService.login(request == null ? null : request.userId()));
    }
}
