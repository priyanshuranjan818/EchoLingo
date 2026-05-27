package com.echolingo.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val Colors = darkColorScheme(
    primary = Color(0xFF4DA3FF),
    secondary = Color(0xFFFFD54F),
    background = Color(0xFF101214),
    surface = Color(0xFF181B1F),
)

@Composable
fun EchoLingoTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = Colors,
        content = content,
    )
}
