package com.ecommerce.core.model

import kotlinx.serialization.Serializable

/**
 * 评论节点，对应后端 `com.ecommerce.product.vo.CommentVO`。
 *
 * ## 树深恒为 2
 * 业务上有三种角色：L1 买家评价 / L2 卖家回复 / L3 第三方追问，
 * 但**结构上只有两层**：服务端对 L2/L3 再追问时会强制把节点拍平回挂到 L1 上。
 * 所以渲染时**不要写递归**——[asks] 里的元素不会再有自己的 [asks]。
 *
 * ## 注意 [reply] 是单个对象
 * 卖家回复是 `CommentVO`（0..1），不是数组。当成 List 解会直接反序列化失败。
 */
@Serializable
data class CommentVO(
    val id: Long = 0L,
    val userId: Long = 0L,
    val nickname: String? = null,
    /** 1=L1 买家评价 2=L2 卖家回复 3=L3 第三方追问 */
    val type: Int = TYPE_REVIEW,
    val parentId: Long? = null,
    val rootId: Long? = null,
    val rating: Int? = null,
    val content: String = "",
    /** **逗号分隔的字符串**，不是数组。用 [imageList] 取解析后的列表。 */
    val images: String? = null,
    val replyToUserId: Long? = null,
    val replyToNickname: String? = null,
    val replyCount: Int = 0,
    /** 后端是 `LocalDateTime`，Spring Boot 默认按 ISO-8601 序列化成字符串 */
    val createTime: String? = null,
    val sellerReply: Boolean = false,
    /** 是否为当前登录用户自己发的，决定要不要显示编辑/删除入口 */
    val mine: Boolean = false,
    /** 卖家回复，**单个对象**（0..1），不是数组 */
    val reply: CommentVO? = null,
    /** 第三方追问列表；其元素不再有子 asks（树深恒为 2） */
    val asks: List<CommentVO> = emptyList(),
    val askTotal: Int = 0,
    val askHasMore: Boolean = false
) {
    /**
     * 把逗号分隔的 [images] 拆成列表。
     *
     * 后端存的是 `"a.jpg,b.jpg"` 这种字符串而不是 JSON 数组，必须自己 split。
     */
    val imageList: List<String>
        get() = images
            ?.split(",")
            ?.map { it.trim() }
            ?.filter { it.isNotEmpty() }
            ?: emptyList()

    /** 展示用昵称，缺失时兜底 */
    val displayName: String
        get() = nickname?.takeIf { it.isNotBlank() } ?: "匿名用户"

    companion object {
        const val TYPE_REVIEW = 1
        const val TYPE_SELLER_REPLY = 2
        const val TYPE_ASK = 3
    }
}

/**
 * 评分汇总，对应后端 `RatingSummaryVO`。
 */
@Serializable
data class RatingSummaryVO(
    /** 后端是 BigDecimal，JSON 里是数字 */
    val ratingAvg: Double = 0.0,
    val ratingCount: Int = 0,
    /**
     * 星级分布。后端声明是 `Map<Integer,Integer>`，但 **JSON 的 key 一定是字符串**
     * （`{"1":0,"2":1,...}`），JSON 规范不允许非字符串 key。
     *
     * kotlinx.serialization 解 `Map<Int,Int>` 需要打开 `allowStructuredMapKeys`，
     * 这里直接声明成 `Map<String,Int>` 更省事也更贴合线上真实报文。
     */
    val distribution: Map<String, Int> = emptyMap()
) {
    /**
     * 取某个星级的数量。
     *
     * @param star 星级 1..5
     * @return 该星级的评价条数，缺失时为 0
     */
    fun countOf(star: Int): Int = distribution[star.toString()] ?: 0
}

/**
 * 观察者上下文，对应后端 `ViewerContextVO`。
 *
 * **权限一律以本对象为准驱动 UI**，不要在客户端自己推断"是不是买过""是不是卖家"。
 * 各个 `xxxDeniedReason` 是后端给好的中文文案（如"登录后才能评价"），直接展示即可。
 */
@Serializable
data class ViewerContextVO(
    val loggedIn: Boolean = false,
    val userId: Long? = null,
    val seller: Boolean = false,
    val canReview: Boolean = false,
    val reviewDeniedReason: String? = null,
    val canReply: Boolean = false,
    val replyDeniedReason: String? = null,
    val canAsk: Boolean = false,
    val askDeniedReason: String? = null
)

/**
 * 商品评论树，对应后端 `CommentTreeVO`，是 `GET comment/product/{productId}` 的 `data`。
 *
 * 取值层级：商品评论列表在 **`data.comments.records`**，
 * 与回复列表的 `data.records` 差一层，写错会渲染出空白页且不报任何错。
 */
@Serializable
data class CommentTreeVO(
    val comments: PageResult<CommentVO> = PageResult(),
    val summary: RatingSummaryVO = RatingSummaryVO(),
    val viewerContext: ViewerContextVO = ViewerContextVO()
)

/**
 * 能否评价，对应后端 `CanReviewVO`。
 */
@Serializable
data class CanReviewVO(
    val canReview: Boolean = false,
    /** 不能评价的中文原因，后端给好的文案 */
    val reason: String? = null,
    val orderId: Long? = null,
    val orderItemId: Long? = null,
    val orderStatus: Int? = null
)

/**
 * 发表 L1 评价，对应后端 `CommentCreateDTO`。
 */
@Serializable
data class CommentCreateDTO(
    val productId: Long,
    val rating: Int,
    val content: String,
    /** 逗号分隔的图片地址串，不是数组 */
    val images: String? = null
)

/**
 * 卖家回复（L2），对应后端 `CommentReplyDTO`。
 *
 * 字段名是 **`parentId`**——注意和 [CommentAskDTO] 的 `commentId` 不一样。
 * 两者都指向 L1 评论的 id，但后端 DTO 定义就是不同的名字，写错会静默 400。
 */
@Serializable
data class CommentReplyDTO(
    val parentId: Long,
    val content: String
)

/**
 * 第三方追问（L3），对应后端 `CommentAskDTO`。
 *
 * 字段名是 **`commentId`**——注意和 [CommentReplyDTO] 的 `parentId` 不一样。
 */
@Serializable
data class CommentAskDTO(
    val commentId: Long,
    val content: String
)

/**
 * 编辑评论，对应后端 `CommentUpdateDTO`。
 */
@Serializable
data class CommentUpdateDTO(
    val content: String? = null,
    val rating: Int? = null,
    val images: String? = null
)
