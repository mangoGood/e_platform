package com.ecommerce.buyer.feature.product

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil.compose.AsyncImage
import com.ecommerce.core.model.CommentVO
import com.ecommerce.core.model.RatingSummaryVO
import com.ecommerce.core.model.ViewerContextVO
import com.ecommerce.core.ui.formatDateTime
import com.ecommerce.core.ui.toImageUrl

private val BrandColor = Color(0xFFFF6B35)
private val StarColor = Color(0xFFFFB400)
private val SubTextColor = Color(0xFF999999)
private val BodyTextColor = Color(0xFF333333)
private val BlockBgColor = Color(0xFFF7F7F7)
private val DividerColor = Color(0xFFEEEEEE)

/**
 * 商品评论区。
 *
 * ## 三级结构如何渲染
 * ```
 * L1 买家评价  CommentVO
 *  ├─ L2 卖家回复   comment.reply   ← 单个对象（0..1），不是数组
 *  └─ L3 第三方追问 comment.asks    ← 数组
 * ```
 * 树深**恒为 2**：`reply` 和 `asks` 里的节点不会再有自己的子节点，
 * 所以这里是**两层平铺渲染，没有递归**。写成递归组件不但多余，
 * 还会在数据异常时把自己转进无限重组。
 *
 * ## 权限一律由 [ViewerContextVO] 驱动
 * "能不能评价""能不能追问"全部读后端给的 `canReview` / `canAsk`，
 * 不在客户端用"有没有登录 + 是不是买过"自己推断——那套判断迟早和后端规则漂移。
 * 不能操作时展示后端给好的中文 `xxxDeniedReason`。
 *
 * @param state             评论区状态
 * @param onRetry           加载失败后的重试
 * @param onLoadMore        加载下一页评论
 * @param onExpandAsks      展开某条评价的全部追问
 * @param onToggleReviewForm 展开/收起发表评价表单
 * @param onSubmitReview    提交评价（评分, 正文）
 * @param onToggleAskInput  展开/收起某条评价的追问输入框
 * @param onSubmitAsk       提交追问（L1 评价 id, 正文）
 * @param onDeleteComment   删除自己的评论
 */
@Composable
fun CommentSection(
    state: CommentUiState,
    onRetry: () -> Unit,
    onLoadMore: () -> Unit,
    onExpandAsks: (Long) -> Unit,
    onToggleReviewForm: (Boolean) -> Unit,
    onSubmitReview: (Int, String) -> Unit,
    onToggleAskInput: (Long?) -> Unit,
    onSubmitAsk: (Long, String) -> Unit,
    onDeleteComment: (Long) -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(vertical = 16.dp)
    ) {
        SectionTitle(total = state.total)

        Spacer(modifier = Modifier.height(12.dp))

        RatingSummaryHeader(summary = state.summary)

        Spacer(modifier = Modifier.height(12.dp))

        ReviewComposer(
            viewerContext = state.viewerContext,
            isOpen = state.isReviewFormOpen,
            isSubmitting = state.isSubmitting,
            onToggle = onToggleReviewForm,
            onSubmit = onSubmitReview
        )

        Spacer(modifier = Modifier.height(8.dp))

        when {
            state.isLoading -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(28.dp),
                        strokeWidth = 2.dp,
                        color = BrandColor
                    )
                }
            }

            state.errorMessage != null && state.comments.isEmpty() -> {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(text = state.errorMessage, color = Color.Red, fontSize = 13.sp)
                    OutlinedButton(
                        onClick = onRetry,
                        shape = RoundedCornerShape(20.dp),
                        modifier = Modifier.padding(top = 12.dp)
                    ) {
                        Text(text = "重试", color = BrandColor, fontSize = 13.sp)
                    }
                }
            }

            state.comments.isEmpty() -> {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(text = "还没有评价，快来抢沙发", color = SubTextColor, fontSize = 13.sp)
                }
            }

            else -> {
                state.comments.forEach { comment ->
                    CommentItem(
                        comment = comment,
                        viewerContext = state.viewerContext,
                        isAsking = state.askingCommentId == comment.id,
                        isSubmitting = state.isSubmitting,
                        isAsksExpanded = state.expandedAsks.contains(comment.id),
                        onExpandAsks = { onExpandAsks(comment.id) },
                        onToggleAskInput = { onToggleAskInput(comment.id) },
                        onSubmitAsk = { content -> onSubmitAsk(comment.id, content) },
                        onDelete = { onDeleteComment(comment.id) }
                    )
                    ThinDivider()
                }

                if (state.hasMore) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 12.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        if (state.isLoadingMore) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(20.dp),
                                strokeWidth = 2.dp,
                                color = BrandColor
                            )
                        } else {
                            TextButton(onClick = onLoadMore) {
                                Text(text = "查看更多评价", color = BrandColor, fontSize = 13.sp)
                            }
                        }
                    }
                }
            }
        }
    }
}

/**
 * 评论区标题栏。
 *
 * @param total 评价总条数
 */
@Composable
private fun SectionTitle(total: Long) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(text = "商品评价", fontSize = 16.sp, fontWeight = FontWeight.Bold)
        Spacer(modifier = Modifier.width(6.dp))
        Text(text = "($total)", fontSize = 13.sp, color = SubTextColor)
    }
}

/**
 * 评分汇总：左侧平均分，右侧 5..1 星分布条。
 *
 * @param summary 后端下发的评分汇总
 */
@Composable
private fun RatingSummaryHeader(summary: RatingSummaryVO) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(
                text = String.format("%.1f", summary.ratingAvg),
                fontSize = 28.sp,
                fontWeight = FontWeight.Bold,
                color = BrandColor
            )
            StarRow(rating = summary.ratingAvg.toInt())
            Text(
                text = "${summary.ratingCount} 条评价",
                fontSize = 11.sp,
                color = SubTextColor
            )
        }

        Spacer(modifier = Modifier.width(20.dp))

        Column(modifier = Modifier.weight(1f)) {
            // 由高到低展示，符合电商评价区的一般习惯
            for (star in 5 downTo 1) {
                RatingBar(
                    star = star,
                    count = summary.countOf(star),
                    total = summary.ratingCount
                )
            }
        }
    }
}

/**
 * 单条星级分布条。
 *
 * 用 `Box(fillMaxWidth(fraction))` 手绘而不是 `LinearProgressIndicator`：
 * material3 1.2.0 里那个带 `progress: Float` 的重载已废弃，
 * 手绘两个 Box 更简单，也不用担心后续版本行为变化。
 *
 * @param star  星级 1..5
 * @param count 该星级的条数
 * @param total 评价总条数
 */
@Composable
private fun RatingBar(star: Int, count: Int, total: Int) {
    // total 为 0 时分母保护，避免 NaN 传进 fillMaxWidth 触发崩溃
    val fraction: Float = if (total <= 0) 0f else (count.toFloat() / total).coerceIn(0f, 1f)
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 1.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "${star}星",
            fontSize = 10.sp,
            color = SubTextColor,
            modifier = Modifier.width(26.dp)
        )
        Box(
            modifier = Modifier
                .weight(1f)
                .height(6.dp)
                .clip(RoundedCornerShape(3.dp))
                .background(Color(0xFFEEEEEE))
        ) {
            if (fraction > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth(fraction)
                        .height(6.dp)
                        .background(StarColor)
                )
            }
        }
        Text(
            text = "$count",
            fontSize = 10.sp,
            color = SubTextColor,
            modifier = Modifier
                .width(28.dp)
                .padding(start = 6.dp)
        )
    }
}

/**
 * 发表评价的入口 + 表单。
 *
 * `canReview` 为 false 时不展示表单，只展示后端给的中文 `reviewDeniedReason`
 * （例如"购买并确认收货后才能评价"）。
 *
 * @param viewerContext 权限上下文
 * @param isOpen        表单是否展开
 * @param isSubmitting  是否正在提交
 * @param onToggle      展开/收起
 * @param onSubmit      提交（评分, 正文）
 */
@Composable
private fun ReviewComposer(
    viewerContext: ViewerContextVO,
    isOpen: Boolean,
    isSubmitting: Boolean,
    onToggle: (Boolean) -> Unit,
    onSubmit: (Int, String) -> Unit
) {
    if (!viewerContext.canReview) {
        val reason = viewerContext.reviewDeniedReason
        if (!reason.isNullOrBlank()) {
            Text(
                text = reason,
                fontSize = 12.sp,
                color = SubTextColor,
                modifier = Modifier.padding(horizontal = 16.dp)
            )
        }
        return
    }

    if (!isOpen) {
        OutlinedButton(
            onClick = { onToggle(true) },
            shape = RoundedCornerShape(20.dp),
            modifier = Modifier.padding(horizontal = 16.dp)
        ) {
            Text(text = "发表评价", color = BrandColor, fontSize = 13.sp)
        }
        return
    }

    var rating by remember { mutableIntStateOf(DEFAULT_RATING) }
    var content by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(BlockBgColor)
            .padding(12.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(text = "评分", fontSize = 13.sp, color = BodyTextColor)
            Spacer(modifier = Modifier.width(8.dp))
            SelectableStarRow(rating = rating, onRatingChange = { rating = it })
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = { Text("说说这件商品怎么样吧", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 3,
            maxLines = 6,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            TextButton(onClick = { onToggle(false) }, enabled = !isSubmitting) {
                Text(text = "取消", color = SubTextColor, fontSize = 13.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = { onSubmit(rating, content) },
                // 内容为空或提交中都禁用，从 UI 层杜绝重复提交
                enabled = !isSubmitting && content.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
            ) {
                Text(text = if (isSubmitting) "提交中…" else "提交", fontSize = 13.sp)
            }
        }
    }
}

/**
 * 一条 L1 买家评价，以及挂在它下面的 L2 卖家回复与 L3 追问。
 *
 * @param comment        L1 评价
 * @param viewerContext  权限上下文
 * @param isAsking       是否展开了追问输入框
 * @param isSubmitting   是否正在提交
 * @param isAsksExpanded 是否已展开全部追问
 * @param onExpandAsks   展开全部追问
 * @param onToggleAskInput 展开/收起追问输入框
 * @param onSubmitAsk    提交追问
 * @param onDelete       删除本条评价
 */
@Composable
private fun CommentItem(
    comment: CommentVO,
    viewerContext: ViewerContextVO,
    isAsking: Boolean,
    isSubmitting: Boolean,
    isAsksExpanded: Boolean,
    onExpandAsks: () -> Unit,
    onToggleAskInput: () -> Unit,
    onSubmitAsk: (String) -> Unit,
    onDelete: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        // ---------- L1 头部：昵称 / 评分 / 时间 ----------
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = comment.displayName,
                fontSize = 13.sp,
                fontWeight = FontWeight.Medium,
                color = BodyTextColor
            )
            Spacer(modifier = Modifier.width(8.dp))
            comment.rating?.let { StarRow(rating = it) }
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = comment.createTime.formatDateTime(),
                fontSize = 11.sp,
                color = SubTextColor
            )
        }

        Spacer(modifier = Modifier.height(6.dp))

        Text(
            text = comment.content,
            fontSize = 14.sp,
            color = BodyTextColor,
            lineHeight = 20.sp
        )

        // ---------- L1 图片：后端是逗号分隔字符串，由 imageList 拆开 ----------
        val images = comment.imageList
        if (images.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                items(images) { image ->
                    AsyncImage(
                        model = image.toImageUrl(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier
                            .size(80.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(BlockBgColor)
                    )
                }
            }
        }

        // ---------- L2 卖家回复：单个对象，不是数组 ----------
        comment.reply?.let { reply ->
            Spacer(modifier = Modifier.height(8.dp))
            SellerReplyBlock(reply = reply)
        }

        // ---------- L3 第三方追问：数组，其元素不再有子节点 ----------
        if (comment.asks.isNotEmpty()) {
            Spacer(modifier = Modifier.height(8.dp))
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(6.dp))
                    .background(BlockBgColor)
                    .padding(10.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                comment.asks.forEach { ask ->
                    AskRow(ask = ask)
                }

                // 内联的 asks 只有 askSize 条，还有更多时给一个展开入口
                if (comment.askHasMore && !isAsksExpanded) {
                    Text(
                        text = "展开全部 ${comment.askTotal} 条追问",
                        fontSize = 12.sp,
                        color = BrandColor,
                        modifier = Modifier.clickable(onClick = onExpandAsks)
                    )
                }
            }
        }

        // ---------- 操作区 ----------
        Spacer(modifier = Modifier.height(6.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (viewerContext.canAsk) {
                Text(
                    text = if (isAsking) "收起" else "追问",
                    fontSize = 12.sp,
                    color = BrandColor,
                    modifier = Modifier.clickable(onClick = onToggleAskInput)
                )
            } else {
                val reason = viewerContext.askDeniedReason
                if (!reason.isNullOrBlank()) {
                    Text(text = reason, fontSize = 11.sp, color = SubTextColor)
                }
            }

            // mine 由后端标注，别在客户端拿 userId 自己比——匿名评价场景下 userId 可能被抹掉
            if (comment.mine) {
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "删除",
                    fontSize = 12.sp,
                    color = SubTextColor,
                    modifier = Modifier.clickable(enabled = !isSubmitting, onClick = onDelete)
                )
            }
        }

        if (isAsking) {
            Spacer(modifier = Modifier.height(8.dp))
            AskComposer(isSubmitting = isSubmitting, onSubmit = onSubmitAsk)
        }
    }
}

/**
 * L2 卖家回复气泡。
 *
 * @param reply 卖家回复节点
 */
@Composable
private fun SellerReplyBlock(reply: CommentVO) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(6.dp))
            .background(Color(0xFFFFF4EF))
            .padding(10.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                text = "商家回复",
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = BrandColor
            )
            Spacer(modifier = Modifier.weight(1f))
            Text(
                text = reply.createTime.formatDateTime(),
                fontSize = 10.sp,
                color = SubTextColor
            )
        }
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = reply.content,
            fontSize = 13.sp,
            color = BodyTextColor,
            lineHeight = 18.sp
        )
    }
}

/**
 * 一条 L3 追问。
 *
 * `replyToNickname` 非空时展示 "A 回复 B："的形式。
 *
 * @param ask 追问节点
 */
@Composable
private fun AskRow(ask: CommentVO) {
    val prefix = if (!ask.replyToNickname.isNullOrBlank()) {
        "${ask.displayName} 回复 ${ask.replyToNickname}："
    } else {
        "${ask.displayName}："
    }
    Column {
        Text(
            text = prefix + ask.content,
            fontSize = 12.sp,
            color = BodyTextColor,
            lineHeight = 17.sp
        )
        Text(
            text = ask.createTime.formatDateTime(),
            fontSize = 10.sp,
            color = SubTextColor
        )
    }
}

/**
 * 追问输入框。
 *
 * @param isSubmitting 是否正在提交
 * @param onSubmit     提交回调
 */
@Composable
private fun AskComposer(
    isSubmitting: Boolean,
    onSubmit: (String) -> Unit
) {
    var content by remember { mutableStateOf("") }
    Column(modifier = Modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = content,
            onValueChange = { content = it },
            placeholder = { Text("想问点什么？", fontSize = 13.sp) },
            modifier = Modifier.fillMaxWidth(),
            minLines = 2,
            maxLines = 4,
            textStyle = androidx.compose.ui.text.TextStyle(fontSize = 13.sp)
        )
        Spacer(modifier = Modifier.height(6.dp))
        Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = { onSubmit(content) },
                enabled = !isSubmitting && content.isNotBlank(),
                shape = RoundedCornerShape(20.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrandColor)
            ) {
                Text(text = if (isSubmitting) "提交中…" else "发送", fontSize = 13.sp)
            }
        }
    }
}

/**
 * 只读星级展示。
 *
 * 用 `★ / ☆` 字符而不是 Material 图标：`StarBorder` 属于 material-icons-extended，
 * 本项目没有该依赖，而**禁止引入任何新依赖**。
 *
 * @param rating 实心星数量
 */
@Composable
private fun StarRow(rating: Int) {
    val safeRating = rating.coerceIn(0, MAX_RATING)
    Row {
        for (index in 1..MAX_RATING) {
            Text(
                text = if (index <= safeRating) "★" else "☆",
                fontSize = 12.sp,
                color = StarColor
            )
        }
    }
}

/**
 * 可点选的星级。
 *
 * @param rating         当前选中星数
 * @param onRatingChange 选中回调
 */
@Composable
private fun SelectableStarRow(rating: Int, onRatingChange: (Int) -> Unit) {
    Row {
        for (index in 1..MAX_RATING) {
            Text(
                text = if (index <= rating) "★" else "☆",
                fontSize = 20.sp,
                color = StarColor,
                modifier = Modifier
                    .padding(horizontal = 2.dp)
                    .clickable { onRatingChange(index) }
            )
        }
    }
}

/**
 * 1px 分隔线。
 *
 * 手写 Box 而不是 material3 的 `Divider`：后者在 1.2.0 已废弃改名 `HorizontalDivider`，
 * 手写没有版本兼容负担。
 */
@Composable
private fun ThinDivider() {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(1.dp)
            .background(DividerColor)
    )
}

/** 星级上限 */
private const val MAX_RATING = 5

/** 发表评价时默认选中的星数 */
private const val DEFAULT_RATING = 5
