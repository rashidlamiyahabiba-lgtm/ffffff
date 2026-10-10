package com.example.util

import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import com.example.R
import com.example.service.QuickAccessibilityService

/**
 * TouchGuardOverlay:
 * Displays a full-screen floating "Please Wait... / LOADING" image shield (`img_please_wait_overlay_1791158763523`)
 * during the WhatsApp Linked auto-click workflow so visually impaired users cannot accidentally tap chats,
 * profiles, or wrong buttons while the service is running.
 *
 * Key Capabilities for Visually Impaired Users:
 * 1. Shows the full-screen "Please Wait..." floating image (`img_please_wait_overlay_1791158763523`) as soon as
 *    the WhatsApp auto-link workflow or `/poweron` Screen Shield starts.
 * 2. Absorbs all accidental finger taps/touches on the underlying WhatsApp screen (`setOnTouchListener { _, _ -> true }`)
 *    with gentle haptic feedback and audio reassurance while accessibility node actions (`performAction(ACTION_CLICK)`,
 *    `ACTION_PASTE`, `ACTION_SET_TEXT`) continue working seamlessly underneath.
 * 3. Automatically hides/shrinks during Biometric / Fingerprint / PIN unlock (`enterBiometricUnlockWindowMode()`)
 *    so the lock screen and fingerprint sensor are 100% unobstructed, and immediately restores the full-screen
 *    touch shield once unlocked.
 * 4. Automatically closes ONLY after login completes and WhatsApp safely returns to the Home / Main Chat screen.
 */
class TouchGuardOverlay private constructor(private val context: Context) {

    private var activeWindowManager: WindowManager? = null
    private val vibrator = context.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
    private val voiceGuidance = VoiceGuidanceManager.getInstance(context)
    private val mainHandler = Handler(Looper.getMainLooper())

    private var overlayView: FrameLayout? = null
    private var waitImageContainer: FrameLayout? = null
    private var waitImageView: ImageView? = null
    private var statusSubtitleView: TextView? = null
    private var codeBadgeView: TextView? = null
    private var isShowing = false
    private var isBiometricModeActive = false
    private var isPersistentPowerOnShieldActive = false
    private var lastTouchAnnouncementTime = 0L
    private var activePairingCode: String = ""

    fun isPowerOnShieldActive(): Boolean = isPersistentPowerOnShieldActive

    fun setPersistentPowerOnShield(enabled: Boolean, service: QuickAccessibilityService? = null) {
        isPersistentPowerOnShieldActive = enabled
        if (enabled) {
            val targetService = service ?: QuickAccessibilityService.instance
            show(targetService, activePairingCode)
            updateStatusStepText("🛡️ স্ক্রিন সুরক্ষা সক্রিয় — অনুগ্রহ করে অপেক্ষা করুন (/poweroff করলে বন্ধ হবে)")
        } else {
            hide(forceHidePowerShield = true)
        }
    }

    fun show(
        service: QuickAccessibilityService? = QuickAccessibilityService.instance,
        pairingCode: String = "",
        onPowerOffClicked: () -> Unit = {}
    ) {
        val hostContext: Context = service ?: context
        val canDrawAppOverlay = Build.VERSION.SDK_INT < Build.VERSION_CODES.M ||
                android.provider.Settings.canDrawOverlays(hostContext)
        val canUseAccessibilityOverlay = service != null
        if (!canDrawAppOverlay && !canUseAccessibilityOverlay) {
            Log.w(TAG, "TouchGuardOverlay: Neither SYSTEM_ALERT_WINDOW nor AccessibilityService is active yet.")
            return
        }

        val wm = hostContext.getSystemService(Context.WINDOW_SERVICE) as? WindowManager ?: return
        activeWindowManager = wm
        if (pairingCode.isNotBlank()) {
            activePairingCode = pairingCode
        }

        mainHandler.post {
            try {
                if (isShowing && overlayView != null) {
                    updateBannerText(activePairingCode)
                    if (!isBiometricModeActive) {
                        overlayView?.visibility = View.VISIBLE
                        setPassThrough(false)
                    }
                    return@post
                }

                val layoutType = when {
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canUseAccessibilityOverlay ->
                        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
                    Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && canDrawAppOverlay ->
                        WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
                    else -> {
                        @Suppress("DEPRECATION")
                        WindowManager.LayoutParams.TYPE_SYSTEM_ALERT
                    }
                }

                // IMPORTANT: Do NOT set FLAG_NOT_TOUCHABLE by default!
                // The floating image shield MUST intercept accidental physical finger touches from the user
                // so they cannot accidentally tap chats, back buttons, or cancel the WhatsApp pairing flow.
                // Programmatic node clicks (`performAction(ACTION_CLICK)`) work directly on the accessibility tree,
                // and `clickAt` temporarily toggles `setPassThrough(true)` during coordinate gestures.
                val params = WindowManager.LayoutParams(
                    WindowManager.LayoutParams.MATCH_PARENT,
                    WindowManager.LayoutParams.MATCH_PARENT,
                    layoutType,
                    WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
                    PixelFormat.TRANSLUCENT
                ).apply {
                    gravity = Gravity.TOP or Gravity.START
                }

                val density = hostContext.resources.displayMetrics.density

                val root = FrameLayout(hostContext).apply {
                    setBackgroundColor(Color.parseColor("#E604131D"))
                    importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS
                    isClickable = true
                    isFocusable = false

                    // Intercept all accidental physical screen touches to keep the user 100% safe during automation
                    setOnTouchListener { _, event ->
                        if (event?.action == MotionEvent.ACTION_DOWN) {
                            handleAccidentalTouchIntercepted()
                        }
                        true
                    }

                    val imageHolder = FrameLayout(hostContext).apply {
                        layoutParams = FrameLayout.LayoutParams(
                            FrameLayout.LayoutParams.MATCH_PARENT,
                            FrameLayout.LayoutParams.MATCH_PARENT
                        )

                        // Full-screen floating "Please Wait..." protection image only (no step-by-step text banners on target app side)
                        val bgImage = ImageView(hostContext).apply {
                            setImageResource(R.drawable.img_please_wait_overlay_1791158763523)
                            scaleType = ImageView.ScaleType.FIT_CENTER
                            setBackgroundColor(Color.parseColor("#D9041019"))
                            layoutParams = FrameLayout.LayoutParams(
                                FrameLayout.LayoutParams.MATCH_PARENT,
                                FrameLayout.LayoutParams.MATCH_PARENT
                            )
                        }
                        waitImageView = bgImage
                        addView(bgImage)
                    }
                    waitImageContainer = imageHolder
                    addView(imageHolder)
                }

                wm.addView(root, params)
                overlayView = root
                isShowing = true
                isBiometricModeActive = false
                Log.i(TAG, "🛡️ TouchGuardOverlay ('Please Wait' floating image shield) active and blocking accidental touches.")

            } catch (e: Exception) {
                Log.e(TAG, "Error displaying TouchGuardOverlay", e)
            }
        }
    }

    /**
     * During Biometric / PIN / Pattern Unlock Mode, temporarily make the overlay pass-through and hidden
     * so Android's BiometricPrompt and Lock Screen never reject touches due to window obscuration.
     */
    fun enterBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (isBiometricModeActive) return@post
                isBiometricModeActive = true
                setPassThrough(true)
                root.visibility = View.GONE
                Log.i(TAG, "🔓 TouchGuardOverlay hidden for Biometric/PIN Unlock so sensor and PIN pad are 100% unobstructed.")
            } catch (e: Exception) {
                Log.w(TAG, "Error entering biometric unlock window mode", e)
            }
        }
    }

    /**
     * Restores the full-screen floating image shield and touch interception after biometric / PIN / pattern unlock finishes.
     */
    fun exitBiometricUnlockWindowMode() {
        mainHandler.post {
            try {
                val root = overlayView ?: return@post
                if (!isBiometricModeActive) return@post
                isBiometricModeActive = false
                root.visibility = View.VISIBLE
                setPassThrough(false)
                statusSubtitleView?.text = "হোয়াটসঅ্যাপ অটো-লিঙ্ক সার্ভিস চলমান রয়েছে — অনুগ্রহ করে অপেক্ষা করুন"
            } catch (e: Exception) {
                Log.w(TAG, "Error exiting biometric unlock window mode", e)
            }
        }
    }

    fun updateStatusStepText(stepMessage: String) {
        mainHandler.post {
            try {
                statusSubtitleView?.text = stepMessage
            } catch (_: Exception) {}
        }
    }

    private fun updateBannerText(code: String) {
        if (code.isNotBlank()) {
            activePairingCode = code
            codeBadgeView?.text = "পেয়ারিং কোড: $activePairingCode • লগইন সম্পন্ন হলে স্বয়ংক্রিয়ভাবে বন্ধ হবে"
        }
    }

    private fun handleAccidentalTouchIntercepted() {
        // Provide gentle haptic feedback only (no spoken voice chatter during service execution)
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator?.vibrate(VibrationEffect.createOneShot(35, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                vibrator?.vibrate(35)
            }
        } catch (_: Exception) {}
    }

    /**
     * Toggles whether touches pass through the floating image shield (used briefly during coordinate gestures
     * or keyboard/biometric interaction) vs blocking accidental user finger touches.
     */
    fun setPassThrough(passThrough: Boolean, temporarilyLowerAlphaForPinPad: Boolean = false) {
        val applyAction = Runnable {
            try {
                val root = overlayView ?: return@Runnable
                val params = root.layoutParams as? WindowManager.LayoutParams ?: return@Runnable
                val currentTouchable = (params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE) == 0
                val targetTouchable = !passThrough
                if (currentTouchable != targetTouchable) {
                    if (passThrough) {
                        params.flags = params.flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE
                    } else {
                        params.flags = params.flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()
                    }
                    activeWindowManager?.updateViewLayout(root, params)
                }
                root.alpha = if (temporarilyLowerAlphaForPinPad) 0.18f else 1.0f
            } catch (e: Exception) {
                Log.w(TAG, "Error updating pass-through flag", e)
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) {
            applyAction.run()
        } else {
            mainHandler.post(applyAction)
        }
    }

    fun hide(forceHidePowerShield: Boolean = false) {
        mainHandler.post {
            try {
                if (forceHidePowerShield) {
                    isPersistentPowerOnShieldActive = false
                }
                // If /poweron Screen Shield Mode is active and this is NOT a /poweroff command,
                // keep the floating protection image on screen so accidental touches cannot cause calls/uninstalls!
                if (!forceHidePowerShield && isPersistentPowerOnShieldActive && QuickAccessibilityService.isPowerOn.value) {
                    exitBiometricUnlockWindowMode()
                    statusSubtitleView?.text = "🛡️ স্ক্রিন সুরক্ষা সক্রিয় (/poweroff করলে বন্ধ হবে)"
                    Log.i(TAG, "🛡️ Keeping floating image active because /poweron Screen Shield is ON.")
                    return@post
                }
                if (isShowing && overlayView != null) {
                    activeWindowManager?.removeView(overlayView)
                    overlayView = null
                    waitImageContainer = null
                    waitImageView = null
                    statusSubtitleView = null
                    codeBadgeView = null
                    activeWindowManager = null
                    isShowing = false
                    isBiometricModeActive = false
                    Log.i(TAG, "🛡️ TouchGuardOverlay ('Please Wait' image) removed.")
                }
            } catch (e: Exception) {
                Log.e(TAG, "Error removing TouchGuardOverlay", e)
            }
        }
    }

    fun isProtected(): Boolean = isShowing

    companion object {
        private const val TAG = "TouchGuardOverlay"

        @Volatile
        private var INSTANCE: TouchGuardOverlay? = null

        fun getInstance(context: Context): TouchGuardOverlay {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TouchGuardOverlay(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
