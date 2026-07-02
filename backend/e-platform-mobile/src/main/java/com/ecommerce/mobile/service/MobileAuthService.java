package com.ecommerce.mobile.service;

import com.ecommerce.common.exception.BusinessException;
import com.ecommerce.common.result.Result;
import com.ecommerce.mobile.client.UserClient;
import com.ecommerce.mobile.context.UserContext;
import com.ecommerce.mobile.dto.LoginRequest;
import com.ecommerce.mobile.dto.LoginResponse;
import com.ecommerce.mobile.dto.RegisterRequest;
import com.ecommerce.mobile.dto.UserInfo;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 认证服务
 */
@Service
public class MobileAuthService {

    @Autowired
    private UserClient userClient;

    /**
     * 登录
     */
    public LoginResponse login(LoginRequest request) {
        Result<LoginResponse> result = userClient.login(request);
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BusinessException(result != null ? result.getMessage() : "登录失败");
        }
        return result.getData();
    }

    /**
     * 注册
     */
    public void register(RegisterRequest request) {
        Result<Void> result = userClient.register(request);
        if (result == null || !result.isSuccess()) {
            throw new BusinessException(result != null ? result.getMessage() : "注册失败");
        }
    }

    /**
     * 获取当前用户信息
     */
    public UserInfo getCurrentUser() {
        Long userId = UserContext.getUserId();
        Result<UserInfo> result = userClient.getUserInfo(userId);
        if (result == null || !result.isSuccess() || result.getData() == null) {
            throw new BusinessException("获取用户信息失败");
        }
        return result.getData();
    }
}
