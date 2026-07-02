package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 商品实体，对应后端 com.ecommerce.product.entity.Product
 */
@Serializable
data class Product(
    val id: Long,
    val sellerId: Long? = null,
    val categoryId: Long? = null,
    val name: String,
    val description: String? = null,
    val price: Double,
    val originalPrice: Double? = null,
    val stock: Int = 0,
    val sales: Int = 0,
    val mainImage: String? = null,
    val images: String? = null,
    val status: Int = 1
)

/**
 * 商品分类，对应后端 Category
 */
@Serializable
data class Category(
    val id: Long,
    val name: String,
    val parentId: Long? = null,
    val level: Int = 1,
    val sort: Int = 0,
    val icon: String? = null,
    val status: Int = 1,
    val children: List<Category>? = null
)

/**
 * 商品新增/修改请求，对应后端 ProductRequest
 */
@Serializable
data class ProductRequest(
    val categoryId: Long,
    val name: String,
    val description: String? = null,
    val price: Double,
    val originalPrice: Double? = null,
    val stock: Int,
    val mainImage: String? = null,
    val images: String? = null
)
