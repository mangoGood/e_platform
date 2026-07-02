package com.ecommerce.buyer.feature.home

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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Divider
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.ecommerce.core.ui.EmptyView
import com.ecommerce.core.ui.ErrorView
import com.ecommerce.core.ui.LoadingView
import com.ecommerce.core.ui.ProductCard

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onProductClick: (Long) -> Unit,
    onNeedLogin: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()
    val listState = rememberLazyListState()

    // 滚动到底部自动加载更多
    val shouldLoadMore by remember {
        derivedStateOf {
            val lastVisibleIndex = listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0
            val totalItems = listState.layoutInfo.totalItemsCount
            lastVisibleIndex >= totalItems - 3
        }
    }
    LaunchedEffect(shouldLoadMore) {
        if (shouldLoadMore && !uiState.isLoading && !uiState.isLoadingMore) {
            viewModel.loadMore()
        }
    }

    Column(modifier = Modifier.fillMaxSize().background(Color(0xFFF5F5F5))) {
        // 顶部栏
        TopAppBar(
            title = {
                Text(
                    "商城",
                    style = TextStyle(fontWeight = FontWeight.Bold, fontSize = 18.sp)
                )
            },
            colors = TopAppBarDefaults.topAppBarColors(
                containerColor = Color(0xFFFF6B35),
                titleContentColor = Color.White
            )
        )

        // 搜索框
        Surface(
            modifier = Modifier.fillMaxWidth(),
            color = Color(0xFFFF6B35)
        ) {
            Box(modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                OutlinedTextField(
                    value = uiState.keyword,
                    onValueChange = { viewModel.search(it) },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("搜索商品", fontSize = 14.sp, color = Color.White) },
                    leadingIcon = {
                        Icon(Icons.Default.Search, contentDescription = null, tint = Color.White)
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(20.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = Color(0xFFFF8C5A),
                        unfocusedContainerColor = Color(0xFFFF8C5A),
                        focusedTextColor = Color.White,
                        unfocusedTextColor = Color.White,
                        focusedBorderColor = Color.Transparent,
                        unfocusedBorderColor = Color.Transparent,
                        cursorColor = Color.White
                    )
                )
            }
        }

        // 内容区
        Box(modifier = Modifier.fillMaxSize()) {
            when {
                uiState.isLoading -> LoadingView()
                uiState.errorMessage != null && uiState.products.isEmpty() -> {
                    ErrorView(
                        message = uiState.errorMessage!!,
                        onRetry = { viewModel.loadFirstPage() }
                    )
                }
                uiState.products.isEmpty() -> EmptyView("暂无商品")
                else -> {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize()
                    ) {
                        items(
                            items = uiState.products,
                            key = { it.id }
                        ) { product ->
                            Surface(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(horizontal = 8.dp, vertical = 4.dp),
                                color = Color.White,
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                ProductCard(
                                    product = product,
                                    onClick = onProductClick
                                )
                            }
                        }

                        // 加载更多指示器
                        if (uiState.isLoadingMore) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("加载中...", color = Color.Gray, fontSize = 13.sp)
                                }
                            }
                        }

                        // 到底提示
                        if (!uiState.isLoadingMore && uiState.currentPage >= uiState.totalPages && uiState.products.isNotEmpty()) {
                            item {
                                Box(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    contentAlignment = Alignment.Center
                                ) {
                                    Text("没有更多了", color = Color.Gray, fontSize = 12.sp)
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}
