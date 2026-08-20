package com.ecommerce.user.dto;

import java.io.Serializable;

/**
 * 刷新接口的响应体。
 *
 * <p>字段名 {@code token} 与 {@link LoginResponse} 保持一致，
 * 客户端可以复用同一套"取 token 并落盘"的代码，不必为刷新场景写第二份解析逻辑。
 */
public class TokenPair implements Serializable {

    private static final long serialVersionUID = 1L;

    /** 新签发的 access token。 */
    private String token;

    /** 新签发的 refresh token（轮换后的值）。 */
    private String refreshToken;

    /** access token 有效期（秒）。 */
    private Long expiresIn;

    public TokenPair() {
    }

    /**
     * @param token        新的 access token
     * @param refreshToken 新的 refresh token
     * @param expiresIn    access token 有效期（秒）
     */
    public TokenPair(String token, String refreshToken, Long expiresIn) {
        this.token = token;
        this.refreshToken = refreshToken;
        this.expiresIn = expiresIn;
    }

    public String getToken() {
        return token;
    }

    public void setToken(String token) {
        this.token = token;
    }

    public String getRefreshToken() {
        return refreshToken;
    }

    public void setRefreshToken(String refreshToken) {
        this.refreshToken = refreshToken;
    }

    public Long getExpiresIn() {
        return expiresIn;
    }

    public void setExpiresIn(Long expiresIn) {
        this.expiresIn = expiresIn;
    }
}
