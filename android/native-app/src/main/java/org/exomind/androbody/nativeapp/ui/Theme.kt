package org.exomind.androbody.nativeapp.ui

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat

/** Named visual roles; no continuously animated status, blur layer or embedded Web runtime. */
object LayoutTokens { val pagePadding = 20.dp; val sectionGap = 20.dp; val itemGap = 10.dp; val railBreakpoint = 640.dp }
private val Light = lightColorScheme(
    primary = Color(0xFF006B60), onPrimary = Color.White, primaryContainer = Color(0xFFB7F1E2), onPrimaryContainer = Color(0xFF00201B),
    secondary = Color(0xFF4A6360), background = Color(0xFFF7FAF8), surface = Color(0xFFF7FAF8),
    onSurface = Color(0xFF17221F), surfaceVariant = Color(0xFFDFE9E3), onSurfaceVariant = Color(0xFF3F4944),
)
private val Dark = darkColorScheme(
    primary = Color(0xFF8DD8C7), onPrimary = Color(0xFF00382F), primaryContainer = Color(0xFF005045), onPrimaryContainer = Color(0xFFB7F1E2),
    secondary = Color(0xFFB1CCC5), background = Color(0xFF101816), surface = Color(0xFF101816),
    onSurface = Color(0xFFDFE9E3), surfaceVariant = Color(0xFF34423C), onSurfaceVariant = Color(0xFFBDC9C1),
)
@Composable fun MorphTheme(theme: String, content: @Composable () -> Unit) {
    val dark = when (theme) { "dark" -> true; "light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = Typography(), content = content)
}
