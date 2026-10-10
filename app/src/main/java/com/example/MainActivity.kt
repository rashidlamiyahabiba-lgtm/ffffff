package com.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.animation.Crossfade
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.example.ui.AccessibilityScreen
import com.example.ui.AuthScreen
import com.example.ui.MainViewModel
import com.example.ui.PhoneNumberSessionManager
import com.example.ui.authStateFlow
import com.example.ui.theme.MyApplicationTheme
import com.google.firebase.Firebase
import com.google.firebase.auth.auth

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        PhoneNumberSessionManager.init(this)
        enableEdgeToEdge()
        setContent {
            val themeMode by viewModel.themeMode.collectAsStateWithLifecycle()
            val highContrast by viewModel.highContrast.collectAsStateWithLifecycle()
            val currentUser by Firebase.auth.authStateFlow().collectAsStateWithLifecycle(
                initialValue = Firebase.auth.currentUser
            )
            val loggedInPhone by PhoneNumberSessionManager.loggedInPhone.collectAsStateWithLifecycle()

            MyApplicationTheme(
                themeMode = themeMode,
                highContrast = highContrast,
                dynamicColor = false,
                onThemeModeChange = { viewModel.setThemeMode(it) },
                onToggleTheme = {
                    val isCurrentlyDark = when (themeMode) {
                        com.example.ui.theme.ThemeMode.DARK -> true
                        com.example.ui.theme.ThemeMode.LIGHT -> false
                        com.example.ui.theme.ThemeMode.SYSTEM -> false
                    }
                    viewModel.toggleTheme(isCurrentlyDark)
                },
                onHighContrastChange = { viewModel.setHighContrast(it) }
            ) {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val user = currentUser
                    val effectiveId = loggedInPhone.ifBlank { user?.uid.orEmpty() }
                    val effectiveLabel = if (loggedInPhone.isNotBlank()) {
                        "Phone: $loggedInPhone"
                    } else {
                        user?.email.orEmpty()
                    }
                    if (effectiveId.isBlank()) {
                        AuthScreen(
                            onAuthSuccess = {},
                            viewModel = viewModel
                        )
                    } else {
                        AccessibilityScreen(
                            viewModel = viewModel,
                            currentUserId = effectiveId,
                            userEmail = effectiveLabel
                        )
                    }
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshStatus(this)
        viewModel.refreshBatteryOptimizationStatus(this)
    }
}
