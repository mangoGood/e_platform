package com.ecommerce.core.network

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 全局会话事件总线：把"必须重新登录"这件事从网络层广播到 UI 层。
 *
 * 刷新令牌也失效时，[TokenRefresher] 会调用 [notifyForcedLogout]，
 * 各 App 的导航层订阅 [forcedLogout] 后跳转登录页。
 *
 * 用 [MutableSharedFlow.tryEmit] 而不是 `emit`：发射点在 OkHttp 的
 * Authenticator 线程上，那里不是协程上下文，不能挂起。
 */
@Singleton
class SessionManager @Inject constructor() {

    private val _forcedLogout = MutableSharedFlow<String>(
        replay = 1,
        extraBufferCapacity = 1,
        onBufferOverflow = BufferOverflow.DROP_OLDEST
    )

    /**
     * 强制登出事件流，元素是展示给用户的中文原因。
     *
     * `replay = 1`：登出发生时 UI 可能还没订阅（例如 App 在后台），
     * 保留最后一条保证回到前台时仍能跳转。
     */
    val forcedLogout: SharedFlow<String> = _forcedLogout.asSharedFlow()

    /**
     * 广播强制登出。可在任意线程调用。
     *
     * @param reason 展示给用户的中文原因
     */
    fun notifyForcedLogout(reason: String = "登录已过期，请重新登录") {
        _forcedLogout.tryEmit(reason)
    }

    /**
     * 消费掉已保留的登出事件，避免重新订阅时重复跳转。
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    fun consumeForcedLogout() {
        _forcedLogout.resetReplayCache()
    }
}
