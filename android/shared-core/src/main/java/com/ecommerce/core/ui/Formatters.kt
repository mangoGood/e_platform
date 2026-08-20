package com.ecommerce.core.ui

import com.ecommerce.core.network.NetworkFactory
import java.text.DecimalFormat
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.time.format.DateTimeParseException

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
 *
 * ## 修复记录：这里原先有一个潜伏 Bug
 * 原实现是：
 * ```kotlin
 * NetworkFactory.BASE_URL.trimEnd('/', 'a', 'p', 'i', '/')
 * ```
 * `trimEnd(vararg Char)` 的语义是**反复剥掉"落在给定字符集里"的尾字符**，
 * 而不是去掉 `/api/` 这个子串。只要域名尾部的字母恰好落在 `{a,p,i}` 里就会被啃穿：
 * `https://api.example.ai/` -> `https://api.example.`（`i`、`a` 被连续剥掉）。
 * debug 环境的 `http://10.0.2.2:8088/api/` 结尾是数字，恰好看不出问题，
 * 一旦切到 release 的真实域名就会拼出错误的图片地址。
 *
 * 现在改用 [String.removeSuffix]，它按**子串**匹配，语义才是"去掉结尾的 /api/"。
 * 该逻辑已上收到 [NetworkFactory.SITE_ROOT_URL]。
 */
fun String?.toImageUrl(): String {
    if (this.isNullOrEmpty()) return ""
    if (startsWith("http")) return this
    val base = NetworkFactory.SITE_ROOT_URL
    return if (startsWith("/")) "$base$this" else "$base/$this"
}

/**
 * 将后端返回的图片相对路径补全为完整 URL，空值返回 null
 * 供需要 nullable String 的场景使用
 */
fun String?.toImageUrlOrNull(): String? {
    if (this.isNullOrEmpty()) return null
    return toImageUrl()
}

/**
 * 后端 `LocalDateTime` 经 Spring Boot 默认配置序列化后的形态，例如 `2024-06-24T10:30:00`。
 */
private val ISO_LOCAL_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ISO_LOCAL_DATE_TIME

/** 展示用的日期时间格式 */
private val DISPLAY_DATE_TIME: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** 展示用的日期格式 */
private val DISPLAY_DATE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")

/**
 * 把后端下发的 ISO-8601 时间串格式化成 `yyyy-MM-dd HH:mm`。
 *
 * 用 JDK 自带的 `java.time`（minSdk 26 原生支持），**不引入任何新依赖**。
 * 解析失败时原样返回，绝不抛异常——时间只是展示信息，不值得让整个页面崩掉。
 *
 * @return 格式化后的字符串；输入为空时返回空串
 */
fun String?.formatDateTime(): String = formatWith(DISPLAY_DATE_TIME)

/**
 * 把后端下发的 ISO-8601 时间串格式化成 `yyyy-MM-dd`。
 *
 * @return 格式化后的字符串；输入为空时返回空串
 */
fun String?.formatDate(): String = formatWith(DISPLAY_DATE)

/**
 * 通用的时间串格式化。
 *
 * @param formatter 目标格式
 * @return 格式化后的字符串；解析失败时返回原串
 */
private fun String?.formatWith(formatter: DateTimeFormatter): String {
    val raw = this?.trim()
    if (raw.isNullOrEmpty()) return ""
    return try {
        LocalDateTime.parse(raw, ISO_LOCAL_DATE_TIME).format(formatter)
    } catch (e: DateTimeParseException) {
        // 后端偶尔会下发 "yyyy-MM-dd HH:mm:ss" 这种带空格的变体，兜底再试一次
        try {
            LocalDateTime.parse(raw.replace(' ', 'T'), ISO_LOCAL_DATE_TIME).format(formatter)
        } catch (e2: DateTimeParseException) {
            raw
        }
    }
}
