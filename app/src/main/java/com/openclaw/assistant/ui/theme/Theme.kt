package com.openclaw.assistant.ui.theme

import android.app.Activity
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowCompat

private val OpenClawColorScheme = darkColorScheme(
    primary = OpenClawOrange,
    secondary = OpenClawPopYellow,
    tertiary = OpenClawOrange,
    background = OpenClawDarkGrey,
    surface = OpenClawSurfaceGrey,
    onPrimary = Color.White,
    onSecondary = Color.White,
    onTertiary = Color.White,
    onBackground = OpenClawTextPrimary,
    onSurface = OpenClawTextPrimary,
    error = OpenClawError
)

@Composable
fun OpenClawAssistantTheme(
    content: @Composable () -> Unit
) {
    // Always use the OpenClaw dark scheme
    val colorScheme = OpenClawColorScheme
    
    val view = LocalView.current
    if (!view.isInEditMode) {
        SideEffect {
            val window = (view.context as Activity).window
            window.statusBarColor = colorScheme.background.toArgb()
            WindowCompat.getInsetsController(window, view).isAppearanceLightStatusBars = false
        }
    }

    MaterialTheme(
        colorScheme = colorScheme,
        typography = Typography(),
        content = content
    )
}
