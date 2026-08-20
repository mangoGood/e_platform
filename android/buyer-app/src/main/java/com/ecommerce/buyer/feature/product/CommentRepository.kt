package com.ecommerce.buyer.feature.product

import com.ecommerce.core.model.CanReviewVO
import com.ecommerce.core.model.CommentAskDTO
import com.ecommerce.core.model.CommentCreateDTO
import com.ecommerce.core.model.CommentTreeVO
import com.ecommerce.core.model.CommentVO
import com.ecommerce.core.model.PageResult
import com.ecommerce.core.network.CommentApi
import com.ecommerce.core.network.apiCall
import com.ecommerce.core.network.requireData
import com.ecommerce.core.network.requireSuccess
import javax.inject.Inject
import javax.inject.Singleton

/**
 * 商品评论仓库（买家端）。
 *
 * ## 三级评论的结构约定
 * - **L1** 买家评价：带 `rating`、可带图，一个买家对一个商品只能有一条。
 * - **L2** 卖家回复：挂在 L1 下，是 [CommentVO.reply]，**单个对象**不是数组。
 * - **L3** 第三方追问：挂在 L1 下，是 [CommentVO.asks]，数组。
 *
 * 树深**恒为 2**——L2/L3 节点不会再有自己的子节点，渲染时不要写递归。
 *
 * ## 取值层级（最容易写错的地方）
 * - `GET comment/product/{id}` 的评论列表在 **`data.comments.records`**
 * - `GET comment/{rootId}/replies` 的列表在 **`data.records`**
 *
 * 两者差一层。取错不会报错，只会渲染出一片空白，排查起来很费时间。
 */
@Singleton
class CommentRepository @Inject constructor(
    private val commentApi: CommentApi
) {

    /**
     * 拉取商品评论树。
     *
     * 返回的 [CommentTreeVO] 一并带回了评分汇总 `summary` 和权限上下文 `viewerContext`，
     * 一次请求就够渲染整个评论区，不需要再单独打 `can-review`。
     *
     * @param productId 商品 id
     * @param pageNum   页码，从 1 开始
     * @param pageSize  每页 L1 评价条数
     * @param sort      排序：`latest` 最新 / `rating` 评分
     * @param askSize   每条 L1 评价内联返回几条追问
     */
    suspend fun getProductComments(
        productId: Long,
        pageNum: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE,
        sort: String = SORT_LATEST,
        askSize: Int = DEFAULT_ASK_SIZE
    ): Result<CommentTreeVO> = apiCall {
        commentApi.getProductComments(
            productId = productId,
            pageNum = pageNum,
            pageSize = pageSize,
            sort = sort,
            askSize = askSize
        ).requireData()
    }

    /**
     * 拉取某条 L1 评价下的全部追问（分页）。
     *
     * 用于"展开更多追问"——评论树里内联的 `asks` 只有 `askSize` 条。
     * 注意这个接口的列表在 `data.records`，比评论树少一层。
     *
     * @param rootId   L1 评价的 id
     * @param pageNum  页码
     * @param pageSize 每页条数
     */
    suspend fun getReplies(
        rootId: Long,
        pageNum: Int = 1,
        pageSize: Int = DEFAULT_PAGE_SIZE
    ): Result<PageResult<CommentVO>> = apiCall {
        commentApi.getReplies(rootId, pageNum, pageSize).requireData()
    }

    /**
     * 查询当前用户能否评价该商品。
     *
     * 一般不用单独调——评论树的 `viewerContext.canReview` 已经带了这个信息。
     * 保留是为了"我的订单 -> 去评价"这类没有评论树上下文的入口。
     *
     * @param productId 商品 id
     */
    suspend fun canReview(productId: Long): Result<CanReviewVO> = apiCall {
        commentApi.canReview(productId).requireData()
    }

    /**
     * 发表 L1 评价。
     *
     * @param productId 商品 id
     * @param rating    评分 1..5
     * @param content   评价正文
     * @param images    图片地址列表；后端存的是**逗号分隔字符串**，这里负责拼接
     */
    suspend fun createComment(
        productId: Long,
        rating: Int,
        content: String,
        images: List<String> = emptyList()
    ): Result<CommentVO> = apiCall {
        commentApi.createComment(
            CommentCreateDTO(
                productId = productId,
                rating = rating.coerceIn(MIN_RATING, MAX_RATING),
                content = content.trim(),
                images = images.filter { it.isNotBlank() }
                    .takeIf { it.isNotEmpty() }
                    ?.joinToString(",")
            )
        ).requireData()
    }

    /**
     * 发表 L3 第三方追问。
     *
     * 请求体字段名是 **`commentId`**（卖家回复用的是 `parentId`，两者不能混）。
     *
     * @param commentId 被追问的 L1 评价 id
     * @param content   追问正文
     */
    suspend fun askComment(commentId: Long, content: String): Result<CommentVO> = apiCall {
        commentApi.askComment(
            CommentAskDTO(commentId = commentId, content = content.trim())
        ).requireData()
    }

    /**
     * 删除自己的评论。
     *
     * @param commentId 评论 id
     */
    suspend fun deleteComment(commentId: Long): Result<Unit> = apiCall {
        commentApi.deleteComment(commentId).requireSuccess()
    }

    companion object {
        const val SORT_LATEST = "latest"
        const val SORT_RATING = "rating"
        const val DEFAULT_PAGE_SIZE = 10
        const val DEFAULT_ASK_SIZE = 3
        const val MIN_RATING = 1
        const val MAX_RATING = 5
    }
}
