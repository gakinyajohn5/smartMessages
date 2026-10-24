package com.example.mpesaboard

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val LightColors = lightColorScheme(
    primary = Color(0xFF1E7B3A),
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD3EFDA),
    onPrimaryContainer = Color(0xFF0A3318),
    surface = Color(0xFFFAFCFA),
    surfaceVariant = Color(0xFFE6EBE7),
    onSurfaceVariant = Color(0xFF1B1F1C),
    error = Color(0xFFB3261E),
)

private val DarkColors = darkColorScheme(
    primary = Color(0xFF6FD08A),
    onPrimary = Color(0xFF00391A),
    primaryContainer = Color(0xFF14502A),
    onPrimaryContainer = Color(0xFFD3EFDA),
    surface = Color(0xFF111412),
    surfaceVariant = Color(0xFF252A26),
    onSurfaceVariant = Color(0xFFE2E6E2),
)

@Composable
fun MpesaTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkColors else LightColors,
        content = content,
    )
}
