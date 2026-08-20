package com.ecommerce.core.di

import android.content.Context
import com.ecommerce.core.datastore.TokenManager
import com.ecommerce.core.network.AddressApi
import com.ecommerce.core.network.AuthInterceptor
import com.ecommerce.core.network.CartApi
import com.ecommerce.core.network.CategoryApi
import com.ecommerce.core.network.CommentApi
import com.ecommerce.core.network.NetworkFactory
import com.ecommerce.core.network.OrderApi
import com.ecommerce.core.network.ProductApi
import com.ecommerce.core.network.QueueInterceptor
import com.ecommerce.core.network.RefreshApi
import com.ecommerce.core.network.TokenAuthenticator
import com.ecommerce.core.network.TokenRefresher
import com.ecommerce.core.network.UserApi
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import javax.inject.Qualifier
import javax.inject.Singleton

/**
 * 标记「裸」网络组件：不带任何鉴权拦截器/Authenticator，只给刷新令牌用。
 */
@Qualifier
@Retention(AnnotationRetention.BINARY)
annotation class BareNetwork

/**
 * 核心网络依赖图。
 *
 * ## 依赖环是怎么被打破的
 * 朴素写法会成环：
 * ```
 * OkHttpClient -> TokenAuthenticator -> (要发刷新请求) -> Retrofit -> OkHttpClient
 * ```
 * kapt 阶段直接报 `Found a dependency cycle`。
 *
 * 这里用两个手段打破：
 * 1. **物理隔离**：刷新链路走 [BareNetwork] 限定符标记的独立 client/retrofit。
 *    裸 client 不依赖 [TokenAuthenticator]，所以
 *    `BareClient -> BareRetrofit -> RefreshApi -> TokenRefresher -> TokenAuthenticator -> AuthClient`
 *    是一条**单向链**，图上不成环。
 * 2. **`dagger.Lazy` 兜底**：[TokenRefresher] 用 `Lazy<RefreshApi>` 注入
 *    （见 TokenRefresher 构造参数）。即便后续有人把刷新链路改回主 Retrofit，
 *    Lazy 也会把实例化推迟到运行时首次使用，避免构造期成环。
 *
 * 顺带一提，这个隔离同时满足安全要求：刷新请求绝不能走带 Authenticator 的 client，
 * 否则刷新失败的 401 会再次触发刷新，无限递归。
 */
@Module
@InstallIn(SingletonComponent::class)
object CoreModule {

    @Provides
    @Singleton
    fun provideTokenManager(@ApplicationContext context: Context): TokenManager =
        TokenManager(context)

    // ---------------------------------------------------------------------
    // 刷新链路（裸，无鉴权组件）
    // ---------------------------------------------------------------------

    @Provides
    @Singleton
    @BareNetwork
    fun provideBareOkHttpClient(): OkHttpClient =
        NetworkFactory.provideBareOkHttpClient()

    @Provides
    @Singleton
    @BareNetwork
    fun provideBareRetrofit(@BareNetwork client: OkHttpClient): Retrofit =
        NetworkFactory.provideRetrofit(client)

    @Provides
    @Singleton
    fun provideRefreshApi(@BareNetwork retrofit: Retrofit): RefreshApi =
        NetworkFactory.provideRefreshApi(retrofit)

    // ---------------------------------------------------------------------
    // 业务链路
    // ---------------------------------------------------------------------

    @Provides
    @Singleton
    fun provideAuthInterceptor(tokenManager: TokenManager): AuthInterceptor =
        AuthInterceptor(tokenManager)

    @Provides
    @Singleton
    fun provideQueueInterceptor(): QueueInterceptor = QueueInterceptor()

    @Provides
    @Singleton
    fun provideTokenAuthenticator(tokenRefresher: TokenRefresher): TokenAuthenticator =
        TokenAuthenticator(tokenRefresher)

    @Provides
    @Singleton
    fun provideOkHttpClient(
        authInterceptor: AuthInterceptor,
        queueInterceptor: QueueInterceptor,
        authenticator: TokenAuthenticator
    ): OkHttpClient = NetworkFactory.provideAuthenticatedOkHttpClient(
        authInterceptor = authInterceptor,
        queueInterceptor = queueInterceptor,
        authenticator = authenticator
    )

    @Provides
    @Singleton
    fun provideRetrofit(client: OkHttpClient): Retrofit =
        NetworkFactory.provideRetrofit(client)

    @Provides
    @Singleton
    fun provideUserApi(retrofit: Retrofit): UserApi =
        NetworkFactory.provideUserApi(retrofit)

    @Provides
    @Singleton
    fun provideProductApi(retrofit: Retrofit): ProductApi =
        NetworkFactory.provideProductApi(retrofit)

    @Provides
    @Singleton
    fun provideCommentApi(retrofit: Retrofit): CommentApi =
        NetworkFactory.provideCommentApi(retrofit)

    @Provides
    @Singleton
    fun provideCategoryApi(retrofit: Retrofit): CategoryApi =
        NetworkFactory.provideCategoryApi(retrofit)

    @Provides
    @Singleton
    fun provideCartApi(retrofit: Retrofit): CartApi =
        NetworkFactory.provideCartApi(retrofit)

    @Provides
    @Singleton
    fun provideOrderApi(retrofit: Retrofit): OrderApi =
        NetworkFactory.provideOrderApi(retrofit)

    @Provides
    @Singleton
    fun provideAddressApi(retrofit: Retrofit): AddressApi =
        NetworkFactory.provideAddressApi(retrofit)
}
