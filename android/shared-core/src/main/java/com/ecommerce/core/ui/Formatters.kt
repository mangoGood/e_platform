package com.ecommerce.core.ui

import com.ecommerce.core.network.NetworkFactory
import java.text.DecimalFormat

private val priceFormat = DecimalFormat("0.00")

/**
 * 格式化价格为带两位小数的字符串（不含货币符号，调用处自行加 ¥）
 * 示例：12.5.formatPrice() -> "12.50"
 */
fun Double.formatPrice(): String = priceFormat.format(this)

fun Double?.formatPrice(): String = priceFormat.format(this ?: 0.0)

/**
 * 格式化价格为带 ¥ 符号的字符串
 * 示例：12.5.formatPriceWithSymbol() -> "¥12.50"
 */
fun Double?.formatPriceWithSymbol(): String = "¥${formatPrice()}"

/**
 * 将后端返回的图片相对路径补全为完整 URL
 * - 已是 http(s) 开头则原样返回
 * - 空值返回空字符串（Coil 会显示占位图）
 */
fun String?.toImageUrl(): String {
    if (this.isNullOrEmpty()) return ""
    return if (startsWith("http")) {
        this
    } else {
        val base = NetworkFactory.BASE_URL.trimEnd('/', 'a', 'p', 'i', '/')
        "$base$this"
    }
}

/**
 * 将后端返回的图片相对路径补全为完整 URL，空值返回 null
 * 供需要 nullable String 的场景使用
 */
fun String?.toImageUrlOrNull(): String? {
    if (this.isNullOrEmpty()) return null
    return toImageUrl()
}
