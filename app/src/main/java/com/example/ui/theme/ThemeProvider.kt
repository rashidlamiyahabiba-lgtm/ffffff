package com.example.ui.theme

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.compositionLocalOf
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Supported theme modes for the application.
 */
enum class ThemeMode {
    SYSTEM,
    LIGHT,
    DARK
}

/**
 * Controller interface exposed via CompositionLocal for UI components to read and update theme state.
 */
data class ThemeController(
    val currentMode: ThemeMode,
    val isDark: Boolean,
    val highContrast: Boolean,
    val setThemeMode: (ThemeMode) -> Unit,
    val toggleTheme: () -> Unit,
    val setHighContrast: (Boolean) -> Unit
)

val LocalThemeController = compositionLocalOf<ThemeController> {
    error("LocalThemeController not provided. Make sure to wrap in MyApplicationTheme.")
}

/**
 * Persistent provider managing application light/dark theme and high-contrast accessibility options.
 */
class ThemeProvider private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _themeMode = MutableStateFlow(loadSavedThemeMode())
    val themeMode: StateFlow<ThemeMode> = _themeMode.asStateFlow()

    private val _highContrast = MutableStateFlow(prefs.getBoolean(KEY_HIGH_CONTRAST, false))
    val highContrast: StateFlow<Boolean> = _highContrast.asStateFlow()

    private fun loadSavedThemeMode(): ThemeMode {
        val saved = prefs.getString(KEY_THEME_MODE, ThemeMode.SYSTEM.name)
        return try {
            ThemeMode.valueOf(saved ?: ThemeMode.SYSTEM.name)
        } catch (_: Exception) {
            ThemeMode.SYSTEM
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        _themeMode.value = mode
        prefs.edit().putString(KEY_THEME_MODE, mode.name).apply()
    }

    /**
     * Toggles between Light and Dark mode. If currently following System or Light, switches to Dark.
     * If currently Dark, switches to Light.
     */
    fun toggleTheme(isCurrentlyDark: Boolean) {
        val nextMode = if (isCurrentlyDark) ThemeMode.LIGHT else ThemeMode.DARK
        setThemeMode(nextMode)
    }

    fun setHighContrast(enabled: Boolean) {
        _highContrast.value = enabled
        prefs.edit().putBoolean(KEY_HIGH_CONTRAST, enabled).apply()
    }

    companion object {
        private const val PREFS_NAME = "app_theme_preferences"
        private const val KEY_THEME_MODE = "theme_mode"
        private const val KEY_HIGH_CONTRAST = "high_contrast_enabled"

        @Volatile
        private var INSTANCE: ThemeProvider? = null

        fun getInstance(context: Context): ThemeProvider {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: ThemeProvider(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
