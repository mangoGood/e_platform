package com.ecommerce.core.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** 主题主色 */
private val BrandColor = Color(0xFFFF6B35)

/**
 * 全屏加载中
 */
@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(
            modifier = Modifier.size(40.dp),
            strokeWidth = 3.dp,
            color = BrandColor
        )
    }
}

/**
 * 空数据占位
 */
@Composable
fun EmptyView(message: String = "暂无数据", modifier: Modifier = Modifier) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Text(text = message, color = Color.Gray, fontSize = 14.sp)
    }
}

/**
 * 错误占位。
 *
 * ## 修复记录
 * 改造前 [onRetry] 是个**从未被使用的死参数**——签名上收了回调，
 * 函数体里却只渲染了一个 Text，调用方传进来的重试逻辑永远不会被触发，
 * 用户看到错误页后除了退出别无选择。
 * 现在 [onRetry] 非空时会渲染出真正可点击的「重试」按钮。
 *
 * @param message 错误文案（应当已经是中文，由 ErrorMapper 保证）
 * @param onRetry 重试回调；为 null 时不显示重试按钮
 */
@Composable
fun ErrorView(
    message: String,
    modifier: Modifier = Modifier,
    onRetry: (() -> Unit)? = null
) {
    Box(modifier = modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.padding(24.dp)
        ) {
            Text(
                text = message,
                color = Color.Red,
                fontSize = 14.sp,
                textAlign = TextAlign.Center
            )
            if (onRetry != null) {
                OutlinedButton(
                    onClick = onRetry,
                    shape = RoundedCornerShape(20.dp),
                    modifier = Modifier.padding(top = 16.dp)
                ) {
                    Text(text = "重试", color = BrandColor, fontSize = 14.sp)
                }
            }
        }
    }
}
