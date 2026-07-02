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
import androidx.compose.material3.CircularProgressIndicator
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
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
                        Icon(Icons.Default.ShoppingCart, contentDescription = "购物车", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF6B35)
                )
            )
        },
        bottomBar = {
            if (uiState.product != null) {
                BottomActionBar(
                    product = uiState.product!!,
                    quantity = uiState.quantity,
                    onDecrease = { viewModel.changeQuantity(-1) },
                    onIncrease = { viewModel.changeQuantity(1) },
                    onAddToCart = { viewModel.addToCart() },
                    onBuyNow = { onBuyNow(uiState.product!!.id) }
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
            when {
                uiState.isLoading -> {
                    CircularProgressIndicator(
                        modifier = Modifier.align(Alignment.Center),
                        color = Color(0xFFFF6B35)
                    )
                }
                uiState.product == null -> {
                    Text(
                        text = uiState.errorMessage ?: "商品不存在",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                }
                else -> {
                    ProductDetailContent(uiState.product!!, uiState.quantity) { delta ->
                        viewModel.changeQuantity(delta)
                    }
                }
            }
        }
    }
}

@Composable
private fun ProductDetailContent(
    product: com.ecommerce.core.model.Product,
    quantity: Int,
    onQuantityChange: (Int) -> Unit
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
                Text(
                    text = "销量 ${product.sales}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
                Spacer(modifier = Modifier.width(16.dp))
                Text(
                    text = "库存 ${product.stock}",
                    color = Color.Gray,
                    fontSize = 12.sp
                )
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
    }
}

@Composable
private fun BottomActionBar(
    product: com.ecommerce.core.model.Product,
    quantity: Int,
    onDecrease: () -> Unit,
    onIncrease: () -> Unit,
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
