package com.ecommerce.buyer.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.ecommerce.core.network.SessionManager

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, "首页", Icons.Default.Home),
    BottomTab(Routes.CART, "购物车", Icons.Default.ShoppingCart),
    BottomTab(Routes.ORDERS, "订单", Icons.Default.ListAlt),
    BottomTab(Routes.PROFILE, "我的", Icons.Default.Person)
)

/**
 * 买家端主脚手架。
 *
 * @param sessionManager 全局会话事件总线，用于接收网络层广播的强制登出事件
 */
@Composable
fun MainScaffold(sessionManager: SessionManager) {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route
    val snackbarHostState = remember { SnackbarHostState() }

    // 刷新令牌彻底失效时，TokenRefresher 会广播强制登出。
    // 这里是网络层事件落到导航层的唯一出口：提示中文原因 + 跳登录页。
    LaunchedEffect(sessionManager) {
        sessionManager.forcedLogout.collect { reason ->
            // 先消费再跳转：replay = 1 会在重新订阅时重放旧事件，
            // 不消费的话每次进前台都会被再踢一次。
            sessionManager.consumeForcedLogout()
            if (navController.currentDestination?.route != Routes.LOGIN) {
                navController.navigate(Routes.LOGIN) {
                    // inclusive = false：保留首页，买家端允许游客浏览，
                    // 用户按返回键能退回首页而不是直接退出 App。
                    popUpTo(navController.graph.findStartDestination().id) { inclusive = false }
                    launchSingleTop = true
                }
            }
            snackbarHostState.showSnackbar(
                message = reason,
                duration = SnackbarDuration.Short
            )
        }
    }

    val showBottomBar = currentRoute in bottomTabs.map { it.route }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbarHostState) },
        bottomBar = {
            if (showBottomBar) {
                NavigationBar(containerColor = Color.White) {
                    bottomTabs.forEach { tab ->
                        val selected = currentRoute == tab.route
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                if (!selected) {
                                    navController.navigate(tab.route) {
                                        popUpTo(navController.graph.findStartDestination().id) {
                                            saveState = true
                                        }
                                        launchSingleTop = true
                                        restoreState = true
                                    }
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = Color(0xFFFF6B35),
                                selectedTextColor = Color(0xFFFF6B35),
                                unselectedIconColor = Color.Gray,
                                unselectedTextColor = Color.Gray
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        AppNavHost(
            navController = navController,
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        )
    }
}

@Composable
fun AppNavHost(
    navController: androidx.navigation.NavHostController,
    modifier: Modifier = Modifier
) {
    NavHost(
        navController = navController,
        startDestination = Routes.HOME,
        modifier = modifier
    ) {
        composable(Routes.HOME) {
            com.ecommerce.buyer.feature.home.HomeScreen(
                onProductClick = { id ->
                    navController.navigate(Routes.productDetail(id))
                },
                onNeedLogin = {
                    navController.navigate(Routes.LOGIN)
                }
            )
        }
        composable(Routes.CART) {
            com.ecommerce.buyer.feature.cart.CartScreen(
                onBack = { navController.popBackStack() },
                onCheckout = { productIds ->
                    navController.navigate(Routes.checkout(productIds))
                }
            )
        }
        composable(Routes.ORDERS) {
            com.ecommerce.buyer.feature.order.OrderListScreen(
                onBack = { navController.popBackStack() },
                onOrderClick = { id -> navController.navigate(Routes.orderDetail(id)) }
            )
        }
        composable(Routes.PROFILE) {
            com.ecommerce.buyer.feature.profile.ProfileScreen(
                onNavigateToOrders = { navController.navigate(Routes.ORDERS) },
                onNavigateToCart = { navController.navigate(Routes.CART) },
                onNavigateToAddresses = { navController.navigate(Routes.ADDRESS_LIST) },
                onNeedLogin = { navController.navigate(Routes.LOGIN) },
                onLogoutSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.HOME) { inclusive = true }
                    }
                }
            )
        }
        composable(Routes.LOGIN) {
            com.ecommerce.buyer.feature.auth.LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Routes.PRODUCT_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("id") { type = androidx.navigation.NavType.LongType })
        ) { backStackEntry ->
            val productId = backStackEntry.arguments?.getLong("id") ?: 0L
            com.ecommerce.buyer.feature.product.ProductDetailScreen(
                productId = productId,
                onBack = { navController.popBackStack() },
                onGoToCart = { navController.navigate(Routes.CART) },
                onBuyNow = { pid -> navController.navigate(Routes.checkout(listOf(pid))) }
            )
        }
        composable(
            route = Routes.CHECKOUT,
            arguments = listOf(androidx.navigation.navArgument("productIds") { type = androidx.navigation.NavType.StringType })
        ) {
            com.ecommerce.buyer.feature.order.CheckoutScreen(
                onBack = { navController.popBackStack() },
                onSelectAddress = { navController.navigate(Routes.ADDRESS_LIST) },
                onOrderCreated = { _ ->
                    navController.navigate(Routes.ORDERS) {
                        popUpTo(Routes.HOME)
                    }
                }
            )
        }
        composable(Routes.ADDRESS_LIST) {
            com.ecommerce.buyer.feature.order.AddressListScreen(
                onBack = { navController.popBackStack() },
                onAddressSelected = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.ORDER_DETAIL,
            arguments = listOf(androidx.navigation.navArgument("orderId") { type = androidx.navigation.NavType.StringType })
        ) {
            com.ecommerce.buyer.feature.order.OrderDetailScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
