package com.ecommerce.core.datastore

import android.content.Context
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey

/**
 * 安全存储 JWT / Refresh Token 与用户身份信息，底层是 [EncryptedSharedPreferences]。
 *
 * ## 为什么全部是同步阻塞 API（没有 suspend）
 * 本类的读写会被 `AuthInterceptor` 和 `TokenAuthenticator` 调用，
 * 而这两者都运行在 **OkHttp 的非协程线程**上。
 * 一旦给读写加 `suspend`，调用方就只能 `runBlocking`，
 * 既没有收益又多一层线程切换，还容易在 Authenticator 里造成死锁。
 * SharedPreferences 的读本身走内存缓存，同步调用是安全的。
 */
class TokenManager(context: Context) {

    private val masterKey = MasterKey.Builder(context)
        .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
        .build()

    private val prefs = EncryptedSharedPreferences.create(
        context,
        "e_platform_auth",
        masterKey,
        EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
        EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
    )

    /** 访问令牌（短时效）。请求头 `Authorization: Bearer <accessToken>` 用的就是它。 */
    var accessToken: String?
        get() = prefs.getString(KEY_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_TOKEN) else putString(KEY_TOKEN, value)
            }.apply()
        }

    /**
     * 刷新令牌（长时效，**轮换式**）。
     *
     * 后端每次刷新成功都会下发一个新的 refreshToken 并立刻作废旧的，
     * 所以刷新成功后必须把新值写回来，否则下一次刷新必定失败。
     */
    var refreshToken: String?
        get() = prefs.getString(KEY_REFRESH_TOKEN, null)
        set(value) {
            prefs.edit().apply {
                if (value == null) remove(KEY_REFRESH_TOKEN) else putString(KEY_REFRESH_TOKEN, value)
            }.apply()
        }

    /** access token 有效期（秒），来自登录/刷新响应的 `expiresIn` */
    var expiresIn: Long
        get() = prefs.getLong(KEY_EXPIRES_IN, 0L)
        set(value) = prefs.edit().putLong(KEY_EXPIRES_IN, value).apply()

    /** access token 的本地保存时刻（毫秒时间戳），用于估算是否临期 */
    var tokenSavedAt: Long
        get() = prefs.getLong(KEY_TOKEN_SAVED_AT, 0L)
        set(value) = prefs.edit().putLong(KEY_TOKEN_SAVED_AT, value).apply()

    var userId: Long
        get() = prefs.getLong(KEY_USER_ID, -1L)
        set(value) = prefs.edit().putLong(KEY_USER_ID, value).apply()

    var userType: Int
        get() = prefs.getInt(KEY_USER_TYPE, 0)
        set(value) = prefs.edit().putInt(KEY_USER_TYPE, value).apply()

    var username: String?
        get() = prefs.getString(KEY_USERNAME, null)
        set(value) = prefs.edit().putString(KEY_USERNAME, value).apply()

    /**
     * 角色列表。用逗号拼接存储而不是 `putStringSet`——后者不保证顺序，
     * 且在部分 ROM 上返回的是内部可变集合，改一次就污染缓存。
     */
    var roles: List<String>
        get() = prefs.getString(KEY_ROLES, null).toStringList()
        set(value) = prefs.edit().putString(KEY_ROLES, value.joinToString(SEPARATOR)).apply()

    /** 权限点列表，供按钮级权限控制使用 */
    var perms: List<String>
        get() = prefs.getString(KEY_PERMS, null).toStringList()
        set(value) = prefs.edit().putString(KEY_PERMS, value.joinToString(SEPARATOR)).apply()

    val isLoggedIn: Boolean
        get() = !accessToken.isNullOrEmpty() && userId > 0L

    /** 本地是否还有可用于刷新的 refreshToken */
    val canRefresh: Boolean
        get() = !refreshToken.isNullOrEmpty()

    /**
     * 一次性写入登录/刷新得到的令牌对。
     *
     * @param accessToken  新的访问令牌
     * @param refreshToken 新的刷新令牌；传 null 表示后端本次没有轮换，保留旧值
     * @param expiresIn    访问令牌有效期（秒）
     */
    fun saveTokens(accessToken: String, refreshToken: String?, expiresIn: Long?) {
        prefs.edit().apply {
            putString(KEY_TOKEN, accessToken)
            if (!refreshToken.isNullOrEmpty()) {
                putString(KEY_REFRESH_TOKEN, refreshToken)
            }
            if (expiresIn != null && expiresIn > 0L) {
                putLong(KEY_EXPIRES_IN, expiresIn)
            }
            putLong(KEY_TOKEN_SAVED_AT, System.currentTimeMillis())
        }.apply()
    }

    /**
     * **只清除 access token**，refreshToken 原封不动保留。
     *
     * 刷新流程专用：401 之后要先把已被服务端拒绝的 access token 作废，
     * 再拿 refreshToken 去换新的。
     *
     * 改造前只有一个全清的 `clear()`，在刷新流程里调用会把 refreshToken 一起删掉，
     * 导致紧接着的刷新请求拿不到凭据、永远失败——这正是本方法存在的唯一理由。
     */
    fun clearAccessToken() {
        prefs.edit()
            .remove(KEY_TOKEN)
            .remove(KEY_EXPIRES_IN)
            .remove(KEY_TOKEN_SAVED_AT)
            .apply()
        // 刻意不 remove(KEY_REFRESH_TOKEN)：刷新流程依赖它。
    }

    /**
     * 清除**全部**登录态（含 refreshToken、身份、角色权限）。
     *
     * 仅用于两种场景：用户主动登出、refreshToken 也失效导致必须重新登录。
     */
    fun clearAll() {
        prefs.edit().clear().apply()
    }

    /**
     * 把逗号拼接的字符串还原成列表。
     *
     * @return 非空元素列表；输入为空时返回空列表
     */
    private fun String?.toStringList(): List<String> {
        if (this.isNullOrEmpty()) return emptyList()
        return split(SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }
    }

    companion object {
        private const val SEPARATOR = ","
        private const val KEY_TOKEN = "jwt_token"
        private const val KEY_REFRESH_TOKEN = "refresh_token"
        private const val KEY_EXPIRES_IN = "expires_in"
        private const val KEY_TOKEN_SAVED_AT = "token_saved_at"
        private const val KEY_USER_ID = "user_id"
        private const val KEY_USER_TYPE = "user_type"
        private const val KEY_USERNAME = "username"
        private const val KEY_ROLES = "roles"
        private const val KEY_PERMS = "perms"
    }
}
