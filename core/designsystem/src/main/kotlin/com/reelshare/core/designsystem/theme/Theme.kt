package com.reelshare.core.designsystem.theme

import android.app.Activity
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.*
import androidx.compose.ui.unit.sp
import androidx.core.view.WindowCompat
import com.reelshare.core.designsystem.R

// Footage supplies the color; blue marks interactive elements.
private val Light = lightColorScheme(
    primary = Color(0xFF2457E6), onPrimary = Color.White,
    primaryContainer = Color(0xFFE6ECFF), onPrimaryContainer = Color(0xFF123691),
    secondary = Color(0xFF526174), onSecondary = Color.White,
    secondaryContainer = Color(0xFFE6ECFF), onSecondaryContainer = Color(0xFF123691),
    tertiary = Color(0xFF526174), onTertiary = Color.White,
    tertiaryContainer = Color(0xFFEEF0F5), onTertiaryContainer = Color(0xFF171B24),
    background = Color(0xFFF7F8FA), onBackground = Color(0xFF171B24),
    surface = Color(0xFFF7F8FA), onSurface = Color(0xFF171B24),
    surfaceContainer = Color.White, surfaceVariant = Color(0xFFEEF0F5),
    surfaceContainerLowest = Color.White, surfaceContainerLow = Color(0xFFF1F3F7),
    surfaceContainerHigh = Color(0xFFECEFF5), surfaceContainerHighest = Color(0xFFE3E7F0),
    onSurfaceVariant = Color(0xFF5D6575), outline = Color(0xFF858C9B),
    outlineVariant = Color(0xFFDDE1E9)
)
private val Dark = darkColorScheme(
    primary = Color(0xFFA7BEFF), onPrimary = Color(0xFF102D75),
    primaryContainer = Color(0xFF233D78), onPrimaryContainer = Color(0xFFDCE5FF),
    secondary = Color(0xFFB5C3D8), onSecondary = Color(0xFF253042),
    secondaryContainer = Color(0xFF233D78), onSecondaryContainer = Color(0xFFDCE5FF),
    tertiary = Color(0xFFB5C3D8), onTertiary = Color(0xFF253042),
    tertiaryContainer = Color(0xFF252B37), onTertiaryContainer = Color(0xFFF0F1F5),
    background = Color(0xFF101217), onBackground = Color(0xFFF0F1F5),
    surface = Color(0xFF101217), onSurface = Color(0xFFF0F1F5),
    surfaceContainer = Color(0xFF1A1E27), surfaceVariant = Color(0xFF252B37),
    surfaceContainerLowest = Color(0xFF0C0E12), surfaceContainerLow = Color(0xFF151820),
    surfaceContainerHigh = Color(0xFF232833), surfaceContainerHighest = Color(0xFF2C3341),
    onSurfaceVariant = Color(0xFFA8B0C1), outline = Color(0xFF7C869A),
    outlineVariant = Color(0xFF343C4B)
)
private val Manrope = FontFamily(
    Font(R.font.manrope_regular, FontWeight.Normal),
    Font(R.font.manrope_medium, FontWeight.Medium),
    Font(R.font.manrope_semibold, FontWeight.SemiBold),
    Font(R.font.manrope_bold, FontWeight.Bold)
)
private fun type(size: Int, height: Int, weight: FontWeight = FontWeight.Normal) =
    TextStyle(fontFamily = Manrope, fontSize = size.sp, lineHeight = height.sp, fontWeight = weight)
private val StudioTypography = Typography(
    displayLarge = type(57, 64, FontWeight.Bold), displayMedium = type(45, 52, FontWeight.Bold),
    displaySmall = type(36, 43, FontWeight.Bold),
    headlineLarge = type(32, 40, FontWeight.Bold), headlineMedium = type(28, 36, FontWeight.Bold),
    headlineSmall = type(24, 32, FontWeight.Bold), titleLarge = type(22, 30, FontWeight.Bold),
    titleMedium = type(16, 24, FontWeight.SemiBold), titleSmall = type(14, 20, FontWeight.SemiBold),
    bodyLarge = type(16, 25), bodyMedium = type(14, 22), bodySmall = type(12, 18),
    labelLarge = type(14, 20, FontWeight.SemiBold), labelMedium = type(12, 18, FontWeight.SemiBold),
    labelSmall = type(11, 16, FontWeight.Medium)
)

@Composable
fun ReelShareTheme(appearance: String = "System", content: @Composable () -> Unit) {
    val dark = when (appearance) { "Dark" -> true; "Light" -> false; else -> isSystemInDarkTheme() }
    val view = LocalView.current
    if (!view.isInEditMode) SideEffect {
        (view.context as? Activity)?.window?.let { window ->
            WindowCompat.getInsetsController(window, view).apply {
                isAppearanceLightStatusBars = !dark
                isAppearanceLightNavigationBars = !dark
            }
        }
    }
    MaterialTheme(colorScheme = if (dark) Dark else Light, typography = StudioTypography, content = content)
}
