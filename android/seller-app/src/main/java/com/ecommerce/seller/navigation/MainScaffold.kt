package com.ecommerce.seller.navigation

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.ListAlt
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.ShoppingBag
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument

private data class BottomTab(
    val route: String,
    val label: String,
    val icon: ImageVector
)

private val bottomTabs = listOf(
    BottomTab(Routes.HOME, "工作台", Icons.Default.Dashboard),
    BottomTab(Routes.PRODUCTS, "商品", Icons.Default.ShoppingBag),
    BottomTab(Routes.ORDERS, "订单", Icons.Default.ListAlt),
    BottomTab(Routes.PROFILE, "我的", Icons.Default.Person)
)

@Composable
fun MainScaffold() {
    val navController = rememberNavController()
    val backStackEntry by navController.currentBackStackEntryAsState()
    val currentRoute = backStackEntry?.destination?.route

    val showBottomBar = currentRoute in bottomTabs.map { it.route }

    Scaffold(
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
                                selectedIconColor = Color(0xFF1976D2),
                                selectedTextColor = Color(0xFF1976D2),
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
            com.ecommerce.seller.feature.home.HomeScreen(
                onNavigateToProducts = { navController.navigate(Routes.PRODUCTS) },
                onNavigateToOrders = { navController.navigate(Routes.ORDERS) },
                onNeedLogin = { navController.navigate(Routes.LOGIN) }
            )
        }
        composable(Routes.PRODUCTS) {
            com.ecommerce.seller.feature.product.ProductListScreen(
                onAddProduct = { navController.navigate(Routes.productEdit(0L)) },
                onEditProduct = { id -> navController.navigate(Routes.productEdit(id)) }
            )
        }
        composable(Routes.ORDERS) {
            com.ecommerce.seller.feature.order.OrderListScreen(
                onOrderClick = { id -> navController.navigate(Routes.orderDetail(id)) }
            )
        }
        composable(Routes.PROFILE) {
            com.ecommerce.seller.feature.profile.ProfileScreen(
                onLogin = { navController.navigate(Routes.LOGIN) }
            )
        }
        composable(Routes.LOGIN) {
            com.ecommerce.seller.feature.auth.LoginScreen(
                onLoginSuccess = {
                    navController.navigate(Routes.HOME) {
                        popUpTo(Routes.LOGIN) { inclusive = true }
                    }
                }
            )
        }
        composable(
            route = Routes.PRODUCT_EDIT,
            arguments = listOf(navArgument("productId") { type = NavType.LongType })
        ) {
            com.ecommerce.seller.feature.product.ProductEditScreen(
                onBack = { navController.popBackStack() },
                onSaved = { navController.popBackStack() }
            )
        }
        composable(
            route = Routes.ORDER_DETAIL,
            arguments = listOf(navArgument("orderId") { type = NavType.LongType })
        ) {
            com.ecommerce.seller.feature.order.OrderDetailScreen(
                onBack = { navController.popBackStack() }
            )
        }
    }
}
