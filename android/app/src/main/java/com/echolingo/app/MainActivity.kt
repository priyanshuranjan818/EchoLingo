package com.echolingo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.echolingo.app.data.preferences.SettingsRepository
import com.echolingo.app.ui.home.HomeScreen
import com.echolingo.app.ui.player.PlayerScreen
import com.echolingo.app.ui.theme.EchoLingoTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        WindowInsetsControllerCompat(window, window.decorView).apply {
            hide(WindowInsetsCompat.Type.systemBars())
            systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        }
        setContent {
            EchoLingoTheme {
                EchoLingoApp()
            }
        }
    }
}

@Composable
private fun EchoLingoApp() {
    val navController = rememberNavController()
    val context = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }

    NavHost(navController = navController, startDestination = "home") {
        composable("home") {
            HomeScreen(
                settingsRepository = settingsRepository,
                onOpenPlayer = { videoId ->
                    navController.navigate("player/$videoId")
                },
            )
        }
        composable(
            route = "player/{videoId}",
            arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                videoId = entry.arguments?.getString("videoId").orEmpty(),
                settingsRepository = settingsRepository,
                onBack = { navController.popBackStack() },
            )
        }
    }
}
