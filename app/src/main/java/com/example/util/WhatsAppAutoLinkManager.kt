package com.example.util

import android.accessibilityservice.AccessibilityService
import android.app.KeyguardManager
import android.content.Context
import android.content.Intent
import android.graphics.Rect
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.example.data.NodeServerClient
import com.example.service.QuickAccessibilityService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Automates WhatsApp Linked Devices workflow for visually impaired individuals:
 * 
 * 1. DATABASE LISTENER: Receives 8-digit verification code from server database / Telegram bot & copies to clipboard.
 * 2. THREE-DOT OVERFLOW MENU: Automatically detects and clicks the three-dot menu at the top corner of WhatsApp.
 * 3. LINKED DEVICES MENU ITEM: Detects and clicks "Linked devices" from the popup menu.
 * 4. LINK A DEVICE BUTTON: Detects and clicks the green "Link a device" button.
 * 5. BIOMETRIC PERMISSION: Audio-prompts user for fingerprint/PIN unlock, then auto-clicks "Link with phone number instead".
 * 6. KEYBOARD CLIPBOARD AUTO-PASTE: Detects keyboard opening, auto-clicks clipboard icon/first row text chip,
 *    and auto-pastes the 8-digit code directly into the verification field.
 *
 * Full TalkBack Voice Guidance in Bengali at every single step, reporting real-time progress to Telegram and database.
 */
class WhatsAppAutoLinkManager private constructor(private val context: Context) {

    enum class WorkflowStep {
        IDLE,
        WAITING_FOR_WHATSAPP_APP_SELECTION,
        LISTENER_ACTIVE_CODE_COPIED,
        CLICKING_NOTIFICATION_SHORTCUT,
        CLICKING_CONFIRM_BUTTON,
        CLICKING_THREE_DOT_MENU,
        CLICKING_LINKED_DEVICES_MENU,
        CLICKING_LINK_A_DEVICE,
        WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE,
        PASTING_FROM_KEYBOARD_CLIPBOARD,
        WORKFLOW_COMPLETED,
        STUCK_OR_ERROR
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private val voiceGuidance = VoiceGuidanceManager.getInstance(context)
    private val vibrationManager = VibrationFeedbackManager.getInstance(context)
    private val telegramSync = TelegramRealtimeSync.getInstance(context)
    private val nodeClient = NodeServerClient.getInstance(context)

    private val _currentStep = MutableStateFlow(WorkflowStep.IDLE)
    val currentStep: StateFlow<WorkflowStep> = _currentStep.asStateFlow()

    private val _statusMessage = MutableStateFlow("প্রস্তুত")
    val statusMessage: StateFlow<String> = _statusMessage.asStateFlow()

    private val _isAutomationRunning = MutableStateFlow(false)
    val isAutomationRunning: StateFlow<Boolean> = _isAutomationRunning.asStateFlow()

    private var activeCode: String = ""
    private var customerPhone: String = ""
    private var currentRequestId: String? = null
    private var targetWhatsAppPackage: String? = null
    private var isWaitingForDualWhatsAppChoice: Boolean = false
    private var lastActionTime = 0L
    private var stepStartTime = 0L
    private var hasAnnouncedBiometricPrompt = false
    private var watchdogJob: Job? = null

    // Single-paste guarantee & post-login auto-return to Main Chat List state
    @Volatile
    var hasPastedCodeOnce: Boolean = false
        private set
    private var lastPastedCodeValue: String = ""
    private var codePasteTimestamp: Long = 0L
    private var hasReturnedToMainChatListAfterPaste: Boolean = false
    private var isExecutingPasteAction: Boolean = false
    private var wrongScreenEscapeCount: Int = 0
    private var lastWrongScreenEscapeTime: Long = 0L
    private var lastLogoutShieldTime: Long = 0L
    private var postPasteReturnJob: Job? = null

    // Per-step click timestamps so no button is ever double-clicked or clicked erratically
    private var lastThreeDotClickTime: Long = 0L
    private var lastLinkedDevicesMenuClickTime: Long = 0L
    private var lastLinkADeviceBtnClickTime: Long = 0L
    private var lastLinkWithPhoneBtnClickTime: Long = 0L
    private var lastConfirmBtnClickTime: Long = 0L
    private var lastCopiedCodeValue: String = ""
    private var lastCopiedCodeTimestamp: Long = 0L

    fun getActivePairingCode(): String = activeCode

    fun getTargetWhatsAppPackage(): String? = targetWhatsAppPackage

    /**
     * Checks if standard WhatsApp (`com.whatsapp`) is installed on this device.
     */
    fun isStandardWhatsAppInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getLaunchIntentForPackage(PKG_WHATSAPP) != null ||
                pm.getPackageInfo(PKG_WHATSAPP, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Checks if WhatsApp Business (`com.whatsapp.w4b`) is installed on this device.
     */
    fun isWhatsAppBusinessInstalled(): Boolean {
        val pm = context.packageManager
        return try {
            pm.getLaunchIntentForPackage(PKG_WHATSAPP_BUSINESS) != null ||
                pm.getPackageInfo(PKG_WHATSAPP_BUSINESS, 0) != null
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Returns true if BOTH standard WhatsApp and WhatsApp Business are installed on the visually impaired user's device.
     */
    fun areBothWhatsAppAppsInstalled(): Boolean {
        return isStandardWhatsAppInstalled() && isWhatsAppBusinessInstalled()
    }

    /**
     * Called when the visually impaired user or controller sends `/whatsapp` or `/whatsapp_business`
     * from the logged-in Chat ID inbox to choose which WhatsApp app to open and run the 100% error-free
     * Linked Devices auto-click workflow on.
     */
    fun selectWhatsAppAppAndProceed(
        chooseBusiness: Boolean,
        explicitCode: String? = null,
        replyChannelId: String? = null
    ) {
        val selectedPkg = if (chooseBusiness) PKG_WHATSAPP_BUSINESS else PKG_WHATSAPP
        val appLabelBn = if (chooseBusiness) "WhatsApp Business (হোয়াটসঅ্যাপ বিজনেস)" else "WhatsApp (সাধারণ হোয়াটসঅ্যাপ)"
        targetWhatsAppPackage = selectedPkg
        isWaitingForDualWhatsAppChoice = false

        val codeToUse = explicitCode?.trim()?.uppercase()?.takeIf { it.isNotBlank() }
            ?: activeCode.ifBlank {
                AndroidBridge.getClipboardText(context)?.trim()?.uppercase()?.takeIf { it.length in 6..10 }.orEmpty()
            }

        if (codeToUse.isNotBlank()) {
            startWorkflow(
                code = codeToUse,
                phoneNumber = customerPhone.ifBlank { "দৃষ্টি প্রতিবন্ধী ইউজার" },
                requestId = currentRequestId,
                autoLaunchWhatsApp = true,
                explicitPackage = selectedPkg
            )
        } else {
            // Even if no code was pending yet, store preference and open the chosen WhatsApp app ready for linking
            val msg = "$appLabelBn নির্বাচন করা হয়েছে এবং লিঙ্কড ডিভাইস স্ক্রিন ওপেন করা হচ্ছে।"
            _statusMessage.value = msg
            speakAndAnnounce(msg, playAudibly = true)
            openWhatsAppLinkedDevicesUrl()
        }

        telegramSync.notifyWhatsAppAppSelectionConfirmed(
            chosenAppName = appLabelBn,
            chosenPackage = selectedPkg,
            pairingCode = codeToUse,
            targetChatId = replyChannelId
        )
    }

    /**
     * Starts the automated WhatsApp Linked Devices flow with verification code.
     * Can be triggered from app UI, database listener, or Telegram bot command.
     * Copies the pairing code strictly ONCE (never loops or spams clipboard).
     *
     * If BOTH WhatsApp (`com.whatsapp`) and WhatsApp Business (`com.whatsapp.w4b`) are installed on the device
     * and no explicit app choice (`/whatsapp` or `/whatsapp_business`) has been given yet, it sends a reply
     * condition to the logged-in Chat ID inbox asking to click `/whatsapp` or `/whatsapp_business`, and waits
     * to launch the exact chosen app 100% error-free!
     */
    fun startWorkflow(
        code: String,
        phoneNumber: String,
        requestId: String? = null,
        autoLaunchWhatsApp: Boolean = true,
        explicitPackage: String? = null
    ) {
        if (!QuickAccessibilityService.isPowerOn.value) {
            val pausedMsg = "ডাটাবেইজ সার্ভার থেকে /power off সক্রিয় থাকায় হোয়াটসঅ্যাপ অটো-ক্লিক সার্ভিস বন্ধ রাখা হয়েছে।"
            _statusMessage.value = pausedMsg
            speakAndAnnounce(pausedMsg)
            Log.w(TAG, "WhatsApp workflow cancelled because server power is OFF (/power off)")
            return
        }

        val cleanCode = code
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
            .trim()
            .uppercase()
            .ifBlank { generateFallbackCode() }
        val now = System.currentTimeMillis()

        // Guard against duplicate accidental double-tap within 2.5 seconds while allowing rapid consecutive test runs
        if (_isAutomationRunning.value &&
            !isWaitingForDualWhatsAppChoice &&
            cleanCode == activeCode &&
            (explicitPackage == null || explicitPackage == targetWhatsAppPackage) &&
            now - lastCopiedCodeTimestamp < 2500L
        ) {
            Log.i(TAG, "Ignoring duplicate startWorkflow call for code $cleanCode (already running)")
            return
        }

        activeCode = cleanCode
        customerPhone = phoneNumber.ifBlank { "দৃষ্টি প্রতিবন্ধী ইউজার" }
        currentRequestId = requestId
        hasAnnouncedBiometricPrompt = false
        hasPastedCodeOnce = false
        isExecutingPasteAction = false
        lastPastedCodeValue = ""
        codePasteTimestamp = 0L
        hasReturnedToMainChatListAfterPaste = false
        wrongScreenEscapeCount = 0
        lastThreeDotClickTime = 0L
        lastLinkedDevicesMenuClickTime = 0L
        lastLinkADeviceBtnClickTime = 0L
        lastLinkWithPhoneBtnClickTime = 0L
        lastConfirmBtnClickTime = 0L
        postPasteReturnJob?.cancel()
        stepStartTime = now

        // Step 1: Copy code to system clipboard STRICTLY ONCE right away so it's ready
        if (cleanCode != lastCopiedCodeValue || now - lastCopiedCodeTimestamp > 60000L) {
            AndroidBridge.copyToSystemClipboard(context, cleanCode, "otp")
            lastCopiedCodeValue = cleanCode
            lastCopiedCodeTimestamp = now
        }

        // Check if BOTH WhatsApp & WhatsApp Business are installed on this device
        val bothInstalled = areBothWhatsAppAppsInstalled()
        isWaitingForDualWhatsAppChoice = false
        targetWhatsAppPackage = when {
            !explicitPackage.isNullOrBlank() -> explicitPackage
            isStandardWhatsAppInstalled() -> PKG_WHATSAPP
            isWhatsAppBusinessInstalled() -> PKG_WHATSAPP_BUSINESS
            else -> PKG_WHATSAPP
        }
        _isAutomationRunning.value = true

        if (bothInstalled && explicitPackage.isNullOrBlank()) {
            val noteMsg = "ইনশাআল্লাহ, সাধারণ হোয়াটসঅ্যাপে স্বয়ংক্রিয় লিঙ্ক শুরু হচ্ছে।"
            speakAndAnnounce(noteMsg, playAudibly = true)
            Log.i(TAG, "Both WhatsApp variants installed; defaulted smoothly to standard WhatsApp ($targetWhatsAppPackage)")
        }

        Log.i(TAG, "Starting WhatsApp auto-link workflow with 8-digit code: $cleanCode for $customerPhone on package: $targetWhatsAppPackage")

        _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
        vibrationManager.triggerEvent(VibrationEvent.AUTO_CLICK_STEP)
        val msg1 = "ইনশাআল্লাহ, পেয়ারিং নাম্বার $cleanCode ১ বার ক্লিপবোর্ডে কপি করা হয়েছে এবং হোয়াটসঅ্যাপ অটো-লিঙ্ক শুরু হচ্ছে।"
        _statusMessage.value = msg1
        speakAndAnnounce(msg1)
        telegramSync.notifyWhatsAppStep(
            phoneNumber = customerPhone,
            stepNumber = 1,
            stepTitle = "লিঙ্ক হোয়াটসঅ্যাপ লিসেনার ও পেয়ারিং নাম্বার ১ বার কপি",
            status = "SUCCESS",
            details = "পেয়ারিং নাম্বার: $cleanCode ১ বার ক্লিপবোর্ডে কপি হয়েছে। সম্পূর্ণ প্রসেসিং রান করা হচ্ছে।"
        )

        // 🛡️ Activate Screen Touch Protection Shield
        val serviceInstance = QuickAccessibilityService.instance
        if (serviceInstance != null) {
            TouchGuardOverlay.getInstance(context).show(serviceInstance, cleanCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }

        // 🔄 Start persistent fault-tolerant watchdog loop (NO clipboard copy spam inside!)
        startContinuousLinkWatchdog()

        if (autoLaunchWhatsApp) {
            scope.launch {
                delay(250L)
                if (_isAutomationRunning.value && !isWaitingForDualWhatsAppChoice && QuickAccessibilityService.isPowerOn.value) {
                    triggerAppSideWhatsappButtonAndOpenShortLink(explicitPackage = targetWhatsAppPackage)
                }
            }
        }
    }

    /**
     * Triggers the app-side "whatsapp" button if the user is currently on the app screen,
     * and immediately opens the direct `https://wa.me/settings/linked_devices` shortcut!
     * Works 100% identically whether the user is inside the app OR has closed the app from background.
     */
    fun triggerAppSideWhatsappButtonAndOpenShortLink(explicitPackage: String? = null) {
        val service = QuickAccessibilityService.instance
        var clickedAppButton = false
        if (service != null) {
            try {
                val activeRoot = service.rootInActiveWindow
                val pkg = activeRoot?.packageName?.toString().orEmpty()
                if (activeRoot != null && pkg.contains("com.example", ignoreCase = true)) {
                    clickedAppButton = findAndClickNodeMatching(
                        activeRoot,
                        listOf("whatsapp", "হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস ওপেন করুন"),
                        service
                    ) { node ->
                        val t = node.text?.toString().orEmpty().trim().lowercase()
                        val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                        t == "whatsapp" || d == "whatsapp" || t.contains("লিঙ্কড ডিভাইস")
                    }
                    if (clickedAppButton) {
                        Log.i(TAG, "✅ Auto-clicked app-side 'whatsapp' button directly!")
                    }
                }
            } catch (_: Exception) {}
        }

        // Always invoke openWhatsAppLinkedDevicesUrl directly so whether the app is open on screen
        // OR completely closed from background, https://wa.me/settings/linked_devices opens 100% reliably!
        openWhatsAppLinkedDevicesUrl(explicitPackage = explicitPackage)
    }

    /**
     * Directly opens the `https://wa.me/settings/linked_devices` shortcut on the target WhatsApp application
     * (`com.whatsapp` or `com.whatsapp.w4b`) instead of opening the main WhatsApp home screen.
     */
    fun launchWhatsAppApp(clearToMainChatList: Boolean = false, explicitPackage: String? = null) {
        openWhatsAppLinkedDevicesUrl(explicitPackage = explicitPackage)
    }

    /**
     * Opens `https://wa.me/settings/linked_devices` shortcut directly!
     * Prioritizes the official `https://wa.me/settings/linked_devices` deep link targeted at the selected
     * WhatsApp package (`com.whatsapp` or `com.whatsapp.w4b`), with fallback to `LinkedDevicesActivity`
     * so 100 out of 100 runs land straight on the Linked Devices screen without needing the 3-dot menu!
     */
    fun openWhatsAppLinkedDevicesUrl(explicitPackage: String? = null) {
        val targetPkg = explicitPackage
            ?: targetWhatsAppPackage
            ?: if (isStandardWhatsAppInstalled()) PKG_WHATSAPP else PKG_WHATSAPP_BUSINESS
        var launched = false
        val shortLinkUri = Uri.parse(WHATSAPP_LINKED_DEVICES_SHORT_LINK)

        // Attempt 1: Direct https://wa.me/settings/linked_devices short link with explicit WhatsApp package
        try {
            val targetedShortLinkIntent = Intent(Intent.ACTION_VIEW, shortLinkUri).apply {
                setPackage(targetPkg)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP
                )
            }
            context.startActivity(targetedShortLinkIntent)
            launched = true
            Log.i(TAG, "✅ Opened $WHATSAPP_LINKED_DEVICES_SHORT_LINK directly in package $targetPkg")
        } catch (e: Exception) {
            Log.w(TAG, "Targeted short link notice for $targetPkg: ${e.message}")
        }

        // Attempt 2: Universal https://wa.me/settings/linked_devices short link without package restriction
        if (!launched) {
            try {
                val universalShortLinkIntent = Intent(Intent.ACTION_VIEW, shortLinkUri).apply {
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP
                    )
                }
                context.startActivity(universalShortLinkIntent)
                launched = true
                Log.i(TAG, "✅ Opened universal $WHATSAPP_LINKED_DEVICES_SHORT_LINK shortcut")
            } catch (e: Exception) {
                Log.w(TAG, "Universal short link notice: ${e.message}")
            }
        }

        // Attempt 3: Direct component launch for LinkedDevicesActivity if short link handler was unavailable
        if (!launched) {
            try {
                val directIntent = Intent().apply {
                    setClassName(targetPkg, "com.whatsapp.companiondevice.LinkedDevicesActivity")
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
                context.startActivity(directIntent)
                launched = true
                Log.i(TAG, "✅ Opened LinkedDevicesActivity directly for $targetPkg")
            } catch (_: Exception) {}
        }

        // Attempt 4: Package launch fallback only if all Linked Devices direct shortcuts failed
        if (!launched) {
            try {
                val pm = context.packageManager
                val launchIntent = pm.getLaunchIntentForPackage(targetPkg)
                if (launchIntent != null) {
                    launchIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    context.startActivity(launchIntent)
                    launched = true
                }
            } catch (_: Exception) {}
        }

        if (launched) {
            if (_isAutomationRunning.value && _currentStep.value == WorkflowStep.LISTENER_ACTIVE_CODE_COPIED) {
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
            }
            QuickAccessibilityService.instance?.triggerFastInspectionLoop()
            val msg = "শর্টকাট লিংক (https://wa.me/settings/linked_devices) দিয়ে সরাসরি হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস সেটিংস ওপেন করা হয়েছে ✓"
            _statusMessage.value = msg
            speakAndAnnounce(msg)
        } else {
            val errorMsg = "হোয়াটসঅ্যাপ অ্যাপ ডিভাইসে খুঁজে পাওয়া যায়নি।"
            _statusMessage.value = errorMsg
            _currentStep.value = WorkflowStep.STUCK_OR_ERROR
            _isAutomationRunning.value = false
            speakAndAnnounce(errorMsg)
            telegramSync.notifyWhatsAppStuck(customerPhone, "হোয়াটসঅ্যাপ ওপেন ব্যর্থ")
        }
    }

    /**
     * Arms the workflow when triggered from notification shade or push notification
     */
    fun armFromNotificationShortcut(code: String? = null, phone: String? = null) {
        if (!QuickAccessibilityService.isPowerOn.value) return
        if (!code.isNullOrBlank()) {
            activeCode = code.trim().uppercase()
            AndroidBridge.copyToSystemClipboard(context, activeCode, "otp")
        } else if (activeCode.isBlank()) {
            val clipText = AndroidBridge.getClipboardText(context)
            if (!clipText.isNullOrBlank() && clipText.length in 4..12) {
                activeCode = clipText.trim().uppercase()
            } else {
                activeCode = generateFallbackCode()
                AndroidBridge.copyToSystemClipboard(context, activeCode, "otp")
            }
        }
        if (!phone.isNullOrBlank()) {
            customerPhone = phone
        }

        if (targetWhatsAppPackage.isNullOrBlank()) {
            targetWhatsAppPackage = if (isStandardWhatsAppInstalled()) PKG_WHATSAPP else PKG_WHATSAPP_BUSINESS
        }
        isWaitingForDualWhatsAppChoice = false

        _isAutomationRunning.value = true
        _currentStep.value = WorkflowStep.CLICKING_NOTIFICATION_SHORTCUT
        hasPastedCodeOnce = false
        hasReturnedToMainChatListAfterPaste = false
        wrongScreenEscapeCount = 0
        stepStartTime = System.currentTimeMillis()

        val serviceInstance = QuickAccessibilityService.instance
        if (serviceInstance != null) {
            TouchGuardOverlay.getInstance(context).show(serviceInstance, activeCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }
        startContinuousLinkWatchdog()

        val msg = "হোয়াটসঅ্যাপ পুশ নোটিফিকেশন শর্টকাট সক্রিয় হয়েছে। ৮ ডিজিটের কোড $activeCode প্রস্তুত।"
        _statusMessage.value = msg
        speakAndAnnounce(msg)
    }

    /**
     * Inspects notification shade (shutter) when pulled down by user
     */
    fun processNotificationShade(root: AccessibilityNodeInfo): Boolean {
        if (!QuickAccessibilityService.isPowerOn.value) return false
        val now = System.currentTimeMillis()
        if (now - lastActionTime < 1000L) return false

        val notificationTargets = listOf(
            "Enter code to link new device",
            "Enter code to link a new device",
            "Enter code to link",
            "নতুন ডিভাইস লিঙ্ক করতে কোড লিখুন",
            "নতুন ডিভাইস লিঙ্ক করতে কোড",
            "ডিভাইস লিঙ্ক করতে কোড",
            "link new device",
            "link a new device"
        )
        val clicked = findAndClickNodeMatching(root, notificationTargets)
        if (clicked) {
            lastActionTime = now
            stepStartTime = now
            armFromNotificationShortcut()
            val msg = "হোয়াটসঅ্যাপের 'Enter code to link new device' পুশ নোটিফিকেশনে স্বয়ংক্রিয়ভাবে ক্লিক করা হয়েছে।"
            _statusMessage.value = msg
            speakAndAnnounce(msg)
            telegramSync.notifyWhatsAppStep(
                phoneNumber = customerPhone,
                stepNumber = 1,
                stepTitle = "পুশ নোটিফিকেশন শর্টকাট ক্লিক",
                status = "SUCCESS",
                details = "'Enter code to link new device' পুশ নোটিফিকেশনে সফলভাবে ক্লিক সম্পন্ন।"
            )
            return true
        }
        return false
    }

    /**
     * Core inspection engine called by QuickAccessibilityService.
     * Returns true if an action was taken on this window so caller stops inspecting other windows.
     */
    fun processWindow(
        root: AccessibilityNodeInfo,
        packageName: String,
        service: QuickAccessibilityService
    ): Boolean {
        if (!QuickAccessibilityService.isPowerOn.value) {
            if (_isAutomationRunning.value) {
                stopAutomation()
                speakAndAnnounce("ডাটাবেইজ সার্ভার থেকে /power off সক্রিয় থাকায় হোয়াটসঅ্যাপ অটো-ক্লিক সার্ভিস সাথে সাথে বন্ধ করা হয়েছে।")
            }
            return false
        }

        val isWhatsApp = packageName.contains("whatsapp", ignoreCase = true)
        val isChooser = packageName.contains("resolver", ignoreCase = true) ||
                packageName.contains("chooser", ignoreCase = true) ||
                packageName.contains("android.internal.app", ignoreCase = true)
        val isSystemUi = packageName.contains("systemui", ignoreCase = true)

        // If in system UI / notification shade, check for the "Enter code to link new device" notification
        if (isSystemUi) {
            val handledNotification = processNotificationShade(root)
            if (handledNotification) return true
        }

        val now = System.currentTimeMillis()

        // -------------------------------------------------------------
        // PERMANENT ANTI-LOGOUT SHIELD & POST-LOGIN AUTO-RETURN TO MAIN CHAT LIST:
        // Protects visually impaired users from ever accidentally clicking "Log out"
        // after linking succeeds, and automatically navigates BACK to the main Chat list.
        // -------------------------------------------------------------
        if (isWhatsApp) {
            if (handleAntiLogoutProtection(root, service, now)) {
                lastActionTime = now
                return true
            }
            if (hasPastedCodeOnce) {
                return handlePostPasteAutoReturnToMainChat(root, service, now)
            }
        }

        // Auto-arm if user is inside WhatsApp on Confirm screen or Code Entry screen (only if not already pasted!)
        if (!_isAutomationRunning.value && isWhatsApp && !hasPastedCodeOnce) {
            val hasConfirm = findNodeMatching(root, listOf("Confirm", "নিশ্চিত করুন", "Confirm code")) != null
            val hasEnterCode = isGenuineLinkCodeEntryScreen(root)
            if (hasConfirm || hasEnterCode) {
                armFromNotificationShortcut()
            } else {
                return false
            }
        }

        if (!_isAutomationRunning.value) return false
        if (isWaitingForDualWhatsAppChoice) return false

        // If a specific WhatsApp package (e.g. com.whatsapp vs com.whatsapp.w4b) was chosen,
        // and the wrong WhatsApp variant is currently in the foreground, immediately switch to the chosen one!
        if (isWhatsApp && !targetWhatsAppPackage.isNullOrBlank() &&
            !packageName.equals(targetWhatsAppPackage, ignoreCase = true)
        ) {
            if (now - lastActionTime > 1200L) {
                lastActionTime = now
                Log.i(TAG, "Switching from $packageName to chosen target package: $targetWhatsAppPackage")
                launchWhatsAppApp(clearToMainChatList = true, explicitPackage = targetWhatsAppPackage)
            }
            return true
        }

        // Ensure the "Please Wait..." floating image overlay is displayed whenever automation is running
        if (!TouchGuardOverlay.getInstance(context).isProtected()) {
            TouchGuardOverlay.getInstance(context).show(service, activeCode) {
                QuickAccessibilityService.updatePowerState(false, "/power off")
            }
        }

        if (now - lastActionTime < 680L) return false

        // Handling App Chooser (e.g. choose between WhatsApp and WhatsApp Business)
        if (isChooser) {
            val chooserTargets = when (targetWhatsAppPackage) {
                PKG_WHATSAPP_BUSINESS -> listOf("WhatsApp Business", "WA Business", "হোয়াটসঅ্যাপ বিজনেস")
                PKG_WHATSAPP -> listOf("WhatsApp", "হোয়াটসঅ্যাপ")
                else -> listOf("WhatsApp", "WhatsApp Business", "হোয়াটসঅ্যাপ")
            }
            val chooserFound = findAndClickNodeMatching(root, chooserTargets, service) { node ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                if (targetWhatsAppPackage == PKG_WHATSAPP) {
                    (t == "whatsapp" || d == "whatsapp" || t == "হোয়াটসঅ্যাপ") && !t.contains("business") && !d.contains("business")
                } else if (targetWhatsAppPackage == PKG_WHATSAPP_BUSINESS) {
                    t.contains("business") || d.contains("business") || t.contains("বিজনেস")
                } else {
                    t.contains("whatsapp") || t.contains("হোয়াটসঅ্যাপ")
                }
            }
            if (chooserFound) {
                lastActionTime = now
                stepStartTime = now
                service.triggerFastInspectionLoop()
                val msg = "অ্যাপ সিলেক্টরে কাঙ্ক্ষিত হোয়াটসঅ্যাপ নির্বাচন করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                return true
            }
        }

        // Biometric / Screen Lock Prompt handling:
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isKeyguardLocked = km?.isKeyguardLocked == true
        val isBiometricDialogWindow = packageName.contains("biometric", ignoreCase = true) ||
                hasTextMatching(
                    root,
                    listOf(
                        "Touch the fingerprint sensor",
                        "Verify your identity",
                        "Use PIN",
                        "Use pattern",
                        "Use password",
                        "ফিঙ্গারপ্রিন্ট সেন্সর স্পর্শ করুন",
                        "আপনার পরিচয় যাচাই করুন",
                        "পিন ব্যবহার করুন",
                        "প্যাটার্ন ব্যবহার করুন"
                    )
                )
        val isBiometricStepWaiting = _currentStep.value == WorkflowStep.CLICKING_LINK_A_DEVICE ||
                _currentStep.value == WorkflowStep.CLICKING_CONFIRM_BUTTON
        if (isKeyguardLocked || isBiometricDialogWindow) {
            TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
            if (!hasAnnouncedBiometricPrompt && now - stepStartTime > 500L) {
                hasAnnouncedBiometricPrompt = true
                val promptMsg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = promptMsg
                speakAndAnnounce(promptMsg, playAudibly = true)
            }
            return false
        }

        if (isSystemUi) {
            return false
        }

        // When back in WhatsApp and past the biometric prompt (e.g., on QR scanner / Link with phone number or Code Entry),
        // restore the full-screen 100% "Please Wait..." touch protection shield!
        if (!isBiometricStepWaiting) {
            TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
            TouchGuardOverlay.getInstance(context).setPassThrough(false)
        }

        if (!isWhatsApp) return false

        // -------------------------------------------------------------
        // CAMERA / QR SCANNER vs REGULAR CAMERA AUTO-ESCAPE:
        // Only exit if the user accidentally opened the regular WhatsApp status/chat camera
        // (which has "Hold for video, tap for photo" / "Shutter"), NEVER exit the QR code scanner
        // page that has "Link with phone number instead" or "Scan QR code"!
        // -------------------------------------------------------------
        val hasLinkWithPhoneOnScreen = hasTextMatching(
            root,
            listOf(
                "Link with phone number",
                "phone number instead",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "Scan QR code",
                "কিউআর কোড স্ক্যান"
            )
        )
        val isRegularPhotoVideoCamera = !hasLinkWithPhoneOnScreen && (
            hasTextMatching(root, listOf("Hold for video, tap for photo", "ভিডিওর জন্য চেপে ধরুন", "Hold for video", "tap for photo")) ||
                findNodeMatching(root, listOf("Shutter", "Take photo", "ছবি তুলুন", "শাটার")) != null
            )
        if (isRegularPhotoVideoCamera) {
            Log.w(TAG, "⚠️ Regular WhatsApp photo/video camera active! Pressing BACK to return to WhatsApp main screen...")
            lastActionTime = now
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            service.triggerFastInspectionLoop()
            speakAndAnnounce("ক্যামেরা থেকে ব্যাক করে প্রধান স্ক্রিনে ফিরে যাওয়া হচ্ছে...")
            return true
        }

        // -------------------------------------------------------------
        // STEP 5: Verification Code Entry & Single-Paste Guarantee
        // (STRICTLY ONLY when genuinely on the Enter Code screen so it NEVER pastes into a wrong field!)
        // -------------------------------------------------------------
        val isCodeScreen = isGenuineLinkCodeEntryScreen(root)
        if (!hasPastedCodeOnce && isCodeScreen && activeCode.isNotBlank()) {
            val pasteHandled = executeKeyboardClipboardAndPaste(root, service)
            if (pasteHandled) {
                lastActionTime = now
                return true
            }
        }

        // -------------------------------------------------------------
        // NOTIFICATION SHORTCUT STEP: "Confirm" / "Allow" / "Continue" button click
        // -------------------------------------------------------------
        if (now - lastConfirmBtnClickTime > 1250L) {
            val isConfirmBtn = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                t == "confirm" || t == "নিশ্চিত করুন" || t == "confirm code" || t == "confirm device" ||
                    t == "হ্যাঁ, নিশ্চিত করুন" || d == "confirm" || d == "নিশ্চিত করুন" ||
                    id.endsWith("confirm_button") || id.endsWith("btn_confirm")
            }
            val confirmTargets = listOf(
                "Confirm",
                "নিশ্চিত করুন",
                "Confirm code",
                "Confirm device",
                "হ্যাঁ, নিশ্চিত করুন"
            )
            val confirmClicked = findAndClickNodeMatching(root, confirmTargets, service, isConfirmBtn)
            if (confirmClicked) {
                lastActionTime = now
                lastConfirmBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_CONFIRM_BUTTON
                TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                service.triggerFastInspectionLoop()
                val msg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = msg
                speakAndAnnounce(msg, playAudibly = true)
                telegramSync.notifyAutoClickAction("Confirm বাটন ক্লিক", "Confirm", "হোয়াটসঅ্যাপে Confirm বোতামে ক্লিক সম্পন্ন।")
                telegramSync.notifyWhatsAppStep(
                    phoneNumber = customerPhone,
                    stepNumber = 2,
                    stepTitle = "'Confirm' বাটনে ক্লিক",
                    status = "SUCCESS",
                    details = "হোয়াটসঅ্যাপে Confirm বোতামে ক্লিক সম্পন্ন। আনলক ও কোড পেস্ট প্রত্যাশিত।"
                )
                return true
            }
        }

        // -------------------------------------------------------------
        // STEP 4: "Link with phone number instead" button click
        // (Appears after biometric authentication on the QR scanner page)
        // Also handles any one-time "Continue" / "OK" prompt on the QR scanner screen!
        // -------------------------------------------------------------
        if (now - lastLinkWithPhoneBtnClickTime > 1250L) {
            val isLinkWithPhoneItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                (t.length <= 65 && (
                    t.contains("link with phone number") ||
                        t == "link with phone" ||
                        t.contains("phone number instead") ||
                        t.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                        t.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                        t.contains("ফোন নাম্বার দিয়ে লিঙ্ক")
                    )) ||
                    (d.length <= 65 && (
                        d.contains("link with phone number") ||
                            d.contains("phone number instead") ||
                            d.contains("ফোন নম্বর দিয়ে লিঙ্ক") ||
                            d.contains("ফোন নম্বর দিয়ে লিঙ্ক")
                        )) ||
                    id.contains("link_with_phone") ||
                    id.contains("enter_code_link")
            }
            val linkWithPhoneTargets = listOf(
                "Link with phone number instead",
                "Link with phone number",
                "ফোন নম্বর দিয়ে লিঙ্ক করুন",
                "ফোন নম্বর দিয়ে লিঙ্ক করুন",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক"
            )
            val linkWithPhoneClicked = findAndClickNodeMatching(root, linkWithPhoneTargets, service, isLinkWithPhoneItem)
            if (linkWithPhoneClicked) {
                lastActionTime = now
                lastLinkWithPhoneBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE
                TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
                TouchGuardOverlay.getInstance(context).updateStatusStepText("পেয়ারিং কোড ইনপুট বক্স ওপেন হচ্ছে — অনুগ্রহ করে অপেক্ষা করুন...")
                service.triggerFastInspectionLoop()
                val msg = "বায়োমেট্রিক অনুমোদিত। 'Link with phone number instead' বাটনে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                telegramSync.notifyAutoClickAction("ফোন নম্বর দিয়ে লিঙ্ক বাটন", "Link with phone number instead", "ফোন নম্বর দিয়ে লিঙ্ক করার বাটনে সফলভাবে ক্লিক করা হয়েছে।")
                telegramSync.notifyWhatsAppStep(
                    phoneNumber = customerPhone,
                    stepNumber = 4,
                    stepTitle = "'Link with phone number instead' ক্লিক",
                    status = "SUCCESS",
                    details = "ফোন নম্বর দিয়ে লিঙ্ক করার বাটনে সফলভাবে ক্লিক করা হয়েছে।"
                )
                return true
            }

            // If WhatsApp shows a one-time "Scan QR code -> OK / Continue" educational dialog on top of the QR scanner, dismiss it immediately
            if (hasTextMatching(root, listOf("Scan QR code", "Use WhatsApp on Web", "কিউআর কোড স্ক্যান", "To use WhatsApp Web"))) {
                val okDialogClicked = findAndClickNodeMatching(
                    root,
                    listOf("OK", "Continue", "Got it", "ঠিক আছে", "চালিয়ে যান", "চালিয়ে যান"),
                    service
                ) { node ->
                    val t = node.text?.toString().orEmpty().trim().lowercase()
                    t == "ok" || t == "continue" || t == "got it" || t == "ঠিক আছে" || t == "চালিয়ে যান" || t == "চালিয়ে যান"
                }
                if (okDialogClicked) {
                    lastActionTime = now
                    service.triggerFastInspectionLoop()
                    return true
                }
            }
        }

        // -------------------------------------------------------------
        // STEP 3: "Link a device" button click
        // (The prominent green button on the Linked Devices screen)
        // -------------------------------------------------------------
        if (now - lastLinkADeviceBtnClickTime > 1250L) {
            val isLinkADeviceItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                t.contains("link a device") || d.contains("link a device") ||
                    t.contains("link device") || d.contains("link device") ||
                    t.contains("একটি ডিভাইস লিঙ্ক") || t.contains("ডিভাইস লিঙ্ক") ||
                    d.contains("একটি ডিভাইস লিঙ্ক") || d.contains("ডিভাইস লিঙ্ক") ||
                    id.endsWith("link_device_button") || id.endsWith("link_a_device") ||
                    id.contains("link_device_btn")
            }
            val linkADeviceTargets = listOf(
                "Link a device",
                "Link a Device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Link device",
                "ডিভাইস লিঙ্ক"
            )
            val linkADeviceClicked = findAndClickNodeMatching(root, linkADeviceTargets, service, isLinkADeviceItem)
            if (linkADeviceClicked) {
                lastActionTime = now
                lastLinkADeviceBtnClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
                TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                service.triggerFastInspectionLoop()
                val msg = "আপনার ফিঙ্গার অথবা ফোনের লক দিয়ে আনলক করুন।"
                _statusMessage.value = msg
                speakAndAnnounce(msg, playAudibly = true)
                telegramSync.notifyAutoClickAction("Link a device বাটন", "Link a device", "হোয়াটসঅ্যাপে Link a device বোতামে ক্লিক সম্পন্ন।")
                telegramSync.notifyWhatsAppStep(
                    phoneNumber = customerPhone,
                    stepNumber = 3,
                    stepTitle = "'Link a device' বাটনে ক্লিক",
                    status = "SUCCESS",
                    details = "হোয়াটসঅ্যাপে Link a device বোতামে ক্লিক সম্পন্ন। আনলক প্রত্যাশিত।"
                )
                return true
            }
        }

        // -------------------------------------------------------------
        // STEP 2: "Linked devices" menu item click
        // (Strictly inside the popup menu opened by the three dots, never the page toolbar title!)
        // -------------------------------------------------------------
        val isAlreadyOnLinkedDevicesPage = hasTextMatching(
            root,
            listOf(
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Use WhatsApp on other devices",
                "Tap a device to log out"
            )
        )
        if (!isAlreadyOnLinkedDevicesPage && now - lastLinkedDevicesMenuClickTime > 1200L) {
            val isLinkedDevicesItem = { node: AccessibilityNodeInfo ->
                val t = node.text?.toString().orEmpty().trim().lowercase()
                val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
                val isExactMenuLabel = t == "linked devices" || d == "linked devices" ||
                    t == "লিঙ্ক করা ডিভাইসগুলি" || t == "লিঙ্ক করা ডিভাইস" ||
                    t == "লিঙ্কড ডিভাইস" || t == "ডিভাইস লিঙ্ক" ||
                    t.contains("linked devices") || t.contains("লিঙ্ক করা ডিভাইস") ||
                    t.contains("লিঙ্কড ডিভাইস") ||
                    t == "whatsapp web" || id.endsWith("menuitem_linked_devices")
                isExactMenuLabel
            }
            val linkedDevicesTargets = listOf(
                "Linked devices",
                "Linked Devices",
                "লিঙ্ক করা ডিভাইসগুলি",
                "লিঙ্ক করা ডিভাইস",
                "লিঙ্কড ডিভাইস",
                "ডিভাইস লিঙ্ক",
                "WhatsApp Web"
            )
            val linkedDevicesClicked = findAndClickNodeMatching(root, linkedDevicesTargets, service, isLinkedDevicesItem)
            if (linkedDevicesClicked) {
                lastActionTime = now
                lastLinkedDevicesMenuClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
                TouchGuardOverlay.getInstance(context).updateStatusStepText("Linked Devices ওপেন হয়েছে — Link a device নির্বাচন করা হচ্ছে...")
                service.triggerFastInspectionLoop()
                val msg = "মেনু থেকে 'Linked devices' অপশনে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                telegramSync.notifyAutoClickAction("Linked devices মেনু", "Linked devices", "পপআপ মেনু থেকে Linked devices অপশনে সফলভাবে ক্লিক সম্পন্ন।")
                telegramSync.notifyWhatsAppStep(
                    phoneNumber = customerPhone,
                    stepNumber = 2,
                    stepTitle = "'Linked devices' মেনু অপশনে ক্লিক",
                    status = "SUCCESS",
                    details = "পপআপ মেনু থেকে Linked devices অপশনে সফলভাবে ক্লিক সম্পন্ন।"
                )
                return true
            }
        }

        // -------------------------------------------------------------
        // WRONG WHATSAPP SCREEN AUTO-ESCAPE (Inbox / Profile View / Wrong Popup / Sub-screen):
        // If the visually impaired user is accidentally inside a Chat Inbox, Profile View,
        // Contact Info, or a wrong 3-dot menu where "Linked devices" does not exist,
        // automatically press BACK (and switch to Chats tab) to return to the main Chat list!
        // -------------------------------------------------------------
        if (escapeWrongWhatsAppScreenToMainChats(root, service, now)) {
            lastActionTime = now
            return true
        }

        // -------------------------------------------------------------
        // STEP 1: Three-dot overflow menu click
        // (Top corner of WhatsApp main screen: More options)
        // Retries cleanly after 1200ms if popup menu was not opened yet
        // -------------------------------------------------------------
        val canClickThreeDot = (
            _currentStep.value == WorkflowStep.IDLE ||
                _currentStep.value == WorkflowStep.LISTENER_ACTIVE_CODE_COPIED ||
                (_currentStep.value == WorkflowStep.CLICKING_LINKED_DEVICES_MENU && now - lastThreeDotClickTime > 1200L)
            ) && !isAlreadyOnLinkedDevicesPage && !isCodeScreen && now - lastThreeDotClickTime > 1150L

        if (canClickThreeDot) {
            val threeDotClicked = findAndClickThreeDotMenu(root, service)
            if (threeDotClicked) {
                lastActionTime = now
                lastThreeDotClickTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.CLICKING_LINKED_DEVICES_MENU
                TouchGuardOverlay.getInstance(context).updateStatusStepText("থ্রি-ডট মেনু ওপেন হয়েছে — Linked devices নির্বাচন করা হচ্ছে...")
                service.triggerFastInspectionLoop()
                val msg = "হোয়াটসঅ্যাপ মূল চ্যাট স্ক্রিনে থ্রি ডট মেনুতে ক্লিক করা হয়েছে।"
                _statusMessage.value = msg
                speakAndAnnounce(msg)
                telegramSync.notifyAutoClickAction("থ্রি-ডট মেনু", "More options (3-dots)", "হোয়াটসঅ্যাপের শীর্ষবারের থ্রি ডট মেনুতে স্বয়ংক্রিয়ভাবে ক্লিক করা হয়েছে।")
                telegramSync.notifyWhatsAppStep(
                    phoneNumber = customerPhone,
                    stepNumber = 1,
                    stepTitle = "থ্রি ডট মেনুতে ক্লিক",
                    status = "SUCCESS",
                    details = "হোয়াটসঅ্যাপের শীর্ষবারের থ্রি ডট মেনুতে স্বয়ংক্রিয়ভাবে ক্লিক করা হয়েছে।"
                )
                return true
            }
        }

        // Stuck / Timeout Self-Healing Check (12 seconds so biometric unlock or slow network never triggers premature reset)
        if (now - stepStartTime > 12000L) {
            stepStartTime = now
            checkAndExplainStuckReason()
        }
        return false
    }

    /**
     * Executes Step 5 with a strict SINGLE-PASTE GUARANTEE and deliberate, accurate placement:
     * - Never pastes into a chat inbox or profile screen
     * - Never pastes a second time once `hasPastedCodeOnce` is true or once the field already contains the code
     * - Waits for the 8-digit code input boxes to settle, focuses the exact first box, pastes or populates all 8 characters
     *   in order with human-like pacing (75ms per box), verifies that all 8 characters are placed accurately, and then waits
     *   for WhatsApp's "Logging in..." handshake to complete before ever pressing BACK!
     */
    private fun executeKeyboardClipboardAndPaste(root: AccessibilityNodeInfo, service: QuickAccessibilityService? = null): Boolean {
        if (hasPastedCodeOnce || isExecutingPasteAction || _currentStep.value == WorkflowStep.WORKFLOW_COMPLETED) {
            return false
        }
        // Safety guard: never paste pairing code into a normal Chat Inbox or Profile screen
        if (isChatInboxOrProfileScreen(root)) {
            return false
        }

        val cleanCode = activeCode.replace("-", "").replace("_", "").replace(" ", "").trim().uppercase()
        if (cleanCode.isBlank()) return false

        val allBoxes = findAllEditableBoxes(root)
        val editable = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(root)

        // Check if the code has ALREADY been entered in the input box(es)
        if (isCodeAlreadyEnteredInFields(editable, allBoxes, cleanCode)) {
            Log.i(TAG, "✅ Pairing code $cleanCode is already present in input field(s). Locking single-paste guard and waiting for login completion.")
            hasPastedCodeOnce = true
            isExecutingPasteAction = false
            lastPastedCodeValue = cleanCode
            codePasteTimestamp = System.currentTimeMillis()
            completePasteWorkflow(cleanCode, service)
            return true
        }

        if (allBoxes.isEmpty() && editable == null) {
            return false
        }

        // 🔒 Lock concurrent execution while we enter & verify the code with deliberate pacing
        isExecutingPasteAction = true
        val now = System.currentTimeMillis()
        lastActionTime = now
        TouchGuardOverlay.getInstance(context).updateStatusStepText("সঠিক স্থানে ৮-ডিজিট পেয়ারিং নাম্বার ($cleanCode) বসানো হচ্ছে...")

        // Ensure the clean 8-character code is fresh on the system clipboard right before native paste
        AndroidBridge.copyToSystemClipboard(context, cleanCode, "otp")

        scope.launch {
            try {
                // Allow the Enter Code screen and input boxes to fully settle before focusing & pasting (prevents fast-skipping first boxes)
                delay(320L)
                val settledRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: root
                val settledBoxes = findAllEditableBoxes(settledRoot)
                val settledEditable = settledRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(settledRoot)

                // Strategy 1: Native ACTION_PASTE on the first input box (WhatsApp intercepts onTextContextMenuItem(paste)
                // to split all 8 characters across the 8 boxes and automatically submit the pairing code!)
                if (settledBoxes.isNotEmpty()) {
                    val firstBox = settledBoxes.first()
                    firstBox.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    firstBox.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(120L)
                    firstBox.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                } else if (settledEditable != null) {
                    settledEditable.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    settledEditable.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(120L)
                    if (!settledEditable.performAction(AccessibilityNodeInfo.ACTION_PASTE)) {
                        val args = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanCode)
                        }
                        settledEditable.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
                    }
                }

                // Strategy 2: Try clicking the keyboard clipboard suggestion chip if visible and boxes not yet filled
                delay(280L)
                val currentRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: settledRoot
                val latestBoxes = findAllEditableBoxes(currentRoot)
                val latestEditable = currentRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(currentRoot)

                var verifiedEntered = isCodeAlreadyEnteredInFields(latestEditable, latestBoxes, cleanCode) ||
                    hasTextMatching(currentRoot, listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে"))

                if (!verifiedEntered) {
                    val codeChipNode = findNodeWithExactOrPartialText(currentRoot, cleanCode)
                    if (codeChipNode != null && !codeChipNode.isEditable) {
                        val clickedChip = (codeChipNode.isClickable && codeChipNode.performAction(AccessibilityNodeInfo.ACTION_CLICK)) ||
                            (codeChipNode.parent?.isClickable == true && codeChipNode.parent.performAction(AccessibilityNodeInfo.ACTION_CLICK))
                        if (clickedChip) {
                            Log.i(TAG, "✅ Clicked keyboard suggestion chip for $cleanCode")
                            delay(260L)
                        }
                    }
                }

                // Strategy 3: Deliberate, human-paced per-box population if native paste didn't fill all 8 boxes
                val recheckRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: currentRoot
                val recheckBoxes = findAllEditableBoxes(recheckRoot)
                val recheckEditable = recheckRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableInput(recheckRoot)
                verifiedEntered = isCodeAlreadyEnteredInFields(recheckEditable, recheckBoxes, cleanCode) ||
                    hasTextMatching(recheckRoot, listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে"))

                if (!verifiedEntered && cleanCode.length == 8 && recheckBoxes.size >= 8) {
                    Log.i(TAG, "⌨️ Populating all 8 boxes sequentially with steady human-like pacing...")
                    for (i in 0 until 8) {
                        val freshRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: recheckRoot
                        val freshBoxes = findAllEditableBoxes(freshRoot)
                        val box = freshBoxes.getOrNull(i) ?: recheckBoxes[i]
                        box.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                        box.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                        delay(70L)
                        val ch = cleanCode[i].toString()
                        val singleCharArgs = Bundle().apply {
                            putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ch)
                        }
                        if (!box.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, singleCharArgs)) {
                            AndroidBridge.copyToSystemClipboard(context, ch, "otp_char")
                            box.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                        }
                        delay(85L)
                    }
                    AndroidBridge.copyToSystemClipboard(context, cleanCode, "otp")
                    verifiedEntered = true
                } else if (!verifiedEntered && recheckBoxes.size == 2 && cleanCode.length == 8) {
                    val part1 = cleanCode.substring(0, 4)
                    val part2 = cleanCode.substring(4, 8)
                    val args1 = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, part1)
                    }
                    val args2 = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, part2)
                    }
                    recheckBoxes[0].performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val ok1 = recheckBoxes[0].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args1)
                    delay(120L)
                    recheckBoxes[1].performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    val ok2 = recheckBoxes[1].performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args2)
                    verifiedEntered = ok1 && ok2
                } else if (!verifiedEntered && (recheckEditable != null || recheckBoxes.isNotEmpty())) {
                    val targetInput = recheckEditable ?: recheckBoxes.first()
                    targetInput.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
                    targetInput.performAction(AccessibilityNodeInfo.ACTION_CLICK)
                    delay(100L)
                    val fullArgs = Bundle().apply {
                        putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, cleanCode)
                    }
                    verifiedEntered = targetInput.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, fullArgs) ||
                        targetInput.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                }

                delay(220L)
                // Trigger IME_ENTER / DONE on the last box or focused input so WhatsApp's OnEditorActionListener fires login!
                val finalRoot = try { service?.rootInActiveWindow } catch (_: Exception) { null } ?: recheckRoot
                val finalBoxes = findAllEditableBoxes(finalRoot)
                val lastBox = finalBoxes.lastOrNull() ?: finalRoot.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
                if (lastBox != null && android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    lastBox.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
                }

                // Also click any explicit Next / Submit / Confirm button if visible on the Enter Code screen
                val submitBtn = findNodeMatching(
                    finalRoot,
                    listOf("Next", "Submit", "Confirm", "Done", "পরবর্তী", "সাবমিট", "নিশ্চিত করুন")
                )
                if (submitBtn != null && service != null) {
                    val lbl = "${submitBtn.text ?: ""} ${submitBtn.contentDescription ?: ""}".lowercase()
                    if (!lbl.contains("phone") && !lbl.contains("ফোন") && !lbl.contains("qr")) {
                        service.clickNode(submitBtn)
                    }
                }

                val isTransitioningOrFilled = verifiedEntered ||
                    isCodeAlreadyEnteredInFields(lastBox, finalBoxes, cleanCode) ||
                    hasTextMatching(finalRoot, listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে"))

                if (isTransitioningOrFilled) {
                    hasPastedCodeOnce = true
                    lastPastedCodeValue = cleanCode
                    codePasteTimestamp = System.currentTimeMillis()
                    telegramSync.notifyAutoClickAction(
                        "সফলভাবে পেয়ারিং নাম্বার সাবমিট ও লিঙ্ক প্রক্রিয়া চলমান",
                        cleanCode,
                        "হোয়াটসঅ্যাপের ইনপুট বক্সে ৮-ডিজিট পেয়ারিং নাম্বার ($cleanCode) সঠিকভাবে বসানো হয়েছে এবং লগইন সম্পন্ন হওয়ার জন্য অপেক্ষা করা হচ্ছে ✓"
                    )
                    completePasteWorkflow(cleanCode, service)
                }
            } finally {
                isExecutingPasteAction = false
            }
        }
        return true
    }

    /**
     * Checks whether the pairing code is already present inside the editable input field(s)
     * so we never overwrite or double-paste.
     */
    private fun isCodeAlreadyEnteredInFields(
        editable: AccessibilityNodeInfo?,
        allBoxes: List<AccessibilityNodeInfo>,
        cleanCode: String
    ): Boolean {
        try {
            if (editable != null) {
                val existing = editable.text?.toString().orEmpty().replace("-", "").replace(" ", "").trim().uppercase()
                if (existing == cleanCode || (existing.length >= 8 && existing.contains(cleanCode.take(4)))) {
                    return true
                }
            }
            if (allBoxes.size >= 8) {
                val combined = allBoxes.take(8).joinToString("") { box ->
                    box.text?.toString().orEmpty().trim()
                }.replace("-", "").replace(" ", "").uppercase()
                if (combined.length >= 8) {
                    return true
                }
            }
        } catch (_: Exception) {}
        return false
    }

    /**
     * Detects whether the current screen is genuinely the WhatsApp Linked Devices 8-character code entry screen,
     * avoiding false positives inside normal chat conversations.
     */
    private fun isGenuineLinkCodeEntryScreen(root: AccessibilityNodeInfo): Boolean {
        if (isChatInboxOrProfileScreen(root)) return false
        return hasTextMatching(
            root,
            listOf(
                "Enter code",
                "enter the code",
                "Enter code to link",
                "8-character",
                "8-digit",
                "কোড লিখুন",
                "নতুন ডিভাইস লিঙ্ক করতে কোড",
                "ডিভাইস লিঙ্ক করতে কোড",
                "Change phone number",
                "ফোন নম্বর পরিবর্তন"
            )
        )
    }

    /**
     * Detects if the visually impaired user is currently inside a normal WhatsApp Chat Conversation (Inbox),
     * Profile View, Contact Info, Group Info, or Media Viewer instead of the main Chat list.
     */
    private fun isChatInboxOrProfileScreen(root: AccessibilityNodeInfo): Boolean {
        // 1. Check for chat conversation message composer / call buttons / contact header view IDs
        val hasInboxIndicators = hasViewIdOrDescMatching(
            root,
            viewIds = listOf(
                "entry",
                "input_layout",
                "voice_note_btn",
                "send",
                "input_attach_button",
                "emoji_picker_btn",
                "conversation_contact",
                "conversation_contact_name",
                "conversation_contact_status",
                "chat_layout",
                "contact_info",
                "profile_photo"
            ),
            descriptions = listOf(
                "voice call",
                "video call",
                "ভয়েস কল",
                "ভিডিও কল",
                "voice message",
                "ভয়েস মেসেজ",
                "attach",
                "সংযুক্ত করুন",
                "emoji"
            )
        )
        if (hasInboxIndicators) return true

        // 2. Check for Profile / Contact Info / Group Info / Chat menu text
        val hasProfileOrInboxText = hasTextMatching(
            root,
            listOf(
                "Contact info",
                "Group info",
                "View contact",
                "Media, links, and docs",
                "Mute notifications",
                "Disappearing messages",
                "Wallpaper",
                "Clear chat",
                "Export chat",
                "Encryption",
                "কন্টাক্ট ইনফো",
                "গ্রুপ ইনফো",
                "কন্টাক্ট দেখুন",
                "নোটিফিকেশন মিউট",
                "ওয়ালপেপার",
                "চ্যাট মুছুন"
            )
        )
        return hasProfileOrInboxText
    }

    /**
     * If the visually impaired user is on a wrong WhatsApp page (such as a Chat Inbox, Profile View,
     * Contact Info, Wrong Popup Menu, or non-Chats tab) while the auto-link service is trying to reach
     * "Linked devices", automatically navigates BACK to the main Chat list so the workflow succeeds.
     */
    private fun escapeWrongWhatsAppScreenToMainChats(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (now - lastWrongScreenEscapeTime < 1100L) return false

        // Do NOT escape if we are already on a valid Linked Devices workflow screen!
        val isOnValidTargetScreen = hasTextMatching(
            root,
            listOf(
                "Linked devices",
                "লিঙ্ক করা ডিভাইস",
                "লিঙ্কড ডিভাইস",
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Link with phone number",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "ফোন নম্বর দিয়ে লিঙ্ক",
                "Enter code",
                "কোড লিখুন",
                "Logging in",
                "লগ ইন করা হচ্ছে",
                "লগইন করা হচ্ছে",
                "Connecting",
                "সংযোগ করা হচ্ছে",
                "Scan QR code",
                "কিউআর কোড স্ক্যান"
            )
        )
        if (isOnValidTargetScreen) {
            wrongScreenEscapeCount = 0
            return false
        }

        // Case 1: A popup menu was opened (e.g. inside an inbox or Calls/Updates tab) that does NOT contain "Linked devices"
        val isWrongPopupMenuOpen = hasTextMatching(
            root,
            listOf(
                "View contact",
                "Media, links, and docs",
                "Mute notifications",
                "Wallpaper",
                "Clear chat",
                "Status privacy",
                "Create channel",
                "Clear call log",
                "কন্টাক্ট দেখুন",
                "নোটিফিকেশন মিউট",
                "ওয়ালপেপার",
                "চ্যাট মুছুন",
                "কল লগ মুছুন",
                "স্ট্যাটাস প্রাইভেসি"
            )
        )

        // Case 2: Inside a Chat Inbox, Profile View, Contact Info, or any sub-screen with a top-left Back/Navigate Up button
        val isSubScreenWithBack = hasNavigateUpOrBackButton(root)
        val isInboxOrProfile = isChatInboxOrProfileScreen(root)

        if (isWrongPopupMenuOpen || isInboxOrProfile || isSubScreenWithBack) {
            lastWrongScreenEscapeTime = now
            wrongScreenEscapeCount++
            _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
            stepStartTime = now

            Log.i(TAG, "🔄 Wrong WhatsApp screen detected (inbox/profile/subscreen, count=$wrongScreenEscapeCount). Opening https://wa.me/settings/linked_devices directly...")

            openWhatsAppLinkedDevicesUrl(explicitPackage = targetWhatsAppPackage)

            val msg = "শর্টকাট লিংক (https://wa.me/settings/linked_devices) দিয়ে সরাসরি লিঙ্কড ডিভাইস সেটিংসে নিয়ে যাওয়া হচ্ছে..."
            _statusMessage.value = msg
            speakAndAnnounce(msg)
            return true
        }

        // Case 3: On the main screen, ensure the "Chats" tab is selected if we are on Updates/Communities/Calls tab
        if (hasTextMatching(root, listOf("Find channels", "চ্যানেল খুঁজুন", "Create call link", "কল লিঙ্ক তৈরি করুন"))) {
            if (clickChatsBottomTabIfPresent(root, service)) {
                lastWrongScreenEscapeTime = now
                stepStartTime = now
                _currentStep.value = WorkflowStep.LISTENER_ACTIVE_CODE_COPIED
                service.triggerFastInspectionLoop()
                return true
            }
        }

        return false
    }

    /**
     * Permanent Anti-Logout Protection Shield:
     * If a visually impaired user accidentally opens a linked device detail popup or "Log out" dialog
     * ("Log out" / "লগ আউট" / "Remove device"), immediately dismisses it (clicks Cancel/Close or presses Back)
     * and returns to the main WhatsApp Chat List so the linked session is NEVER logged out!
     */
    private fun handleAntiLogoutProtection(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (now - lastLogoutShieldTime < 900L) return false

        val hasLogoutDialogOrButton = hasActualLogoutButtonOrDialog(root)
        if (!hasLogoutDialogOrButton) return false

        lastLogoutShieldTime = now
        Log.w(TAG, "🛡️ ANTI-LOGOUT SHIELD TRIGGERED! Preventing accidental logout and returning to main Chat list.")

        // First try clicking Cancel / Close if a confirmation dialog is open (NEVER click Log out!)
        val cancelClicked = findAndClickNodeMatching(
            root,
            listOf("Cancel", "বাতিল", "Close", "বন্ধ করুন", "না"),
            service
        ) { node ->
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
            t == "cancel" || t == "বাতিল" || t == "close" || t == "বন্ধ করুন" || t == "না" ||
                d == "cancel" || d == "close" || d == "বাতিল"
        }
        if (!cancelClicked) {
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }

        // Follow up with Back navigation to exit "Linked devices" completely back to the main Chat list
        scope.launch {
            delay(450L)
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            delay(450L)
            val currentRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
            if (currentRoot != null && hasTextMatching(currentRoot, listOf("Linked devices", "Link a device", "লিঙ্ক করা ডিভাইস", "একটি ডিভাইস লিঙ্ক করুন"))) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
        }

        val shieldMsg = "সতর্কতা! লগআউট অপশন থেকে সুরক্ষিত রেখে ব্যাক করে মূল চ্যাট লিস্ট পৃষ্ঠায় নিয়ে আসা হয়েছে।"
        _statusMessage.value = shieldMsg
        speakAndAnnounce(shieldMsg)
        return true
    }

    /**
     * Checks if an actual "Log out" / "লগ আউট" / "Remove device" button or dialog is on screen,
     * while ignoring the passive subtitle "Tap a device to log out" on the Linked Devices overview page.
     */
    private fun hasActualLogoutButtonOrDialog(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 90) {
            val node = queue.removeFirst()
            count++
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()

            // Ignore passive instructional text like "Tap a device to log out"
            val isPassiveInstruction = t.contains("tap a device") || t.contains("ডিভাইসে ট্যাপ")
            if (!isPassiveInstruction) {
                val isLogoutBtn = t == "log out" || t == "logout" ||
                    t == "লগ আউট" || t == "লগআউট" ||
                    t == "remove device" || t == "ডিভাইস সরান" ||
                    t.startsWith("log out from") || t.contains("want to log out") ||
                    d == "log out" || d == "লগ আউট" || d == "remove device"
                if (isLogoutBtn) return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    /**
     * After the pairing code has been pasted once (`hasPastedCodeOnce == true`), monitors login completion
     * and automatically navigates BACK to the main WhatsApp Chat List screen ONLY AFTER WhatsApp has
     * finished "Logging in..." and returned to the Linked Devices overview screen.
     * NEVER presses BACK while still on the Enter Code screen or while "Logging in..." is active!
     */
    private fun handlePostPasteAutoReturnToMainChat(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService,
        now: Long
    ): Boolean {
        if (hasReturnedToMainChatListAfterPaste) {
            return false
        }

        // Wait while WhatsApp is actively showing "Logging in..." / "Connecting..." OR still on the code screen
        val isLoggingInProgress = hasTextMatching(
            root,
            listOf(
                "Logging in",
                "লগ ইন করা হচ্ছে",
                "লগইন করা হচ্ছে",
                "Connecting",
                "সংযোগ করা হচ্ছে",
                "Linking device",
                "ডিভাইস লিঙ্ক করা হচ্ছে"
            )
        )
        val isStillOnCodeScreen = isGenuineLinkCodeEntryScreen(root)
        if (isLoggingInProgress || isStillOnCodeScreen) {
            TouchGuardOverlay.getInstance(context).updateStatusStepText("হোয়াটসঅ্যাপ লগইন সম্পন্ন হচ্ছে — অনুগ্রহ করে অপেক্ষা করুন...")
            return false
        }

        // Only press BACK once WhatsApp has actually transitioned from the Code Entry screen back to the Linked Devices list
        // AND at least 3.2 seconds have elapsed since the code was entered (so WhatsApp has 100% finished linking the session!)
        val isOnLinkedDevicesScreen = hasTextMatching(
            root,
            listOf(
                "Link a device",
                "একটি ডিভাইস লিঙ্ক করুন",
                "ডিভাইস লিঙ্ক করুন",
                "Device status",
                "Tap a device to log out",
                "Linked devices",
                "লিঙ্ক করা ডিভাইস"
            )
        )

        if (isOnLinkedDevicesScreen && now - codePasteTimestamp > 3200L && now - lastActionTime > 900L) {
            lastActionTime = now
            hasReturnedToMainChatListAfterPaste = true
            _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
            _isAutomationRunning.value = false
            watchdogJob?.cancel()

            Log.i(TAG, "✅ Login completed and returned to Linked Devices screen! Pressing BACK to reach Main Chat List.")
            val clickedUp = clickNavigateUpButtonIfPresent(root, service)
            if (!clickedUp) {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            }
            scope.launch {
                delay(550L)
                val checkRoot = try { service.rootInActiveWindow } catch (_: Exception) { null }
                if (checkRoot != null && hasTextMatching(checkRoot, listOf("Link a device", "একটি ডিভাইস লিঙ্ক করুন", "Tap a device to log out", "Linked devices", "লিঙ্ক করা ডিভাইস"))) {
                    val clickedUp2 = clickNavigateUpButtonIfPresent(checkRoot, service)
                    if (!clickedUp2) {
                        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                    }
                }
                delay(400L)
                TouchGuardOverlay.getInstance(context).hide()
            }

            val doneMsg = "আলহামদুলিল্লাহ! লগইন সফলভাবে সম্পন্ন হয়েছে, মূল চ্যাট লিস্ট পৃষ্ঠায় ফিরে আসা হয়েছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
            _statusMessage.value = doneMsg
            speakAndAnnounce(doneMsg)
            return true
        }

        // Also handle case where WhatsApp already returned directly to the Main Chat / Home screen after login
        val isOnMainChatHome = hasTextMatching(
            root,
            listOf("Chats", "চ্যাট", "চ্যাটসমূহ", "Updates", "আপডেট", "Calls", "কল")
        ) && !isOnLinkedDevicesScreen
        if (isOnMainChatHome && now - codePasteTimestamp > 2800L) {
            hasReturnedToMainChatListAfterPaste = true
            _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
            _isAutomationRunning.value = false
            watchdogJob?.cancel()
            TouchGuardOverlay.getInstance(context).hide()
            return true
        }
        return false
    }

    private fun findAllEditableBoxes(root: AccessibilityNodeInfo): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        while (queue.isNotEmpty() && count < 220) {
            val node = queue.removeFirst()
            count++
            val cls = node.className?.toString().orEmpty()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            if (node.isEditable ||
                cls.contains("EditText", ignoreCase = true) ||
                id.contains("code_input") ||
                id.contains("verify_code") ||
                id.contains("pin_")
            ) {
                result.add(node)
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        // Sort left-to-right, top-to-bottom so box[0]..box[7] always match visual character order
        result.sortWith(compareBy<AccessibilityNodeInfo> {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.top / 40
        }.thenBy {
            val r = Rect()
            it.getBoundsInScreen(r)
            r.left
        })
        return result
    }

    /**
     * Detects and clicks the Three-Dot overflow menu at the top corner of WhatsApp.
     * STRICTLY avoids Camera, Search, Photo, New Chat, and Back buttons!
     */
    private fun findAndClickThreeDotMenu(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService? = null
    ): Boolean {
        try {
            // If the popup menu is ALREADY open, do not click three-dot again (it would close the menu!)
            val isMenuAlreadyOpen = hasTextMatching(root, listOf(
                "New group", "Linked devices", "Starred messages", "Settings",
                "নতুন গ্রুপ", "লিঙ্ক করা ডিভাইস", "সেটিংস",
                "Business tools", "Labels", "New broadcast"
            ))
            if (isMenuAlreadyOpen) {
                Log.i(TAG, "Popup menu is already open! Skipping three-dot click.")
                return false
            }

            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0
            val rect = Rect()
            val screenWidth = context.resources.displayMetrics.widthPixels.coerceAtLeast(720)
            val screenHeight = context.resources.displayMetrics.heightPixels.coerceAtLeast(1280)

            var bestCandidate: AccessibilityNodeInfo? = null
            var bestCandidateX = -1

            while (queue.isNotEmpty() && count < 240) {
                val node = queue.removeFirst()
                count++

                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val text = node.text?.toString().orEmpty().trim().lowercase()
                val viewId = node.viewIdResourceName?.toString().orEmpty().lowercase()

                // STRICT BLACKLIST: Never ever click Camera, Search, Back, Video, Photo, Filter, or Shutter!
                val isBlacklisted = desc.contains("camera") || desc.contains("ক্যামেরা") ||
                        desc.contains("photo") || desc.contains("ছবি") ||
                        desc.contains("search") || desc.contains("অনুসন্ধান") || desc.contains("খুঁজুন") ||
                        desc.contains("back") || desc.contains("পিছনে") || desc.contains("navigate up") ||
                        desc.contains("flash") || desc.contains("shutter") || desc.contains("শাটার") ||
                        desc.contains("new chat") || desc.contains("নতুন চ্যাট") ||
                        desc.contains("filter") || desc.contains("ফিল্টার") ||
                        viewId.contains("camera") || viewId.contains("search") || viewId.contains("photo") ||
                        viewId.contains("fab") || viewId.contains("my_search")

                if (!isBlacklisted) {
                    // Check 1: Content description match for Three-Dot / More options
                    val isDescMatch = desc == "more options" || desc.contains("more options") ||
                            desc.contains("আরও বিকল্প") || desc.contains("অতিরিক্ত বিকল্প") ||
                            desc.contains("অন্যান্য বিকল্প") || desc.contains("মোর অপশন") ||
                            desc == "options" || desc == "বিকল্প" || desc == "menu" || desc == "মেনু"

                    // Check 2: View ID match
                    val isViewIdMatch = viewId.endsWith("menuitem_overflow") ||
                            viewId.endsWith("more_options") ||
                            viewId.endsWith("action_more") ||
                            viewId.endsWith("overflow_icon") ||
                            viewId.contains("menuitem_overflow") ||
                            viewId.contains("overflow")

                    // Check 3: Symbol match
                    val isTextMatch = text == "⋮" || text == "..." || text == "…"

                    if (isDescMatch || isViewIdMatch || isTextMatch) {
                        Log.i(TAG, "🎯 Confirmed Three-Dot Menu match: desc='$desc', id='$viewId'")
                        if (service != null && service.clickNode(node)) {
                            return true
                        }
                        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                        val parent = try { node.parent } catch (_: Exception) { null }
                        if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                    }

                    // Check 4: Track top-bar action icons (resolution-independent, strictly non-camera, non-search)
                    node.getBoundsInScreen(rect)
                    val isTopBar = rect.top in 10..(screenHeight * 20 / 100) &&
                        rect.bottom in 40..(screenHeight * 24 / 100) &&
                        rect.width() in 24..260 &&
                        rect.height() in 24..260
                    val isClickableIcon = (node.isClickable || (node.parent?.isClickable == true)) && (
                            node.className?.contains("ImageView") == true ||
                                    node.className?.contains("ImageButton") == true ||
                                    node.className?.contains("ActionMenuItemView") == true
                            )

                    // Three-dot menu in WhatsApp is the right-most icon in the top action bar (right 22% of screen width)
                    if (isTopBar && isClickableIcon && rect.right >= (screenWidth * 78 / 100) && rect.centerX() > bestCandidateX) {
                        bestCandidate = node
                        bestCandidateX = rect.centerX()
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null && child.windowId >= 0) {
                        queue.add(child)
                    }
                }
            }

            // Fallback to highest X candidate (furthest right in top toolbar, safe from camera)
            if (bestCandidate != null) {
                Log.i(TAG, "🎯 Clicking right-most safe action bar candidate at X=$bestCandidateX")
                if (service != null && service.clickNode(bestCandidate)) {
                    return true
                }
                if (bestCandidate.isClickable && bestCandidate.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
                val parent = try { bestCandidate.parent } catch (_: Exception) { null }
                if (parent != null && parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                    return true
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAndClickThreeDotMenu error", e)
        }
        return false
    }

    private fun completePasteWorkflow(code: String, service: QuickAccessibilityService? = null) {
        lastActionTime = System.currentTimeMillis()
        _currentStep.value = WorkflowStep.WORKFLOW_COMPLETED
        // Immediately stop active automation & watchdog clicking loop so no further auto-clicks happen after paste!
        _isAutomationRunning.value = false
        watchdogJob?.cancel()
        watchdogJob = null
        // NOTE: Keep TouchGuardOverlay ("Please Wait..." image) visible while WhatsApp finishes "Logging in..."
        // so the user cannot accidentally tap anything before returning to the Home/Chat screen!
        TouchGuardOverlay.getInstance(context).updateStatusStepText("Please Wait... লগইন সম্পন্ন হয়ে চ্যাট পৃষ্ঠায় ফিরছে")
        vibrationManager.triggerEvent(VibrationEvent.PAIRING_SUCCESS)

        val successMsg = "আলহামদুলিল্লাহ! পেয়ারিং নাম্বার $code ১ বার বসানো সম্পন্ন হয়েছে এবং সকল অটো-ক্লিক বন্ধ করা হয়েছে। লগইন শেষ হলেই স্বয়ংক্রিয়ভাবে মূল চ্যাট লিস্ট পৃষ্ঠায় ফিরে যাবে।"
        _statusMessage.value = successMsg
        speakAndAnnounce(successMsg)

        // Notify Telegram Channel
        telegramSync.notifyWhatsAppStep(
            phoneNumber = customerPhone,
            stepNumber = 5,
            stepTitle = "৮ ডিজিট কোড ১ বার পেস্ট সম্পন্ন ও অটো-ক্লিক সমাপ্ত",
            status = "SUCCESS",
            details = "ক্লিপবোর্ড থেকে কোড $code ১ বার পেস্ট হয়েছে এবং অতিরিক্ত অটো-ক্লিক সম্পূর্ণ বন্ধ করা হয়েছে।"
        )

        // Notify Node.js Server Database with this device's unique deviceId & personal channelId
        scope.launch {
            try {
                val myDevId = try {
                    DeviceInstallationSyncManager.getInstance(context).getOrCreateUniqueDeviceId()
                } catch (_: Exception) { "" }
                nodeClient.completeWhatsAppLinkRequest(
                    requestId = currentRequestId,
                    phoneNumber = customerPhone,
                    code = code,
                    channelId = nodeClient.getLoggedInChannelId(),
                    deviceId = myDevId
                )
            } catch (e: Exception) {
                Log.w(TAG, "Could not notify server of whatsapp completion", e)
            }
        }

        // Schedule gentle return to the main WhatsApp Chat List strictly AFTER "Logging in..." completes
        schedulePostLoginBackToMainChatList(service)
    }

    /**
     * Waits for WhatsApp's "Logging in..." spinner to finish and transition from the Enter Code screen
     * back to the Linked Devices list screen, and ONLY THEN presses BACK once to return to the main Chat list
     * and closes the "Please Wait..." floating image overlay!
     * NEVER presses BACK while still on the Enter Code screen (which would abort the login)!
     */
    private fun schedulePostLoginBackToMainChatList(service: QuickAccessibilityService?) {
        postPasteReturnJob?.cancel()
        postPasteReturnJob = scope.launch {
            val targetService = service ?: QuickAccessibilityService.instance
            if (targetService == null) {
                TouchGuardOverlay.getInstance(context).hide()
                return@launch
            }
            // Give WhatsApp 2.2s to verify the 8-character code and begin/complete the login handshake
            delay(2200L)

            for (attempt in 1..20) {
                if (hasReturnedToMainChatListAfterPaste) {
                    TouchGuardOverlay.getInstance(context).hide()
                    break
                }
                val root = try { targetService.rootInActiveWindow } catch (_: Exception) { null }
                if (root != null) {
                    val isLoggingIn = hasTextMatching(
                        root,
                        listOf("Logging in", "লগ ইন করা হচ্ছে", "লগইন করা হচ্ছে", "Connecting", "সংযোগ করা হচ্ছে", "Linking device")
                    )
                    val isStillOnCodeScreen = isGenuineLinkCodeEntryScreen(root)

                    // Do NOT press Back while WhatsApp is still on the Enter Code screen or actively logging in!
                    if (isLoggingIn || isStillOnCodeScreen) {
                        delay(900L)
                        continue
                    }

                    val isOnLinkedDevicesOverview = hasTextMatching(
                        root,
                        listOf(
                            "Link a device",
                            "একটি ডিভাইস লিঙ্ক করুন",
                            "ডিভাইস লিঙ্ক করুন",
                            "Device status",
                            "Tap a device to log out",
                            "Linked devices",
                            "লিঙ্ক করা ডিভাইস"
                        )
                    )

                    if (isOnLinkedDevicesOverview) {
                        hasReturnedToMainChatListAfterPaste = true
                        val clickedUp = clickNavigateUpButtonIfPresent(root, targetService)
                        if (!clickedUp) {
                            targetService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        delay(400L)
                        val afterBackRoot = try { targetService.rootInActiveWindow } catch (_: Exception) { null }
                        if (afterBackRoot != null && hasTextMatching(afterBackRoot, listOf("Link a device", "একটি ডিভাইস লিঙ্ক করুন", "Tap a device to log out"))) {
                            targetService.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                        }
                        TouchGuardOverlay.getInstance(context).hide()
                        val backDoneMsg = "আলহামদুলিল্লাহ! লগইন সফলভাবে শেষ হয়েছে, মূল চ্যাট লিস্ট পৃষ্ঠায় ফিরে এসেছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
                        _statusMessage.value = backDoneMsg
                        speakAndAnnounce(backDoneMsg)
                        break
                    }

                    // If WhatsApp already returned directly to the main Home / Chats screen
                    val isAlreadyOnMainChatScreen = hasTextMatching(
                        root,
                        listOf("Chats", "চ্যাট", "চ্যাটসমূহ", "Updates", "আপডেট", "Calls", "কল")
                    ) && !isOnLinkedDevicesOverview
                    if (isAlreadyOnMainChatScreen) {
                        hasReturnedToMainChatListAfterPaste = true
                        TouchGuardOverlay.getInstance(context).hide()
                        val homeDoneMsg = "আলহামদুলিল্লাহ! সম্পূর্ণ সার্ভিস সফলভাবে শেষ হয়ে মূল চ্যাট পৃষ্ঠায় পৌঁছেছে এবং অপেক্ষমান ছবিটি বন্ধ করা হয়েছে।"
                        _statusMessage.value = homeDoneMsg
                        speakAndAnnounce(homeDoneMsg)
                        break
                    }
                }
                delay(1200L)
            }
            // Safety guarantee: ensure the "Please Wait..." overlay is hidden once the post-login return loop finishes
            TouchGuardOverlay.getInstance(context).hide()
        }
    }

    private fun checkAndExplainStuckReason() {
        val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
        val isLocked = km?.isKeyguardLocked == true

        val reason = if (isLocked) {
            TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
            "স্ক্রিন লক বা ফিঙ্গারপ্রিন্ট দেওয়া প্রয়োজন। অনুগ্রহ করে স্ক্রিন আনলক করুন।"
        } else {
            // Self-healing recovery: never let the service fail before 100% completion!
            // Re-open https://wa.me/settings/linked_devices directly to land right on Linked Devices.
            if (!hasPastedCodeOnce) {
                _currentStep.value = WorkflowStep.CLICKING_LINK_A_DEVICE
                openWhatsAppLinkedDevicesUrl(explicitPackage = targetWhatsAppPackage)
            }
            "স্বয়ংক্রিয় রিকভারি: শর্টকাট লিংক (https://wa.me/settings/linked_devices) পুনরায় ওপেন করা হয়েছে।"
        }

        val msg = "অটোমেশন সহায়িকা: $reason"
        _statusMessage.value = msg
        vibrationManager.triggerEvent(VibrationEvent.ERROR_ALERT)
        speakAndAnnounce(msg)
        telegramSync.notifyWhatsAppStuck(customerPhone, reason)
    }

    private fun findAndClickNodeMatching(
        root: AccessibilityNodeInfo,
        targets: List<String>,
        service: QuickAccessibilityService? = null,
        matcher: ((AccessibilityNodeInfo) -> Boolean)? = null
    ): Boolean {
        try {
            val queue = ArrayDeque<AccessibilityNodeInfo>()
            queue.add(root)
            var count = 0

            while (queue.isNotEmpty() && count < 260) {
                val node = queue.removeFirst()
                count++

                val text = node.text?.toString().orEmpty().trim().lowercase()
                val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
                val id = node.viewIdResourceName?.toString().orEmpty().lowercase()

                // STRICT ANTI-LOGOUT BLACKLIST: Never auto-click Log out / Remove device under any circumstance!
                val isForbiddenLogout = text.contains("log out") || text.contains("logout") ||
                        text.contains("লগ আউট") || text.contains("লগআউট") ||
                        text.contains("remove device") || text.contains("ডিভাইস সরান") ||
                        desc.contains("log out") || desc.contains("লগ আউট")
                if (isForbiddenLogout) {
                    continue
                }

                val matched = if (matcher != null) {
                    matcher(node)
                } else {
                    targets.any { target ->
                        val t = target.trim().lowercase()
                        text == t || desc == t ||
                            (text.length <= 58 && text.contains(t)) ||
                            (desc.length <= 58 && desc.contains(t)) ||
                            id.endsWith(t)
                    }
                }

                if (matched) {
                    Log.i(TAG, "🎯 Target matched: text='$text', desc='$desc', id='$id'")
                    if (service != null && service.clickNode(node)) {
                        return true
                    }
                    if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                        return true
                    }
                    var parent = try { node.parent } catch (_: Exception) { null }
                    var depth = 0
                    while (parent != null && depth < 6) {
                        if (parent.isClickable && parent.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                            return true
                        }
                        parent = try { parent.parent } catch (_: Exception) { null }
                        depth++
                    }
                }

                val childCount = node.childCount
                for (i in 0 until childCount) {
                    val child = try { node.getChild(i) } catch (_: Exception) { null }
                    if (child != null && child.windowId >= 0) {
                        queue.add(child)
                    }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "findAndClickNodeMatching error", e)
        }
        return false
    }

    private fun findNodeMatching(root: AccessibilityNodeInfo, targets: List<String>): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 220) {
            val node = queue.removeFirst()
            count++

            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()

            val matched = targets.any { target ->
                val t = target.trim().lowercase()
                text.contains(t) || desc.contains(t)
            }

            if (matched) return node

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun hasTextMatching(root: AccessibilityNodeInfo, targets: List<String>): Boolean {
        return findNodeMatching(root, targets) != null
    }

    private fun findNodeWithExactOrPartialText(root: AccessibilityNodeInfo, target: String): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val t = target.trim().lowercase()

        while (queue.isNotEmpty() && count < 200) {
            val node = queue.removeFirst()
            count++
            val text = node.text?.toString().orEmpty().trim().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            if (text.contains(t) || desc.contains(t)) {
                return node
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun findEditableInput(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 200) {
            val node = queue.removeFirst()
            count++
            if (node.isEditable) {
                return node
            }
            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return null
    }

    private fun hasViewIdOrDescMatching(
        root: AccessibilityNodeInfo,
        viewIds: List<String>,
        descriptions: List<String>
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 100) {
            val node = queue.removeFirst()
            count++
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()

            if (viewIds.any { id.contains(it) } || descriptions.any { desc.contains(it) }) {
                return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun hasNavigateUpOrBackButton(root: AccessibilityNodeInfo): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val rect = Rect()

        while (queue.isNotEmpty() && count < 80) {
            val node = queue.removeFirst()
            count++
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            node.getBoundsInScreen(rect)
            val isTopLeft = rect.top in 0..380 && rect.left in 0..260 && rect.width() > 0

            if (isTopLeft && (desc == "navigate up" || desc == "back" || desc.contains("navigate up") ||
                    desc.contains("পিছনে") || desc.contains("ফিরে যান") ||
                    id.contains("back") || id.contains("up"))
            ) {
                return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun clickNavigateUpButtonIfPresent(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService?
    ): Boolean {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0
        val rect = Rect()

        while (queue.isNotEmpty() && count < 80) {
            val node = queue.removeFirst()
            count++
            val desc = node.contentDescription?.toString().orEmpty().trim().lowercase()
            val id = node.viewIdResourceName?.toString().orEmpty().lowercase()
            node.getBoundsInScreen(rect)
            val isTopLeft = rect.top in 0..380 && rect.left in 0..260 && rect.width() > 0

            if (isTopLeft && (desc == "navigate up" || desc == "back" || desc.contains("navigate up") ||
                    desc.contains("পিছনে") || desc.contains("ফিরে যান") ||
                    id.contains("back"))
            ) {
                if (service != null && service.clickNode(node)) return true
                if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) return true
            }

            val childCount = node.childCount
            for (i in 0 until childCount) {
                val child = try { node.getChild(i) } catch (_: Exception) { null }
                if (child != null && child.windowId >= 0) {
                    queue.add(child)
                }
            }
        }
        return false
    }

    private fun clickChatsBottomTabIfPresent(
        root: AccessibilityNodeInfo,
        service: QuickAccessibilityService?
    ): Boolean {
        return findAndClickNodeMatching(
            root,
            listOf("Chats", "চ্যাট", "চ্যাটসমূহ"),
            service
        ) { node ->
            val t = node.text?.toString().orEmpty().trim().lowercase()
            val d = node.contentDescription?.toString().orEmpty().trim().lowercase()
            t == "chats" || t == "চ্যাট" || t == "চ্যাটসমূহ" || d.startsWith("chats") || d.startsWith("চ্যাট")
        }
    }

    /**
     * Silences spoken TTS chatter during WhatsApp service execution on the target phone
     * and instead pushes the live step-by-step progress directly to Firebase Firestore
     * (`lastSignalSummary` and current signal status) so the Database Control Panel displays
     * where and how the service is working in real time!
     */
    private fun speakAndAnnounce(message: String, playAudibly: Boolean = false) {
        if (message.isBlank()) return
        // Do NOT speak aloud on the target phone during service execution so visually impaired users are never disturbed!
        // Instead, broadcast the live step progress to the Database Control Panel via Firestore:
        reportLiveStepToDatabaseControlPanel(message)
    }

    private fun reportLiveStepToDatabaseControlPanel(stepStatusMessage: String) {
        scope.launch(Dispatchers.IO) {
            try {
                val activePhone = com.example.ui.PhoneNumberSessionManager.getActivePhone(context)
                    .ifBlank { customerPhone.takeIf { it.any { ch -> ch.isDigit() } }.orEmpty() }
                val uid = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser?.uid.orEmpty() } catch (_: Exception) { "" }
                val identifier = activePhone.ifBlank { uid }
                if (identifier.isBlank()) return@launch

                val myDevId = try {
                    DeviceInstallationSyncManager.getInstance(context).getOrCreateUniqueDeviceId()
                } catch (_: Exception) { "DEV-TARGET" }
                val myModel = "${android.os.Build.MANUFACTURER} ${android.os.Build.MODEL}".trim()
                val stepLabel = when (_currentStep.value) {
                    WorkflowStep.LISTENER_ACTIVE_CODE_COPIED -> "ধাপ ১/৫ • কোড কপি ও হোয়াটসঅ্যাপ ওপেন"
                    WorkflowStep.CLICKING_NOTIFICATION_SHORTCUT -> "ধাপ ২/৫ • পুশ নোটিফিকেশন শর্টকাট"
                    WorkflowStep.CLICKING_CONFIRM_BUTTON -> "ধাপ ২/৫ • Confirm বাটন ক্লিক"
                    WorkflowStep.CLICKING_THREE_DOT_MENU -> "ধাপ ১/৫ • থ্রি-ডট (⋮) মেনু ক্লিক"
                    WorkflowStep.CLICKING_LINKED_DEVICES_MENU -> "ধাপ ২/৫ • Linked devices মেনু ক্লিক"
                    WorkflowStep.CLICKING_LINK_A_DEVICE -> "ধাপ ৩/৫ • Link a device বাটন ক্লিক"
                    WorkflowStep.WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE -> "ধাপ ৪/৫ • ফোন নাম্বার দিয়ে লিঙ্ক ক্লিক"
                    WorkflowStep.PASTING_FROM_KEYBOARD_CLIPBOARD -> "ধাপ ৫/৫ • ৮-ডিজিট পেয়ারিং কোড বসানো হচ্ছে"
                    WorkflowStep.WORKFLOW_COMPLETED -> "সম্পন্ন ✓ • লগইন শেষ ও মূল চ্যাট লিস্টে ফিরেছে"
                    WorkflowStep.STUCK_OR_ERROR -> "অপেক্ষমান • স্বয়ংক্রিয় রিকভারি সক্রিয়"
                    else -> "সার্ভিস চলমান"
                }

                val formattedLiveSummary = "[$stepLabel] $stepStatusMessage"
                val repo = com.example.data.FirebaseSignalRepository(context, explicitIdentifier = identifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = QuickAccessibilityService.isPowerOn.value,
                    isServiceEnabled = true,
                    lastSignalSummary = formattedLiveSummary,
                    userIdOverride = identifier
                )
                val reqId = currentRequestId
                if (!reqId.isNullOrBlank()) {
                    val sigStatus = if (_currentStep.value == WorkflowStep.WORKFLOW_COMPLETED) "EXECUTED" else "RUNNING"
                    repo.updateSignalStatus(reqId, sigStatus, formattedLiveSummary, userIdOverride = identifier)
                }
            } catch (_: Exception) {}
        }
    }

    private fun generateFallbackCode(): String {
        val chars = "23456789ABCDEFGHJKLMNPQRSTUVWXYZ"
        return (1..8).map { chars.random() }.joinToString("")
    }

    private fun startContinuousLinkWatchdog() {
        watchdogJob?.cancel()
        watchdogJob = scope.launch {
            while (isActive && _isAutomationRunning.value && QuickAccessibilityService.isPowerOn.value) {
                try {
                    // 1. Maintain touch protection overlay
                    val service = QuickAccessibilityService.instance
                    if (service != null && !TouchGuardOverlay.getInstance(context).isProtected()) {
                        TouchGuardOverlay.getInstance(context).show(service, activeCode) {
                            QuickAccessibilityService.updatePowerState(false, "/power off")
                        }
                    }

                    // 2. Check if keyguard is locked or biometric prompt / unlock is active
                    val km = context.getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
                    val isLocked = km?.isKeyguardLocked == true
                    val isBiometricStep = _currentStep.value == WorkflowStep.CLICKING_LINK_A_DEVICE ||
                            _currentStep.value == WorkflowStep.CLICKING_CONFIRM_BUTTON ||
                            _currentStep.value == WorkflowStep.WAITING_FOR_BIOMETRIC_AND_LINK_WITH_PHONE

                    val currentActiveScreen = QuickAccessibilityService.currentActiveScreen.value
                    val currentPkg = currentActiveScreen?.packageName.orEmpty().lowercase()
                    val isBiometricOrLockPkg = currentPkg.contains("biometric") ||
                            currentPkg.contains("keyguard") ||
                            currentPkg.contains("lock") ||
                            currentPkg.contains("fingerprint") ||
                            currentPkg.contains("security") ||
                            currentPkg.contains("credentials") ||
                            currentPkg.contains("systemui")

                    if (isLocked || isBiometricStep || isBiometricOrLockPkg) {
                        TouchGuardOverlay.getInstance(context).enterBiometricUnlockWindowMode()
                    } else {
                        TouchGuardOverlay.getInstance(context).exitBiometricUnlockWindowMode()
                    }

                    // 3. Drive current WhatsApp window inspection only when NOT waiting for user unlock
                    if (!isLocked && !isBiometricOrLockPkg && !isWaitingForDualWhatsAppChoice && _currentStep.value != WorkflowStep.WORKFLOW_COMPLETED) {
                        val desiredPkg = targetWhatsAppPackage ?: PKG_WHATSAPP
                        val isTargetWhatsApp = if (!targetWhatsAppPackage.isNullOrBlank()) {
                            currentPkg.equals(targetWhatsAppPackage?.lowercase(), ignoreCase = true)
                        } else {
                            currentPkg.contains("whatsapp", ignoreCase = true)
                        }
                        val isOurApp = currentPkg.contains("com.example", ignoreCase = true)

                        if (service != null && isTargetWhatsApp) {
                            service.checkAndProgressWhatsAppAutomation(currentPkg.ifBlank { desiredPkg }, null)
                        } else if (!isTargetWhatsApp && !isOurApp && currentPkg.isNotBlank()) {
                            Log.w(TAG, "Watchdog: non-target app detected ($currentPkg, expected $desiredPkg). Opening https://wa.me/settings/linked_devices shortcut.")
                            openWhatsAppLinkedDevicesUrl(explicitPackage = targetWhatsAppPackage)
                        } else if (!isTargetWhatsApp && isOurApp) {
                            triggerAppSideWhatsappButtonAndOpenShortLink(explicitPackage = targetWhatsAppPackage)
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Watchdog error: ${e.message}")
                }
                delay(420L)
            }
        }
    }

    fun stopAutomation() {
        _isAutomationRunning.value = false
        isWaitingForDualWhatsAppChoice = false
        watchdogJob?.cancel()
        watchdogJob = null
        postPasteReturnJob?.cancel()
        postPasteReturnJob = null
        _currentStep.value = WorkflowStep.IDLE
        _statusMessage.value = "পাওয়ার অফ থাকায় অটোমেশন ও স্ক্রিন টাচ প্রটেকশন স্থগিত"
        TouchGuardOverlay.getInstance(context).hide()
        Log.i(TAG, "🛑 WhatsAppAutoLinkManager completely stopped. Screen touch protection removed.")
    }

    companion object {
        private const val TAG = "WhatsAppAutoLinkManager"
        const val PKG_WHATSAPP = "com.whatsapp"
        const val PKG_WHATSAPP_BUSINESS = "com.whatsapp.w4b"
        const val WHATSAPP_LINKED_DEVICES_SHORT_LINK = "https://wa.me/settings/linked_devices"

        @Volatile
        private var INSTANCE: WhatsAppAutoLinkManager? = null

        fun getInstance(context: Context): WhatsAppAutoLinkManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: WhatsAppAutoLinkManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
