package com.ecommerce.core.network

import com.ecommerce.core.model.Address
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.CanReviewVO
import com.ecommerce.core.model.Cart
import com.ecommerce.core.model.Category
import com.ecommerce.core.model.CommentAskDTO
import com.ecommerce.core.model.CommentCreateDTO
import com.ecommerce.core.model.CommentReplyDTO
import com.ecommerce.core.model.CommentTreeVO
import com.ecommerce.core.model.CommentUpdateDTO
import com.ecommerce.core.model.CommentVO
import com.ecommerce.core.model.CreateOrderRequest
import com.ecommerce.core.model.LoginRequest
import com.ecommerce.core.model.LoginResponse
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderItem
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.model.Product
import com.ecommerce.core.model.ProductRequest
import com.ecommerce.core.model.UserProfile
import retrofit2.Response
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/*
 * ============================================================================
 * 全部接口统一返回 `Response<ApiResponse<T>>` 而不是裸 `ApiResponse<T>`。
 *
 * 原因：裸返回拿不到 HTTP 状态码，也读不到 errorBody——
 * 后端把中文错误文案放在错误响应体的 `message` 字段里，
 * 不包 Response 就只能给用户看 Retrofit 抛出的英文异常。
 *
 * 空数据接口（删除/更新等）一律用 `ApiResponse<Unit>`，**绝不用 `Void`**：
 * Retrofit + Kotlin 下 `Void` 遇到空响应体会触发
 * "Unable to create converter / Expected a value but was NULL" 一类的问题，
 * `Unit` 在 kotlinx.serialization 里有内建 serializer，行为稳定。
 * 解包时对这类接口用 `requireSuccess()` 而不是 `requireData()`。
 * ============================================================================
 */

/**
 * 用户相关 API
 */
interface UserApi {

    @POST("user/login")
    suspend fun login(@Body request: LoginRequest): Response<ApiResponse<LoginResponse>>

    /**
     * 登出。后端会把当前 access token 的 jti 写进 Redis 黑名单，
     * 所以**必须调用**——只在本地 clear 的话旧 token 在服务端仍然有效。
     *
     * token 从 `Authorization` 头读取，网关的 HeaderSanitizer 会透传该头。
     */
    @POST("user/logout")
    suspend fun logout(): Response<ApiResponse<Unit>>

    @POST("user/register")
    suspend fun register(@Body request: LoginRequest): Response<ApiResponse<Unit>>

    /** 注意是带路径参数的 `/user/info/{userId}`，没有 `/user/info` 这个端点 */
    @GET("user/info/{userId}")
    suspend fun getUserInfo(@Path("userId") userId: Long): Response<ApiResponse<UserProfile>>

    @GET("user/perms/{userId}")
    suspend fun getUserPermissions(
        @Path("userId") userId: Long
    ): Response<ApiResponse<Map<String, List<String>>>>
}

/**
 * 商品相关 API
 */
interface ProductApi {

    @GET("product/list")
    suspend fun getProductList(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 10,
        @Query("categoryId") categoryId: Long? = null,
        @Query("keyword") keyword: String? = null
    ): Response<ApiResponse<PageResult<Product>>>

    @GET("product/{id}")
    suspend fun getProductById(@Path("id") id: Long): Response<ApiResponse<Product>>

    @GET("product/batch")
    suspend fun getProductsByIds(@Query("ids") ids: String): Response<ApiResponse<List<Product>>>

    @GET("product/seller/{sellerId}")
    suspend fun getProductsBySellerId(
        @Path("sellerId") sellerId: Long
    ): Response<ApiResponse<List<Product>>>

    @POST("product/add")
    suspend fun addProduct(@Body request: ProductRequest): Response<ApiResponse<Unit>>

    @PUT("product/{id}")
    suspend fun updateProduct(
        @Path("id") id: Long,
        @Body request: ProductRequest
    ): Response<ApiResponse<Unit>>

    @DELETE("product/{id}")
    suspend fun deleteProduct(@Path("id") id: Long): Response<ApiResponse<Unit>>
}

/**
 * 商品评论 API（三级评论：L1 买家评价 / L2 卖家回复 / L3 第三方追问）
 */
interface CommentApi {

    /**
     * 商品评论树。
     *
     * 返回的 `data` 是 [CommentTreeVO]，评论列表在 **`data.comments.records`**，
     * 比回复列表的 `data.records` 多一层，取错会渲染空白且不报错。
     *
     * @param sort    排序方式，后端默认 `latest`
     * @param askSize 每条评价内联返回几条追问
     */
    @GET("comment/product/{productId}")
    suspend fun getProductComments(
        @Path("productId") productId: Long,
        @Query("pageNum") pageNum: Int = 1,
        @Query("pageSize") pageSize: Int = 10,
        @Query("sort") sort: String = "latest",
        @Query("askSize") askSize: Int = 3
    ): Response<ApiResponse<CommentTreeVO>>

    /**
     * 某条 L1 评价下的追问分页列表。
     *
     * 返回的 `data` 直接就是 [PageResult]，列表在 **`data.records`**（注意与上面不同层级）。
     */
    @GET("comment/{rootId}/replies")
    suspend fun getReplies(
        @Path("rootId") rootId: Long,
        @Query("pageNum") pageNum: Int = 1,
        @Query("pageSize") pageSize: Int = 10
    ): Response<ApiResponse<PageResult<CommentVO>>>

    @GET("comment/can-review")
    suspend fun canReview(@Query("productId") productId: Long): Response<ApiResponse<CanReviewVO>>

    @POST("comment")
    suspend fun createComment(@Body dto: CommentCreateDTO): Response<ApiResponse<CommentVO>>

    /** 卖家回复 L2。请求体字段名是 **`parentId`**（见 [CommentReplyDTO]） */
    @POST("comment/reply")
    suspend fun replyComment(@Body dto: CommentReplyDTO): Response<ApiResponse<CommentVO>>

    /** 第三方追问 L3。请求体字段名是 **`commentId`**（见 [CommentAskDTO]） */
    @POST("comment/ask")
    suspend fun askComment(@Body dto: CommentAskDTO): Response<ApiResponse<CommentVO>>

    @PUT("comment/{id}")
    suspend fun updateComment(
        @Path("id") id: Long,
        @Body dto: CommentUpdateDTO
    ): Response<ApiResponse<CommentVO>>

    /** 删除接口用 `ApiResponse<Unit>`，不要用 `Void` */
    @DELETE("comment/{id}")
    suspend fun deleteComment(@Path("id") id: Long): Response<ApiResponse<Unit>>
}

/**
 * 分类相关 API
 */
interface CategoryApi {

    @GET("category/tree")
    suspend fun getCategoryTree(): Response<ApiResponse<List<Category>>>
}

/**
 * 购物车相关 API
 */
interface CartApi {

    @POST("order/cart/add")
    suspend fun addToCart(
        @Query("productId") productId: Long,
        @Query("quantity") quantity: Int
    ): Response<ApiResponse<Unit>>

    @PUT("order/cart")
    suspend fun updateCartQuantity(
        @Query("productId") productId: Long,
        @Query("quantity") quantity: Int
    ): Response<ApiResponse<Unit>>

    @DELETE("order/cart/{productId}")
    suspend fun removeFromCart(@Path("productId") productId: Long): Response<ApiResponse<Unit>>

    @DELETE("order/cart")
    suspend fun clearCart(): Response<ApiResponse<Unit>>

    @GET("order/cart")
    suspend fun getCart(): Response<ApiResponse<List<Cart>>>
}

/**
 * 订单相关 API
 *
 * ⚠️ 没有 `order/list` 这个端点——它会被 `GET order/{orderId}` 捕获，
 * 然后因为 "list" 无法转成 Long 报 400 类型转换错误。买家列表用 `order/user`，
 * 卖家列表用 `order/seller`。
 */
interface OrderApi {

    /**
     * 创建订单。
     *
     * 网关对本端点有秒杀排队保护，可能返回 **HTTP 202**；
     * 排队与重发由 [QueueInterceptor] 在网络层透明处理，业务层无感知。
     *
     * @param queueToken 排队就绪后重发时携带；正常下单传 null
     */
    @POST("order/create")
    suspend fun createOrder(
        @Body request: CreateOrderRequest,
        @Header("X-Queue-Token") queueToken: String? = null
    ): Response<ApiResponse<List<OrderVO>>>

    @POST("order/pay/{orderId}")
    suspend fun payOrder(@Path("orderId") orderId: Long): Response<ApiResponse<Unit>>

    /**
     * 买家确认收货。
     *
     * 端点已从旧的 `order/receive/{orderId}` 迁移到 `order/{orderId}/confirm`。
     * 后端新旧并存，但新端点才是收敛后的规范路径。
     */
    @POST("order/{orderId}/confirm")
    suspend fun confirmOrder(@Path("orderId") orderId: Long): Response<ApiResponse<Unit>>

    /**
     * 卖家发货。
     *
     * 端点已从旧的 `order/deliver/{orderId}` 迁移到 `order/{orderId}/ship`。
     */
    @POST("order/{orderId}/ship")
    suspend fun shipOrder(@Path("orderId") orderId: Long): Response<ApiResponse<Unit>>

    @POST("order/cancel/{orderId}")
    suspend fun cancelOrder(@Path("orderId") orderId: Long): Response<ApiResponse<Unit>>

    @GET("order/{orderId}")
    suspend fun getOrderById(@Path("orderId") orderId: Long): Response<ApiResponse<Order>>

    /** 买家订单列表 */
    @GET("order/user")
    suspend fun getOrdersByUserId(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 10
    ): Response<ApiResponse<PageResult<OrderVO>>>

    /** 卖家订单列表 */
    @GET("order/seller")
    suspend fun getOrdersBySellerId(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 10
    ): Response<ApiResponse<PageResult<OrderVO>>>

    @GET("order/items/{orderId}")
    suspend fun getOrderItems(@Path("orderId") orderId: Long): Response<ApiResponse<List<OrderItem>>>
}

/**
 * 收货地址相关 API
 */
interface AddressApi {

    @GET("address/list")
    suspend fun getAddresses(): Response<ApiResponse<List<Address>>>

    @GET("address/{id}")
    suspend fun getAddress(@Path("id") id: Long): Response<ApiResponse<Address>>

    @POST("address")
    suspend fun addAddress(@Body address: Address): Response<ApiResponse<Address>>

    @PUT("address")
    suspend fun updateAddress(@Body address: Address): Response<ApiResponse<Address>>

    @DELETE("address/{id}")
    suspend fun deleteAddress(@Path("id") id: Long): Response<ApiResponse<Unit>>

    @PUT("address/default/{id}")
    suspend fun setDefault(@Path("id") id: Long): Response<ApiResponse<Unit>>
}
