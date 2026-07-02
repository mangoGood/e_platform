package com.ecommerce.core.network

import com.ecommerce.core.model.Address
import com.ecommerce.core.model.ApiResponse
import com.ecommerce.core.model.Cart
import com.ecommerce.core.model.Category
import com.ecommerce.core.model.CreateOrderRequest
import com.ecommerce.core.model.LoginRequest
import com.ecommerce.core.model.LoginResponse
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderItem
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.model.Product
import com.ecommerce.core.model.ProductRequest
import retrofit2.http.Body
import retrofit2.http.DELETE
import retrofit2.http.GET
import retrofit2.http.POST
import retrofit2.http.PUT
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * 用户相关 API
 */
interface UserApi {
    @POST("user/login")
    suspend fun login(@Body request: LoginRequest): ApiResponse<LoginResponse>
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
    ): ApiResponse<PageResult<Product>>

    @GET("product/{id}")
    suspend fun getProductById(@Path("id") id: Long): ApiResponse<Product>

    @GET("product/batch")
    suspend fun getProductsByIds(@Query("ids") ids: String): ApiResponse<List<Product>>

    @GET("product/seller/{sellerId}")
    suspend fun getProductsBySellerId(@Path("sellerId") sellerId: Long): ApiResponse<List<Product>>

    @POST("product/add")
    suspend fun addProduct(@Body request: ProductRequest): ApiResponse<Void>

    @PUT("product/{id}")
    suspend fun updateProduct(
        @Path("id") id: Long,
        @Body request: ProductRequest
    ): ApiResponse<Void>

    @DELETE("product/{id}")
    suspend fun deleteProduct(@Path("id") id: Long): ApiResponse<Void>
}

/**
 * 分类相关 API
 */
interface CategoryApi {
    @GET("category/tree")
    suspend fun getCategoryTree(): ApiResponse<List<Category>>
}

/**
 * 购物车相关 API
 */
interface CartApi {
    @POST("order/cart/add")
    suspend fun addToCart(
        @Query("productId") productId: Long,
        @Query("quantity") quantity: Int
    ): ApiResponse<Void>

    @PUT("order/cart")
    suspend fun updateCartQuantity(
        @Query("productId") productId: Long,
        @Query("quantity") quantity: Int
    ): ApiResponse<Void>

    @DELETE("order/cart/{productId}")
    suspend fun removeFromCart(@Path("productId") productId: Long): ApiResponse<Void>

    @DELETE("order/cart")
    suspend fun clearCart(): ApiResponse<Void>

    @GET("order/cart")
    suspend fun getCart(): ApiResponse<List<Cart>>
}

/**
 * 订单相关 API
 */
interface OrderApi {
    @POST("order/create")
    suspend fun createOrder(@Body request: CreateOrderRequest): ApiResponse<List<OrderVO>>

    @POST("order/pay/{orderId}")
    suspend fun payOrder(@Path("orderId") orderId: Long): ApiResponse<Void>

    @POST("order/receive/{orderId}")
    suspend fun receiveOrder(@Path("orderId") orderId: Long): ApiResponse<Void>

    @POST("order/deliver/{orderId}")
    suspend fun deliverOrder(@Path("orderId") orderId: Long): ApiResponse<Void>

    @POST("order/cancel/{orderId}")
    suspend fun cancelOrder(@Path("orderId") orderId: Long): ApiResponse<Void>

    @GET("order/{orderId}")
    suspend fun getOrderById(@Path("orderId") orderId: Long): ApiResponse<Order>

    @GET("order/user")
    suspend fun getOrdersByUserId(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 10
    ): ApiResponse<PageResult<OrderVO>>

    @GET("order/seller")
    suspend fun getOrdersBySellerId(
        @Query("current") current: Int = 1,
        @Query("size") size: Int = 10
    ): ApiResponse<PageResult<OrderVO>>

    @GET("order/items/{orderId}")
    suspend fun getOrderItems(@Path("orderId") orderId: Long): ApiResponse<List<OrderItem>>
}

/**
 * 收货地址相关 API
 */
interface AddressApi {
    @GET("address/list")
    suspend fun getAddresses(): ApiResponse<List<Address>>

    @GET("address/{id}")
    suspend fun getAddress(@Path("id") id: Long): ApiResponse<Address>

    @POST("address")
    suspend fun addAddress(@Body address: Address): ApiResponse<Address>

    @PUT("address")
    suspend fun updateAddress(@Body address: Address): ApiResponse<Address>

    @DELETE("address/{id}")
    suspend fun deleteAddress(@Path("id") id: Long): ApiResponse<Void>

    @PUT("address/default/{id}")
    suspend fun setDefault(@Path("id") id: Long): ApiResponse<Void>
}
