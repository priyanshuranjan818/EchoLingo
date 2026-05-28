package com.echolingo.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.echolingo.app.data.db.AppDatabase
import com.echolingo.app.data.preferences.SettingsRepository
import com.echolingo.app.data.repository.HistoryRepository
import com.echolingo.app.ui.history.HistoryScreen
import com.echolingo.app.ui.home.HomeScreen
import com.echolingo.app.ui.player.PlayerScreen
import com.echolingo.app.ui.settings.SettingsSheet
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
    val navController      = rememberNavController()
    val context            = LocalContext.current
    val settingsRepository = remember { SettingsRepository(context.applicationContext) }
    val historyRepository  = remember {
        HistoryRepository(AppDatabase.getInstance(context).historyDao())
    }
    var showSettings by remember { mutableStateOf(false) }

    if (showSettings) {
        SettingsSheet(
            settingsRepository = settingsRepository,
            onDismiss          = { showSettings = false },
        )
    }

    NavHost(navController = navController, startDestination = "home") {

        composable("home") {
            HomeScreen(
                settingsRepository = settingsRepository,
                onOpenPlayer   = { videoId -> navController.navigate("player/$videoId") },
                onOpenHistory  = { navController.navigate("history") },
                onOpenSettings = { showSettings = true },
            )
        }

        composable("history") {
            HistoryScreen(
                historyRepository = historyRepository,
                onOpenPlayer = { videoId ->
                    navController.navigate("player/$videoId") {
                        popUpTo("history") { inclusive = false }
                    }
                },
                onBack = { navController.popBackStack() },
            )
        }

        composable(
            route     = "player/{videoId}",
            arguments = listOf(navArgument("videoId") { type = NavType.StringType }),
        ) { entry ->
            PlayerScreen(
                videoId            = entry.arguments?.getString("videoId").orEmpty(),
                settingsRepository = settingsRepository,
                historyRepository  = historyRepository,
                onBack             = { navController.popBackStack() },
            )
        }
    }
}
