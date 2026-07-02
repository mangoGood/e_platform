package com.ecommerce.mobile.dto;

import lombok.Data;

/**
 * 用户信息
 */
@Data
public class UserInfo {
    private Long id;
    private String username;
    private String nickname;
    private String phone;
    private String avatar;
    private Integer userType;
    private Integer status;
}
