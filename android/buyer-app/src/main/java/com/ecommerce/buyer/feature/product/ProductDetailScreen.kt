package com.ecommerce.buyer.feature.product

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ecommerce.core.model.Product
import com.ecommerce.core.ui.ErrorView
import com.ecommerce.core.ui.LoadingView
import com.ecommerce.core.ui.formatPrice
import com.ecommerce.core.ui.toImageUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProductDetailScreen(
    productId: Long,
    onBack: () -> Unit,
    onGoToCart: () -> Unit,
    onBuyNow: (Long) -> Unit,
    viewModel: ProductDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(productId) {
        viewModel.loadProduct(productId)
    }

    LaunchedEffect(uiState.addToCartSuccess) {
        if (uiState.addToCartSuccess) {
            snackbarHostState.showSnackbar("已加入购物车")
            viewModel.consumeAddToCartSuccess()
        }
    }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearError()
        }
    }

    // 评论区的错误只在**列表已有内容**时走 Snackbar（多半是提交失败）。
    // 列表为空时错误由评论区内部渲染成带「重试」的占位，此处不抢着消费掉，
    // 否则刚渲染出来的错误占位会被立刻清空，用户什么也看不到。
    LaunchedEffect(uiState.comment.errorMessage) {
        val message = uiState.comment.errorMessage
        if (message != null && uiState.comment.comments.isNotEmpty()) {
            snackbarHostState.showSnackbar(message)
            viewModel.clearCommentError()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("商品详情", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                actions = {
                    IconButton(onClick = onGoToCart) {
                        Icon(
                            Icons.Default.ShoppingCart,
                            contentDescription = "购物车",
                            tint = Color.White
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF6B35)
                )
            )
        },
        bottomBar = {
            val product = uiState.product
            if (product != null) {
                BottomActionBar(
                    product = product,
                    quantity = uiState.quantity,
                    onAddToCart = { viewModel.addToCart() },
                    onBuyNow = { onBuyNow(product.id) }
                )
            }
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .background(Color(0xFFF5F5F5))
        ) {
            val product = uiState.product
            when {
                uiState.isLoading -> LoadingView()

                product == null -> ErrorView(
                    message = uiState.errorMessage ?: "商品不存在",
                    // 改造前这里是一个纯 Text，加载失败后用户只能退出重进。
                    // ErrorView 的 onRetry 参数以前是死参数，现已修好，这里正式接上。
                    onRetry = { viewModel.loadProduct(productId) }
                )

                else -> ProductDetailContent(
                    product = product,
                    quantity = uiState.quantity,
                    commentState = uiState.comment,
                    onQuantityChange = { delta -> viewModel.changeQuantity(delta) },
                    onRetryComments = { viewModel.loadComments() },
                    onLoadMoreComments = { viewModel.loadMoreComments() },
                    onExpandAsks = { rootId -> viewModel.expandAsks(rootId) },
                    onToggleReviewForm = { open -> viewModel.toggleReviewForm(open) },
                    onSubmitReview = { rating, content -> viewModel.submitReview(rating, content) },
                    onToggleAskInput = { commentId -> viewModel.toggleAskInput(commentId) },
                    onSubmitAsk = { commentId, content -> viewModel.submitAsk(commentId, content) },
                    onDeleteComment = { commentId -> viewModel.deleteComment(commentId) }
                )
            }
        }
    }
}

/**
 * 商品详情正文：主图 / 价格 / 数量 / 描述 / **评论区**。
 *
 * 整体挂在一个 `verticalScroll` 的 Column 上而不是 LazyColumn：
 * 页面区块数量固定且不多，用 LazyColumn 反而要把每个区块拆成 item，
 * 还要处理评论区内部 LazyColumn 的嵌套滚动冲突。
 * 评论图片横向列表用 LazyRow——横纵轴不同，不构成嵌套滚动问题。
 */
@Composable
private fun ProductDetailContent(
    product: Product,
    quantity: Int,
    commentState: CommentUiState,
    onQuantityChange: (Int) -> Unit,
    onRetryComments: () -> Unit,
    onLoadMoreComments: () -> Unit,
    onExpandAsks: (Long) -> Unit,
    onToggleReviewForm: (Boolean) -> Unit,
    onSubmitReview: (Int, String) -> Unit,
    onToggleAskInput: (Long?) -> Unit,
    onSubmitAsk: (Long, String) -> Unit,
    onDeleteComment: (Long) -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(bottom = 16.dp)
    ) {
        // 商品主图
        AsyncImage(
            model = product.mainImage.toImageUrl(),
            contentDescription = product.name,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxWidth()
                .height(360.dp)
                .background(Color.White)
        )

        // 价格与标题
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp)
        ) {
            Row(verticalAlignment = Alignment.Bottom) {
                Text(
                    text = "¥${product.price.formatPrice()}",
                    color = Color(0xFFFF6B35),
                    fontSize = 28.sp,
                    fontWeight = FontWeight.Bold
                )
                if ((product.originalPrice ?: 0.0) > product.price) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        text = "¥${product.originalPrice.formatPrice()}",
                        color = Color.Gray,
                        fontSize = 14.sp,
                        textDecoration = TextDecoration.LineThrough
                    )
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
            Text(
                text = product.name,
                fontSize = 18.sp,
                fontWeight = FontWeight.Medium,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row {
                Text(text = "销量 ${product.sales}", color = Color.Gray, fontSize = 12.sp)
                Spacer(modifier = Modifier.width(16.dp))
                Text(text = "库存 ${product.stock}", color = Color.Gray, fontSize = 12.sp)
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 数量选择
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text("数量", fontSize = 16.sp)
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedButton(
                    onClick = { onQuantityChange(-1) },
                    enabled = quantity > 1,
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.size(36.dp)
                ) { Text("−") }
                Text(
                    text = "$quantity",
                    modifier = Modifier.padding(horizontal = 16.dp),
                    fontSize = 16.sp
                )
                OutlinedButton(
                    onClick = { onQuantityChange(1) },
                    shape = RoundedCornerShape(0.dp),
                    modifier = Modifier.size(36.dp)
                ) { Text("+") }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 商品描述
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(Color.White)
                .padding(16.dp)
        ) {
            Text(
                "商品详情",
                fontSize = 16.sp,
                fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(bottom = 8.dp)
            )
            Text(
                text = product.description ?: "暂无详细描述",
                fontSize = 14.sp,
                color = Color(0xFF333333),
                lineHeight = 22.sp
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // 评论区（三级评论：L1 评价 / L2 卖家回复 / L3 追问）
        CommentSection(
            state = commentState,
            onRetry = onRetryComments,
            onLoadMore = onLoadMoreComments,
            onExpandAsks = onExpandAsks,
            onToggleReviewForm = onToggleReviewForm,
            onSubmitReview = onSubmitReview,
            onToggleAskInput = onToggleAskInput,
            onSubmitAsk = onSubmitAsk,
            onDeleteComment = onDeleteComment
        )
    }
}

@Composable
private fun BottomActionBar(
    product: Product,
    quantity: Int,
    onAddToCart: () -> Unit,
    onBuyNow: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text(
            text = "合计: ¥${(product.price * quantity).formatPrice()}",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = Color(0xFFFF6B35),
            modifier = Modifier.weight(1f)
        )
        OutlinedButton(
            onClick = onAddToCart,
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.height(48.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = Color(0xFFFF6B35))
        ) {
            Icon(Icons.Default.ShoppingCart, contentDescription = null)
            Spacer(modifier = Modifier.width(4.dp))
            Text("加入购物车")
        }
        Button(
            onClick = onBuyNow,
            colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
            shape = RoundedCornerShape(24.dp),
            modifier = Modifier.height(48.dp)
        ) {
            Text("立即购买", color = Color.White)
        }
    }
}
