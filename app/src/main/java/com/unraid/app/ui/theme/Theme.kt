package com.unraid.app.ui.theme

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color

private val UnraidDarkColorScheme = darkColorScheme(
    primary = UnraidOrange,
    onPrimary = Color(0xFF1A0D00),
    primaryContainer = Color(0xFF4A2506),
    onPrimaryContainer = Color(0xFFFFDCC2),
    secondary = StatusBlue,
    onSecondary = Color(0xFF001F2A),
    background = DarkBackground,
    onBackground = TextPrimary,
    surface = DarkSurface,
    onSurface = TextPrimary,
    surfaceVariant = DarkSurfaceVariant,
    onSurfaceVariant = TextSecondary,
    outline = DarkOutline,
    error = StatusRed,
    onError = Color(0xFF2B0606)
)

/**
 * 다크 모드를 기본으로 적용하는 Material 3 앱 테마.
 */
@Composable
fun UnraidHubTheme(
    content: @Composable () -> Unit
) {
    MaterialTheme(
        colorScheme = UnraidDarkColorScheme,
        typography = AppTypography,
        content = content
    )
}
