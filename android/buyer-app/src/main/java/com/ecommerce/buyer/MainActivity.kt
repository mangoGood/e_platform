package com.ecommerce.buyer

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import com.ecommerce.buyer.navigation.MainScaffold
import com.ecommerce.core.network.SessionManager
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    /**
     * 全局会话事件总线（`@Singleton`）。
     *
     * 在 Activity 层注入再传给 Compose，而不是在 Composable 里用 ViewModel 兜一层：
     * 强制登出是**全局**事件，不属于任何一个业务 ViewModel 的职责。
     */
    @Inject
    lateinit var sessionManager: SessionManager

    override fun onCreate(savedInstanceState: Bundle?) {
        installSplashScreen()
        super.onCreate(savedInstanceState)
        setContent {
            Surface(
                modifier = Modifier.fillMaxSize(),
                color = Color.White
            ) {
                MainScaffold(sessionManager = sessionManager)
            }
        }
    }
}
