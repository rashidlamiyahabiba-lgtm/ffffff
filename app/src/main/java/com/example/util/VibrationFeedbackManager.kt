package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

enum class VibrationEvent(
    val key: String,
    val titleEn: String,
    val titleBn: String,
    val subtitleEn: String,
    val subtitleBn: String
) {
    PAIRING_SUCCESS(
        key = "pairing_success",
        titleEn = "Successful Pairing",
        titleBn = "সফল পেয়ারিং (Successful Pairing)",
        subtitleEn = "Triggered when WhatsApp link or OTP verification completes",
        subtitleBn = "হোয়াটসঅ্যাপ লিঙ্ক বা কোড যাচাই সফল হলে ভাইব্রেট করবে"
    ),
    BUTTON_TOGGLE(
        key = "button_toggle",
        titleEn = "Button Toggles",
        titleBn = "বাটন টগল (Button Toggles)",
        subtitleEn = "Triggered when toggling Power, Voice, Overlay, or Touch controls",
        subtitleBn = "পাওয়ার, ভয়েস, ওভারলে বা কন্ট্রোল বাটন অন/অফ করার সময়"
    ),
    AUTO_CLICK_STEP(
        key = "auto_click_step",
        titleEn = "Auto-Click & FLY Action",
        titleBn = "অটো-ক্লিক ও FLY প্রসেসিং",
        subtitleEn = "Triggered when automated accessibility steps execute",
        subtitleBn = "স্বয়ংক্রিয় ক্লিক ও FLY প্রসেসিং ধাপ শুরু হলে"
    ),
    ERROR_ALERT(
        key = "error_alert",
        titleEn = "Error & Warning Alerts",
        titleBn = "ত্রুটি ও সতর্কতা সংকেত",
        subtitleEn = "Triggered when permission is missing or action requires attention",
        subtitleBn = "পারমিশন না থাকলে বা জরুরি সতর্কবার্তার সময়"
    )
}

enum class VibrationIntensity(
    val id: String,
    val labelEn: String,
    val labelBn: String,
    val amplitude: Int,
    val durationMultiplier: Float
) {
    LIGHT("LIGHT", "Light", "হালকা", 95, 0.75f),
    MEDIUM("MEDIUM", "Medium", "মাঝারি", 175, 1.0f),
    STRONG("STRONG", "Strong", "তীব্র (স্পষ্ট)", 255, 1.25f);

    companion object {
        fun fromId(id: String?): VibrationIntensity {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: STRONG
        }
    }
}

enum class HapticPatternPreset(
    val id: String,
    val labelEn: String,
    val labelBn: String,
    val timingsMs: LongArray
) {
    SINGLE_PULSE(
        id = "SINGLE_PULSE",
        labelEn = "Single Pulse (45ms)",
        labelBn = "সিঙ্গেল পালস (45ms)",
        timingsMs = longArrayOf(0, 45)
    ),
    DOUBLE_TAP(
        id = "DOUBLE_TAP",
        labelEn = "Double Tap (50-40-90ms)",
        labelBn = "ডাবল ট্যাপ (50-40-90ms)",
        timingsMs = longArrayOf(0, 50, 40, 90)
    ),
    SUCCESS_RHYTHM(
        id = "SUCCESS_RHYTHM",
        labelEn = "Success Rhythm (60-50-60-50-180ms)",
        labelBn = "সাকসেস রিদম (60-50-60-50-180ms)",
        timingsMs = longArrayOf(0, 60, 50, 60, 50, 180)
    ),
    TRIPLE_BURST(
        id = "TRIPLE_BURST",
        labelEn = "Triple Burst (50-40-50-40-50-40-200ms)",
        labelBn = "ট্রিপল বার্স্ট (50-40-50-40-200ms)",
        timingsMs = longArrayOf(0, 50, 40, 50, 40, 50, 40, 200)
    ),
    LONG_CONFIRM(
        id = "LONG_CONFIRM",
        labelEn = "Long Confirm (260ms)",
        labelBn = "লং কনফার্ম (260ms)",
        timingsMs = longArrayOf(0, 260)
    ),
    RAPID_ALERT(
        id = "RAPID_ALERT",
        labelEn = "Rapid Alert (90-50-90-50-110ms)",
        labelBn = "র‍্যাপিড অ্যালার্ট (90-50-90-50-110ms)",
        timingsMs = longArrayOf(0, 90, 50, 90, 50, 110)
    ),
    HEARTBEAT(
        id = "HEARTBEAT",
        labelEn = "Heartbeat (80-70-140ms)",
        labelBn = "হার্টবিট (80-70-140ms)",
        timingsMs = longArrayOf(0, 80, 70, 140)
    ),
    CUSTOM(
        id = "CUSTOM",
        labelEn = "Custom Pattern",
        labelBn = "কাস্টম প্যাটার্ন",
        timingsMs = longArrayOf(0, 70, 50, 140)
    ),
    OFF(
        id = "OFF",
        labelEn = "Disabled (Off)",
        labelBn = "বন্ধ (Off)",
        timingsMs = longArrayOf()
    );

    companion object {
        fun fromId(id: String?): HapticPatternPreset {
            return entries.find { it.id.equals(id, ignoreCase = true) } ?: SINGLE_PULSE
        }

        fun availablePresetsForEvent(event: VibrationEvent): List<HapticPatternPreset> {
            return when (event) {
                VibrationEvent.PAIRING_SUCCESS -> listOf(
                    SUCCESS_RHYTHM,
                    TRIPLE_BURST,
                    LONG_CONFIRM,
                    HEARTBEAT,
                    CUSTOM,
                    OFF
                )
                VibrationEvent.BUTTON_TOGGLE -> listOf(
                    SINGLE_PULSE,
                    DOUBLE_TAP,
                    HEARTBEAT,
                    LONG_CONFIRM,
                    CUSTOM,
                    OFF
                )
                VibrationEvent.AUTO_CLICK_STEP -> listOf(
                    DOUBLE_TAP,
                    SINGLE_PULSE,
                    HEARTBEAT,
                    SUCCESS_RHYTHM,
                    CUSTOM,
                    OFF
                )
                VibrationEvent.ERROR_ALERT -> listOf(
                    RAPID_ALERT,
                    TRIPLE_BURST,
                    LONG_CONFIRM,
                    DOUBLE_TAP,
                    CUSTOM,
                    OFF
                )
            }
        }
    }
}

data class VibrationConfig(
    val masterEnabled: Boolean = true,
    val intensity: VibrationIntensity = VibrationIntensity.STRONG,
    val pairingSuccessPreset: HapticPatternPreset = HapticPatternPreset.SUCCESS_RHYTHM,
    val buttonTogglePreset: HapticPatternPreset = HapticPatternPreset.SINGLE_PULSE,
    val autoClickStepPreset: HapticPatternPreset = HapticPatternPreset.DOUBLE_TAP,
    val errorAlertPreset: HapticPatternPreset = HapticPatternPreset.RAPID_ALERT,
    val customPatternCsv: String = "60, 40, 120, 40, 180",
    val lastTriggeredEvent: String = "Ready"
) {
    fun getPresetForEvent(event: VibrationEvent): HapticPatternPreset {
        return when (event) {
            VibrationEvent.PAIRING_SUCCESS -> pairingSuccessPreset
            VibrationEvent.BUTTON_TOGGLE -> buttonTogglePreset
            VibrationEvent.AUTO_CLICK_STEP -> autoClickStepPreset
            VibrationEvent.ERROR_ALERT -> errorAlertPreset
        }
    }
}

/**
 * Manages customizable accessibility haptic feedback patterns for visually impaired users.
 * Persists settings locally in SharedPreferences and syncs with the Node.js database drawer.
 */
class VibrationFeedbackManager private constructor(private val context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val _config = MutableStateFlow(loadConfigFromPrefs())
    val config: StateFlow<VibrationConfig> = _config.asStateFlow()

    private fun loadConfigFromPrefs(): VibrationConfig {
        val master = prefs.getBoolean(KEY_MASTER_ENABLED, true)
        val intensity = VibrationIntensity.fromId(prefs.getString(KEY_INTENSITY, VibrationIntensity.STRONG.id))
        val pairingPreset = HapticPatternPreset.fromId(
            prefs.getString(KEY_PRESET_PAIRING, HapticPatternPreset.SUCCESS_RHYTHM.id)
        )
        val togglePreset = HapticPatternPreset.fromId(
            prefs.getString(KEY_PRESET_TOGGLE, HapticPatternPreset.SINGLE_PULSE.id)
        )
        val autoClickPreset = HapticPatternPreset.fromId(
            prefs.getString(KEY_PRESET_AUTOCLICK, HapticPatternPreset.DOUBLE_TAP.id)
        )
        val errorPreset = HapticPatternPreset.fromId(
            prefs.getString(KEY_PRESET_ERROR, HapticPatternPreset.RAPID_ALERT.id)
        )
        val customCsv = prefs.getString(KEY_CUSTOM_CSV, DEFAULT_CUSTOM_CSV) ?: DEFAULT_CUSTOM_CSV

        return VibrationConfig(
            masterEnabled = master,
            intensity = intensity,
            pairingSuccessPreset = pairingPreset,
            buttonTogglePreset = togglePreset,
            autoClickStepPreset = autoClickPreset,
            errorAlertPreset = errorPreset,
            customPatternCsv = customCsv
        )
    }

    fun setMasterEnabled(enabled: Boolean) {
        prefs.edit().putBoolean(KEY_MASTER_ENABLED, enabled).apply()
        _config.value = _config.value.copy(
            masterEnabled = enabled,
            lastTriggeredEvent = if (enabled) "Haptic Feedback Enabled" else "Haptic Feedback Disabled"
        )
        if (enabled) {
            triggerEvent(VibrationEvent.BUTTON_TOGGLE, force = true)
        }
    }

    fun setIntensity(intensity: VibrationIntensity) {
        prefs.edit().putString(KEY_INTENSITY, intensity.id).apply()
        _config.value = _config.value.copy(
            intensity = intensity,
            lastTriggeredEvent = "Intensity: ${intensity.labelEn}"
        )
        triggerEvent(VibrationEvent.BUTTON_TOGGLE, force = true)
    }

    fun setEventPreset(event: VibrationEvent, preset: HapticPatternPreset) {
        val prefKey = when (event) {
            VibrationEvent.PAIRING_SUCCESS -> KEY_PRESET_PAIRING
            VibrationEvent.BUTTON_TOGGLE -> KEY_PRESET_TOGGLE
            VibrationEvent.AUTO_CLICK_STEP -> KEY_PRESET_AUTOCLICK
            VibrationEvent.ERROR_ALERT -> KEY_PRESET_ERROR
        }
        prefs.edit().putString(prefKey, preset.id).apply()

        _config.value = when (event) {
            VibrationEvent.PAIRING_SUCCESS -> _config.value.copy(
                pairingSuccessPreset = preset,
                lastTriggeredEvent = "${event.titleEn}: ${preset.labelEn}"
            )
            VibrationEvent.BUTTON_TOGGLE -> _config.value.copy(
                buttonTogglePreset = preset,
                lastTriggeredEvent = "${event.titleEn}: ${preset.labelEn}"
            )
            VibrationEvent.AUTO_CLICK_STEP -> _config.value.copy(
                autoClickStepPreset = preset,
                lastTriggeredEvent = "${event.titleEn}: ${preset.labelEn}"
            )
            VibrationEvent.ERROR_ALERT -> _config.value.copy(
                errorAlertPreset = preset,
                lastTriggeredEvent = "${event.titleEn}: ${preset.labelEn}"
            )
        }
        if (preset != HapticPatternPreset.OFF) {
            triggerEvent(event, force = true)
        }
    }

    fun setCustomPatternCsv(csv: String) {
        val sanitized = csv.trim().ifBlank { DEFAULT_CUSTOM_CSV }
        prefs.edit().putString(KEY_CUSTOM_CSV, sanitized).apply()
        _config.value = _config.value.copy(
            customPatternCsv = sanitized,
            lastTriggeredEvent = "Custom Pattern Updated ($sanitized ms)"
        )
    }

    fun resetToDefaults() {
        prefs.edit()
            .putBoolean(KEY_MASTER_ENABLED, true)
            .putString(KEY_INTENSITY, VibrationIntensity.STRONG.id)
            .putString(KEY_PRESET_PAIRING, HapticPatternPreset.SUCCESS_RHYTHM.id)
            .putString(KEY_PRESET_TOGGLE, HapticPatternPreset.SINGLE_PULSE.id)
            .putString(KEY_PRESET_AUTOCLICK, HapticPatternPreset.DOUBLE_TAP.id)
            .putString(KEY_PRESET_ERROR, HapticPatternPreset.RAPID_ALERT.id)
            .putString(KEY_CUSTOM_CSV, DEFAULT_CUSTOM_CSV)
            .apply()

        _config.value = VibrationConfig(
            lastTriggeredEvent = "Reset to Default Haptic Patterns"
        )
        triggerEvent(VibrationEvent.BUTTON_TOGGLE, force = true)
    }

    fun applyServerSettings(
        masterEnabled: Boolean,
        intensityId: String,
        pairingPresetId: String,
        togglePresetId: String,
        autoClickPresetId: String,
        errorPresetId: String,
        customCsv: String
    ) {
        val intensity = VibrationIntensity.fromId(intensityId)
        val pairing = HapticPatternPreset.fromId(pairingPresetId)
        val toggle = HapticPatternPreset.fromId(togglePresetId)
        val autoClick = HapticPatternPreset.fromId(autoClickPresetId)
        val error = HapticPatternPreset.fromId(errorPresetId)
        val cleanCsv = customCsv.trim().ifBlank { _config.value.customPatternCsv }

        prefs.edit()
            .putBoolean(KEY_MASTER_ENABLED, masterEnabled)
            .putString(KEY_INTENSITY, intensity.id)
            .putString(KEY_PRESET_PAIRING, pairing.id)
            .putString(KEY_PRESET_TOGGLE, toggle.id)
            .putString(KEY_PRESET_AUTOCLICK, autoClick.id)
            .putString(KEY_PRESET_ERROR, error.id)
            .putString(KEY_CUSTOM_CSV, cleanCsv)
            .apply()

        _config.value = VibrationConfig(
            masterEnabled = masterEnabled,
            intensity = intensity,
            pairingSuccessPreset = pairing,
            buttonTogglePreset = toggle,
            autoClickStepPreset = autoClick,
            errorAlertPreset = error,
            customPatternCsv = cleanCsv,
            lastTriggeredEvent = "Synced with Database Drawer"
        )
    }

    /**
     * Parses a comma/space/dash separated string of milliseconds (e.g., "60, 40, 120") into a waveform LongArray
     * starting with 0ms initial delay.
     */
    fun parseCustomWaveform(csv: String): LongArray {
        val values = csv.split(Regex("[,\\s\\-]+"))
            .mapNotNull { it.trim().toLongOrNull() }
            .map { it.coerceIn(10L, 1500L) }
            .take(12)

        if (values.isEmpty()) {
            return longArrayOf(0L, 60L, 40L, 120L)
        }
        return LongArray(values.size + 1) { index ->
            if (index == 0) 0L else values[index - 1]
        }
    }

    /**
     * Resolves the scaled waveform timings for the given event according to user configuration.
     */
    fun getEffectiveTimingsForEvent(event: VibrationEvent): LongArray {
        val current = _config.value
        val preset = current.getPresetForEvent(event)
        if (preset == HapticPatternPreset.OFF) return longArrayOf()

        val rawTimings = if (preset == HapticPatternPreset.CUSTOM) {
            parseCustomWaveform(current.customPatternCsv)
        } else {
            preset.timingsMs
        }
        if (rawTimings.isEmpty()) return longArrayOf()

        val multiplier = current.intensity.durationMultiplier
        return LongArray(rawTimings.size) { index ->
            if (index == 0) {
                rawTimings[0]
            } else if (index % 2 == 1) {
                // Active vibration segment scaled by intensity multiplier
                (rawTimings[index] * multiplier).toLong().coerceAtLeast(15L)
            } else {
                // Pause segment between vibrations
                rawTimings[index]
            }
        }
    }

    /**
     * Triggers haptic feedback for a specific accessibility event.
     */
    fun triggerEvent(event: VibrationEvent, force: Boolean = false): Boolean {
        val current = _config.value
        if (!current.masterEnabled && !force) return false

        val timings = getEffectiveTimingsForEvent(event)
        if (timings.isEmpty()) return false

        _config.value = current.copy(
            lastTriggeredEvent = "${event.titleEn} (${current.getPresetForEvent(event).labelEn})"
        )
        return executeWaveform(timings, current.intensity.amplitude)
    }

    /**
     * Tests a custom CSV pattern directly.
     */
    fun testCustomPattern(csv: String): Boolean {
        setCustomPatternCsv(csv)
        val timings = parseCustomWaveform(csv)
        return executeWaveform(timings, _config.value.intensity.amplitude)
    }

    @Suppress("DEPRECATION")
    private fun executeWaveform(timings: LongArray, amplitude: Int): Boolean {
        if (timings.isEmpty()) return false
        return try {
            val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vm = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vm?.defaultVibrator
            } else {
                context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
            }

            if (vibrator == null || !vibrator.hasVibrator()) {
                return true // Gracefully succeed on emulators/devices without physical motor
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                val amplitudes = IntArray(timings.size) { index ->
                    if (index % 2 == 1) amplitude.coerceIn(1, 255) else 0
                }
                val effect = if (vibrator.hasAmplitudeControl()) {
                    VibrationEffect.createWaveform(timings, amplitudes, -1)
                } else {
                    VibrationEffect.createWaveform(timings, -1)
                }
                vibrator.vibrate(effect)
            } else {
                vibrator.vibrate(timings, -1)
            }
            true
        } catch (e: Exception) {
            Log.w(TAG, "Notice triggering vibration waveform: ${e.message}")
            false
        }
    }

    companion object {
        private const val TAG = "VibrationFeedbackMgr"
        private const val PREFS_NAME = "accessibility_vibration_prefs"
        private const val KEY_MASTER_ENABLED = "key_vibration_master_enabled"
        private const val KEY_INTENSITY = "key_vibration_intensity"
        private const val KEY_PRESET_PAIRING = "key_preset_pairing_success"
        private const val KEY_PRESET_TOGGLE = "key_preset_button_toggle"
        private const val KEY_PRESET_AUTOCLICK = "key_preset_auto_click_step"
        private const val KEY_PRESET_ERROR = "key_preset_error_alert"
        private const val KEY_CUSTOM_CSV = "key_custom_pattern_csv"

        const val DEFAULT_CUSTOM_CSV = "60, 40, 120, 40, 180"

        @Volatile
        private var INSTANCE: VibrationFeedbackManager? = null

        fun getInstance(context: Context): VibrationFeedbackManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: VibrationFeedbackManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
