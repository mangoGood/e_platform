package com.ecommerce.core.network

import com.ecommerce.core.BuildConfig
import com.jakewharton.retrofit2.converter.kotlinx.serialization.asConverterFactory
import okhttp3.Authenticator
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import retrofit2.Retrofit
import java.util.concurrent.TimeUnit

/**
 * 网络客户端构建器，由 Hilt 提供单例。
 *
 * ## 两套 Client 的分工
 * - [provideAuthenticatedOkHttpClient]：业务请求用。挂 [AuthInterceptor]（加 token）、
 *   [QueueInterceptor]（处理秒杀 202）和 [TokenAuthenticator]（401 自动刷新）。
 * - [provideBareOkHttpClient]：**只给刷新令牌用**。不挂任何鉴权组件。
 *   刷新请求一旦走了带 Authenticator 的 client，刷新失败返回 401 会再次触发刷新，
 *   直接无限递归。这两套 client 必须物理隔离。
 */
object NetworkFactory {

    /** 后端网关地址（按构建类型区分：debug 用模拟器回环地址，release 用生产地址） */
    val BASE_URL: String = BuildConfig.BASE_URL

    /** 用于把相对图片路径补全成绝对 URL 的站点根地址，例如 `http://10.0.2.2:8088` */
    val SITE_ROOT_URL: String = BASE_URL.removeSuffix("/api/").removeSuffix("/")

    private const val CONNECT_TIMEOUT_SECONDS = 15L
    private const val READ_TIMEOUT_SECONDS = 20L
    private const val WRITE_TIMEOUT_SECONDS = 20L

    /**
     * 刷新令牌专用的读超时。比业务请求略短，避免 Authenticator 长时间占住 OkHttp 线程。
     */
    private const val REFRESH_TIMEOUT_SECONDS = 10L

    /**
     * 构造日志拦截器。release 构建降级到 NONE，避免把 token 和请求体写进日志。
     *
     * @return 日志拦截器
     */
    private fun loggingInterceptor(): HttpLoggingInterceptor =
        HttpLoggingInterceptor().apply {
            level = if (BuildConfig.DEBUG) {
                HttpLoggingInterceptor.Level.BODY
            } else {
                HttpLoggingInterceptor.Level.NONE
            }
        }

    /**
     * 业务请求用的 OkHttpClient。
     *
     * 拦截器顺序有讲究：
     * 1. [AuthInterceptor] 先加 `Authorization` 头；
     * 2. [QueueInterceptor] 再处理 202——它内部重发原始请求时，请求上已经带好了 token；
     * 3. 日志放最后，能记录到最终发出的请求。
     *
     * @param authInterceptor  token 注入拦截器
     * @param queueInterceptor 秒杀排队拦截器
     * @param authenticator    401 刷新 Authenticator
     * @return 已配置的 client
     */
    fun provideAuthenticatedOkHttpClient(
        authInterceptor: AuthInterceptor,
        queueInterceptor: QueueInterceptor,
        authenticator: Authenticator
    ): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(authInterceptor)
        .addInterceptor(queueInterceptor)
        .addInterceptor(loggingInterceptor())
        .authenticator(authenticator)
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(READ_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(WRITE_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 裸 OkHttpClient：**没有** AuthInterceptor、没有 QueueInterceptor、没有 Authenticator。
     *
     * 专供 [RefreshApi] 使用。这是防止"刷新失败又触发刷新"无限递归的根本手段。
     *
     * @return 无鉴权组件的 client
     */
    fun provideBareOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(loggingInterceptor())
        .connectTimeout(CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .readTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .writeTimeout(REFRESH_TIMEOUT_SECONDS, TimeUnit.SECONDS)
        .build()

    /**
     * 构造 Retrofit。
     *
     * @param client 底层 OkHttpClient
     * @return Retrofit 实例
     */
    fun provideRetrofit(client: OkHttpClient): Retrofit = Retrofit.Builder()
        .baseUrl(BASE_URL)
        .client(client)
        .addConverterFactory(
            JsonConfig.json.asConverterFactory("application/json".toMediaType())
        )
        .build()

    fun provideUserApi(retrofit: Retrofit): UserApi = retrofit.create(UserApi::class.java)
    fun provideRefreshApi(retrofit: Retrofit): RefreshApi = retrofit.create(RefreshApi::class.java)
    fun provideProductApi(retrofit: Retrofit): ProductApi = retrofit.create(ProductApi::class.java)
    fun provideCommentApi(retrofit: Retrofit): CommentApi = retrofit.create(CommentApi::class.java)
    fun provideCategoryApi(retrofit: Retrofit): CategoryApi = retrofit.create(CategoryApi::class.java)
    fun provideCartApi(retrofit: Retrofit): CartApi = retrofit.create(CartApi::class.java)
    fun provideOrderApi(retrofit: Retrofit): OrderApi = retrofit.create(OrderApi::class.java)
    fun provideAddressApi(retrofit: Retrofit): AddressApi = retrofit.create(AddressApi::class.java)
}
