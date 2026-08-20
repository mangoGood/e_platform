package com.ecommerce.user.dto;

import javax.validation.constraints.NotBlank;
import java.io.Serializable;

/**
 * 刷新 Token 请求体：{@code {"refreshToken": "..."}}。
 */
public class RefreshTokenRequest implements Serializable {

    private static final long serialVersionUID = 1L;

    /** refresh token，由登录接口下发。 */
    @NotBlank(message = "refreshToken 不能为空")
    private String refreshToken;

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }
}
