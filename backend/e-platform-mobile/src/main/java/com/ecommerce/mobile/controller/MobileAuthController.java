package com.ecommerce.mobile.controller;

import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.LoginRequest;
import com.ecommerce.mobile.dto.LoginResponse;
import com.ecommerce.mobile.dto.RegisterRequest;
import com.ecommerce.mobile.dto.UserInfo;
import com.ecommerce.mobile.service.MobileAuthService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

import javax.validation.Valid;

/**
 * 移动端认证接口
 */
@RestController
@RequestMapping("/mobile/auth")
public class MobileAuthController {

    @Autowired
    private MobileAuthService authService;

    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        return Result.success(authService.login(request));
    }

    @PostMapping("/register")
    public Result<Void> register(@Valid @RequestBody RegisterRequest request) {
        authService.register(request);
        return Result.success();
    }

    @GetMapping("/me")
    public Result<UserInfo> getCurrentUser() {
        return Result.success(authService.getCurrentUser());
    }
}
