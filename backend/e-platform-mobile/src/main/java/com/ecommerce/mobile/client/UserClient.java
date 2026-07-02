package com.ecommerce.mobile.client;

import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.dto.LoginRequest;
import com.ecommerce.mobile.dto.LoginResponse;
import com.ecommerce.mobile.dto.RegisterRequest;
import com.ecommerce.mobile.dto.UserInfo;
import org.springframework.cloud.openfeign.FeignClient;
import org.springframework.web.bind.annotation.*;

/**
 * 用户服务 Feign 客户端
 */
@FeignClient(name = "e-platform-user", url = "${service.user.url:http://localhost:8085}")
public interface UserClient {

    @PostMapping("/user/login")
    Result<LoginResponse> login(@RequestBody LoginRequest request);

    @PostMapping("/user/register")
    Result<Void> register(@RequestBody RegisterRequest request);

    @GetMapping("/user/info/{userId}")
    Result<UserInfo> getUserInfo(@PathVariable("userId") Long userId);
}
