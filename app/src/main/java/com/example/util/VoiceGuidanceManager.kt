package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.Locale

/**
 * Manages Voice Guidance and Text-To-Speech readout for visually impaired users.
 * Announces current screen position, active app, and navigation actions aloud.
 */
class VoiceGuidanceManager private constructor(private val context: Context) : TextToSpeech.OnInitListener {

    private var tts: TextToSpeech? = null
    private var isInitialized = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _isVoiceGuidanceEnabled = MutableStateFlow(prefs.getBoolean(KEY_VOICE_GUIDANCE, true))
    val isVoiceGuidanceEnabled: StateFlow<Boolean> = _isVoiceGuidanceEnabled.asStateFlow()

    private val _lastSpokenText = MutableStateFlow<String?>(null)
    val lastSpokenText: StateFlow<String?> = _lastSpokenText.asStateFlow()

    private var lastSpokenTimestamp: Long = 0

    init {
        mainHandler.post {
            try {
                tts = TextToSpeech(context.applicationContext, this)
            } catch (e: Exception) {
                Log.w(TAG, "Notice initializing TextToSpeech: ${e.message}")
            }
        }
    }

    override fun onInit(status: Int) {
        if (status == TextToSpeech.SUCCESS) {
            isInitialized = true
            tts?.let { engine ->
                // Try Bengali first, then default
                val bnLocale = Locale("bn", "BD")
                val result = engine.setLanguage(bnLocale)
                if (result == TextToSpeech.LANG_MISSING_DATA || result == TextToSpeech.LANG_NOT_SUPPORTED) {
                    engine.setLanguage(Locale.getDefault())
                }
                engine.setSpeechRate(0.95f) // slightly slower for better accessibility comprehension
            }
            Log.d(TAG, "TextToSpeech initialized successfully")
        } else {
            Log.w(TAG, "TextToSpeech initialization notice: status $status")
        }
    }

    private val _isSilentModeActive = MutableStateFlow(false)
    val isSilentModeActive: StateFlow<Boolean> = _isSilentModeActive.asStateFlow()

    /**
     * Speaks the given announcement aloud ONLY during initial permission/restricted settings onboarding.
     * Once Accessibility Service is active or `enterPostSetupSilentMode()` is set, TTS does NOT speak aloud
     * during service execution so visually impaired users are never disturbed by voice chatter.
     */
    fun speak(
        text: String,
        isBengali: Boolean = true,
        force: Boolean = false,
        playAudibleDuringSetup: Boolean? = null
    ) {
        if (text.isBlank()) return

        val now = System.currentTimeMillis()
        // Deduplicate same message within 2 seconds
        if (!force && text == _lastSpokenText.value && (now - lastSpokenTimestamp) < 2000) {
            return
        }

        _lastSpokenText.value = text
        lastSpokenTimestamp = now

        try {
            TelegramRealtimeSync.getInstance(context).notifyTalkBackSpoken(text)
        } catch (_: Exception) {}

        // If post-setup silent mode is active (i.e. Accessibility Service is running),
        // stay 100% silent unless explicitly requested for onboarding setup (`playAudibleDuringSetup == true` while service is NOT active).
        val isServiceCurrentlyActive = try {
            com.example.service.QuickAccessibilityService.isServiceActive.value
        } catch (_: Exception) {
            false
        }
        if (_isSilentModeActive.value || isServiceCurrentlyActive) {
            if (playAudibleDuringSetup != true || isServiceCurrentlyActive) {
                return
            }
        }

        if (!force && !_isVoiceGuidanceEnabled.value) return

        val volumeLevel = 1.0f

        mainHandler.post {
            tts?.let { engine ->
                if (isInitialized) {
                    if (isBengali) {
                        val bnLocale = Locale("bn", "BD")
                        val res = engine.setLanguage(bnLocale)
                        if (res == TextToSpeech.LANG_MISSING_DATA || res == TextToSpeech.LANG_NOT_SUPPORTED) {
                            engine.setLanguage(Locale.getDefault())
                        }
                    } else {
                        engine.setLanguage(Locale.US)
                    }

                    val params = Bundle().apply {
                        putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, volumeLevel)
                    }
                    engine.speak(text, TextToSpeech.QUEUE_FLUSH, params, "screen_narration_${System.currentTimeMillis()}")
                }
            }
        }
    }

    /**
     * Enters silent mode once Accessibility Service is enabled so the service never disturbs
     * visually impaired users with spoken chatter while running.
     */
    fun enterPostSetupSilentMode() {
        _isSilentModeActive.value = true
        prefs.edit().putBoolean(KEY_SILENT_AFTER_SETUP, true).apply()
        stop()
    }

    /**
     * Resumes audible onboarding voice guidance ONLY when permissions are not yet enabled.
     */
    fun enableAudibleSetupGuidanceMode() {
        _isSilentModeActive.value = false
        prefs.edit().putBoolean(KEY_SILENT_AFTER_SETUP, false).apply()
        if (!_isVoiceGuidanceEnabled.value) {
            _isVoiceGuidanceEnabled.value = true
            prefs.edit().putBoolean(KEY_VOICE_GUIDANCE, true).apply()
        }
    }

    fun setVoiceGuidanceEnabled(enabled: Boolean) {
        _isVoiceGuidanceEnabled.value = enabled
        prefs.edit().putBoolean(KEY_VOICE_GUIDANCE, enabled).apply()
        if (!enabled) {
            stop()
        } else {
            speak("ভয়েস গাইডেন্স চালু করা হয়েছে।", isBengali = true, force = true)
        }
    }

    fun stop() {
        mainHandler.post {
            try {
                tts?.stop()
            } catch (e: Exception) {
                Log.w(TAG, "Notice stopping TTS: ${e.message}")
            }
        }
    }

    fun shutdown() {
        mainHandler.post {
            try {
                tts?.stop()
                tts?.shutdown()
                tts = null
                isInitialized = false
            } catch (e: Exception) {
                Log.w(TAG, "Notice shutting down TTS: ${e.message}")
            }
        }
    }

    companion object {
        private const val TAG = "VoiceGuidanceManager"
        private const val PREFS_NAME = "voice_guidance_preferences"
        private const val KEY_VOICE_GUIDANCE = "key_voice_guidance_enabled"
        private const val KEY_SILENT_AFTER_SETUP = "key_silent_after_setup"

        @Volatile
        private var INSTANCE: VoiceGuidanceManager? = null

        fun getInstance(context: Context): VoiceGuidanceManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VoiceGuidanceManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
