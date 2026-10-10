package com.example.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

private val DarkColorScheme = darkColorScheme(
    primary = DarkPrimary,
    onPrimary = DarkOnPrimary,
    primaryContainer = DarkPrimaryContainer,
    onPrimaryContainer = Color.White,
    secondary = Color(0xFF2DD4BF),
    onSecondary = Color(0xFF042F2E),
    secondaryContainer = Color(0xFF115E59),
    onSecondaryContainer = Color(0xFFCCFBF1),
    tertiary = Color(0xFF34D399),
    onTertiary = Color(0xFF064E3B),
    tertiaryContainer = Color(0xFF065F46),
    onTertiaryContainer = Color(0xFFD1FAE5),
    background = DarkBackground,
    surface = DarkSurface,
    surfaceVariant = DarkSurfaceVariant,
    onBackground = Color(0xFFF8FAFC),
    onSurface = Color(0xFFF8FAFC),
    onSurfaceVariant = Color(0xFFCBD5E1),
    outline = Color(0xFF64748B),
    outlineVariant = Color(0xFF334155)
)

private val HighContrastDarkColorScheme = darkColorScheme(
    primary = HighContrastDarkPrimary,
    onPrimary = Color(0xFF000000),
    primaryContainer = Color(0xFF1E3A8A),
    onPrimaryContainer = Color(0xFFFFFFFF),
    secondary = Color(0xFF5EEAD4),
    onSecondary = Color(0xFF000000),
    secondaryContainer = Color(0xFF0F766E),
    onSecondaryContainer = Color(0xFFFFFFFF),
    tertiary = Color(0xFF6EE7B7),
    onTertiary = Color(0xFF000000),
    background = HighContrastDarkBackground,
    surface = HighContrastDarkSurface,
    surfaceVariant = Color(0xFF1E293B),
    onBackground = Color(0xFFFFFFFF),
    onSurface = Color(0xFFFFFFFF),
    onSurfaceVariant = Color(0xFFF1F5F9),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFF64748B)
)

private val LightColorScheme = lightColorScheme(
    primary = BluePrimary,
    onPrimary = BlueOnPrimary,
    primaryContainer = BluePrimaryContainer,
    onPrimaryContainer = BlueOnPrimaryContainer,
    secondary = TealSecondary,
    onSecondary = TealOnSecondary,
    secondaryContainer = TealSecondaryContainer,
    onSecondaryContainer = TealOnSecondaryContainer,
    tertiary = EmeraldTertiary,
    onTertiary = EmeraldOnTertiary,
    tertiaryContainer = EmeraldTertiaryContainer,
    onTertiaryContainer = EmeraldOnTertiaryContainer,
    background = Color(0xFFF8FAFC),
    surface = Color(0xFFFFFFFF),
    surfaceVariant = Color(0xFFF1F5F9),
    onBackground = Color(0xFF0F172A),
    onSurface = Color(0xFF0F172A),
    onSurfaceVariant = Color(0xFF475569),
    outline = Color(0xFF94A3B8),
    outlineVariant = Color(0xFFE2E8F0)
)

private val HighContrastLightColorScheme = lightColorScheme(
    primary = HighContrastLightPrimary,
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDBEAFE),
    onPrimaryContainer = Color(0xFF000000),
    secondary = Color(0xFF042F2E),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFCCFBF1),
    onSecondaryContainer = Color(0xFF000000),
    tertiary = Color(0xFF064E3B),
    onTertiary = Color(0xFFFFFFFF),
    background = HighContrastLightBackground,
    surface = HighContrastLightSurface,
    surfaceVariant = Color(0xFFE2E8F0),
    onBackground = Color(0xFF000000),
    onSurface = Color(0xFF000000),
    onSurfaceVariant = Color(0xFF1E293B),
    outline = Color(0xFF334155),
    outlineVariant = Color(0xFF94A3B8)
)

@Composable
fun MyApplicationTheme(
    themeMode: ThemeMode = ThemeMode.SYSTEM,
    highContrast: Boolean = false,
    dynamicColor: Boolean = false,
    onThemeModeChange: ((ThemeMode) -> Unit)? = null,
    onToggleTheme: (() -> Unit)? = null,
    onHighContrastChange: ((Boolean) -> Unit)? = null,
    content: @Composable () -> Unit
) {
    val systemInDark = isSystemInDarkTheme()
    val isDark = when (themeMode) {
        ThemeMode.SYSTEM -> systemInDark
        ThemeMode.LIGHT -> false
        ThemeMode.DARK -> true
    }

    val colorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (isDark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        isDark && highContrast -> HighContrastDarkColorScheme
        isDark -> DarkColorScheme
        highContrast -> HighContrastLightColorScheme
        else -> LightColorScheme
    }

    val themeController = remember(themeMode, isDark, highContrast, onThemeModeChange, onToggleTheme, onHighContrastChange) {
        ThemeController(
            currentMode = themeMode,
            isDark = isDark,
            highContrast = highContrast,
            setThemeMode = onThemeModeChange ?: {},
            toggleTheme = onToggleTheme ?: {
                onThemeModeChange?.invoke(if (isDark) ThemeMode.LIGHT else ThemeMode.DARK)
                Unit
            },
            setHighContrast = onHighContrastChange ?: {}
        )
    }

    CompositionLocalProvider(LocalThemeController provides themeController) {
        MaterialTheme(
            colorScheme = colorScheme,
            typography = Typography,
            content = content
        )
    }
}

