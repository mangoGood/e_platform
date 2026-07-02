package com.ecommerce.buyer.feature.order

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
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import coil.compose.AsyncImage
import com.ecommerce.core.model.Order
import com.ecommerce.core.model.OrderVO
import com.ecommerce.core.ui.formatPrice
import com.ecommerce.core.ui.toImageUrl

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun OrderDetailScreen(
    onBack: () -> Unit,
    viewModel: OrderDetailViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val snackbarHostState = remember { SnackbarHostState() }

    LaunchedEffect(uiState.errorMessage) {
        uiState.errorMessage?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }
    LaunchedEffect(uiState.actionSuccess) {
        uiState.actionSuccess?.let {
            snackbarHostState.showSnackbar(it)
            viewModel.clearMessages()
        }
    }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = { Text("订单详情", color = Color.White) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "返回", tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFFFF6B35)
                )
            )
        },
        bottomBar = {
            uiState.order?.let { order ->
                OrderDetailBottomBar(
                    order = order,
                    isProcessing = uiState.isProcessing,
                    onPay = { viewModel.payOrder() },
                    onCancel = { viewModel.cancelOrder() },
                    onReceive = { viewModel.receiveOrder() }
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
                uiState.order == null -> {
                    Text(
                        text = uiState.errorMessage ?: "订单不存在",
                        modifier = Modifier.align(Alignment.Center),
                        color = Color.Gray
                    )
                }
                else -> {
                    OrderDetailContent(uiState.order!!)
                }
            }
        }
    }
}

@Composable
private fun OrderDetailContent(order: OrderVO) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(12.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        // 状态卡片
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFFFF6B35))
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    order.statusText,
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    statusHint(order.status),
                    color = Color.White.copy(alpha = 0.8f),
                    fontSize = 13.sp
                )
            }
        }

        // 收货地址
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("收货信息", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    "${order.receiverName ?: ""}  ${order.receiverPhone ?: ""}",
                    fontSize = 14.sp
                )
                Text(
                    order.receiverAddress ?: "无地址",
                    fontSize = 13.sp,
                    color = Color.Gray
                )
            }
        }

        // 商品清单
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("商品清单", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                order.items.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 6.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        AsyncImage(
                            model = item.productImage.toImageUrl(),
                            contentDescription = item.productName,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier
                                .size(64.dp)
                                .clip(RoundedCornerShape(6.dp))
                                .background(Color(0xFFEEEEEE))
                        )
                        Spacer(modifier = Modifier.width(10.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                item.productName,
                                fontSize = 13.sp,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                "¥${item.price.formatPrice()} x${item.quantity}",
                                fontSize = 12.sp,
                                color = Color.Gray
                            )
                        }
                        Text(
                            "¥${item.totalAmount.formatPrice()}",
                            fontSize = 13.sp,
                            color = Color(0xFFFF6B35),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // 金额信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                PriceRow("商品金额", "¥${order.totalAmount.formatPrice()}")
                Spacer(modifier = Modifier.height(6.dp))
                PriceRow("运费", "¥${order.freightAmount.formatPrice()}")
                Spacer(modifier = Modifier.height(6.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("实付款", fontWeight = FontWeight.Bold)
                    Text(
                        "¥${order.payAmount.formatPrice()}",
                        color = Color(0xFFFF6B35),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }

        // 订单信息
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(8.dp),
            colors = CardDefaults.cardColors(containerColor = Color.White)
        ) {
            Column(modifier = Modifier.padding(12.dp)) {
                Text("订单信息", fontSize = 14.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(8.dp))
                InfoRow("订单编号", order.orderNo)
                InfoRow("创建时间", order.createTime ?: "")
                order.payTime?.let { InfoRow("付款时间", it) }
                order.deliveryTime?.let { InfoRow("发货时间", it) }
                order.receiveTime?.let { InfoRow("收货时间", it) }
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
private fun PriceRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp)
    }
}

@Composable
private fun InfoRow(label: String, value: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 3.dp),
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        Text(label, fontSize = 13.sp, color = Color.Gray)
        Text(value, fontSize = 13.sp, color = Color(0xFF333333))
    }
}

@Composable
private fun OrderDetailBottomBar(
    order: OrderVO,
    isProcessing: Boolean,
    onPay: () -> Unit,
    onCancel: () -> Unit,
    onReceive: () -> Unit
) {
    val showButtons = order.status == Order.STATUS_UNPAID ||
        order.status == Order.STATUS_SHIPPED
    if (!showButtons) return

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color.White)
            .padding(12.dp),
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (isProcessing) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                strokeWidth = 2.dp,
                color = Color(0xFFFF6B35)
            )
            Spacer(modifier = Modifier.width(8.dp))
        }
        when (order.status) {
            Order.STATUS_UNPAID -> {
                OutlinedButton(
                    onClick = onCancel,
                    enabled = !isProcessing,
                    modifier = Modifier.height(40.dp)
                ) { Text("取消订单", fontSize = 13.sp) }
                Spacer(modifier = Modifier.width(8.dp))
                Button(
                    onClick = onPay,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                    modifier = Modifier.height(40.dp)
                ) { Text("立即付款", color = Color.White, fontSize = 13.sp) }
            }
            Order.STATUS_SHIPPED -> {
                Button(
                    onClick = onReceive,
                    enabled = !isProcessing,
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFF6B35)),
                    modifier = Modifier.height(40.dp)
                ) { Text("确认收货", color = Color.White, fontSize = 13.sp) }
            }
        }
    }
}

private fun statusHint(status: Int): String = when (status) {
    Order.STATUS_UNPAID -> "请尽快完成付款"
    Order.STATUS_PAID -> "商家正在备货中"
    Order.STATUS_SHIPPED -> "商品已发出，请注意查收"
    Order.STATUS_COMPLETED -> "订单已完成"
    Order.STATUS_CANCELLED -> "订单已取消"
    else -> ""
}
