package com.gateshot.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val GateShotColors = darkColorScheme(
    primary = Color(0xFF4FC3F7),          // Light blue — visible on snow
    onPrimary = Color.Black,
    secondary = Color(0xFFFF7043),         // Orange — accent for recording/alerts
    onSecondary = Color.Black,
    tertiary = Color(0xFF66BB6A),          // Green — success/OK states
    background = Color(0xFF121212),
    surface = Color(0xFF1E1E1E),
    onBackground = Color.White,
    onSurface = Color.White,
    error = Color(0xFFEF5350),
    surfaceVariant = Color(0xFF2C2C2C),
    onSurfaceVariant = Color(0xFFB0B0B0)
)

@Composable
fun GateShotTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = GateShotColors,
        content = content
    )
}
