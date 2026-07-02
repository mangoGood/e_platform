package com.ecommerce.mobile.dto;

import lombok.Data;

/**
 * 注册请求
 */
@Data
public class RegisterRequest {
    private String username;
    private String password;
    private String nickname;
    private String phone;
    private Integer userType;
}
