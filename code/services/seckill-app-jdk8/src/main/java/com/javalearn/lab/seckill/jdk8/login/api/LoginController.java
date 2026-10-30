package com.javalearn.lab.seckill.jdk8.login.api;

import com.javalearn.lab.common.Result;
import com.javalearn.lab.seckill.jdk8.login.app.LoginService;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mock login for k6 and the demo. Same path, JSON envelope and Redis-backed
 * token store as the JDK 21 peers: the login probe in verify.sh round-robins
 * over all three business instances through Nginx, so app-3 must write the
 * {@code login:token:<token> -> userId} key exactly like app-1/app-2.
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
        return Result.success(loginService.login(request == null ? null : request.getUserId()));
    }
}
