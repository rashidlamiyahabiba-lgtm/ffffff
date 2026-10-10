package com.example.util

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.AccessibilityServiceInfo
import android.app.AppOpsManager
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Process
import android.provider.Settings
import android.view.accessibility.AccessibilityManager
import com.example.service.QuickAccessibilityService

object AccessibilityHelper {

    /**
     * Checks if the app has SYSTEM_ALERT_WINDOW ("Display over other apps") permission.
     */
    fun canDrawOverlays(context: Context): Boolean {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            Settings.canDrawOverlays(context)
        } else {
            true
        }
    }

    /**
     * Opens the Android Settings page to grant "Display over other apps" (SYSTEM_ALERT_WINDOW) permission.
     */
    fun openOverlayPermissionSettings(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            try {
                val intent = Intent(
                    Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                    Uri.parse("package:${context.packageName}")
                ).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(intent)
            } catch (_: Exception) {
                try {
                    val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                        data = Uri.parse("package:${context.packageName}")
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(fallback)
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Checks whether the companion accessibility service is currently enabled and granted permission.
     */
    fun isAccessibilityServiceEnabled(context: Context): Boolean = isServiceEnabled(context)

    fun isServiceEnabled(context: Context): Boolean {
        // Direct runtime instance check
        if (QuickAccessibilityService.isServiceActive.value || QuickAccessibilityService.instance != null) {
            return true
        }

        // AccessibilityManager check
        val am = context.getSystemService(Context.ACCESSIBILITY_SERVICE) as? AccessibilityManager
        if (am != null) {
            val enabledServices = am.getEnabledAccessibilityServiceList(AccessibilityServiceInfo.FEEDBACK_ALL_MASK)
            for (service in enabledServices) {
                val serviceInfo = service.resolveInfo?.serviceInfo
                if (serviceInfo != null &&
                    serviceInfo.packageName == context.packageName &&
                    serviceInfo.name == QuickAccessibilityService::class.java.name
                ) {
                    return true
                }
            }
        }

        // System Settings check
        return try {
            val expectedComponentName = ComponentName(context, QuickAccessibilityService::class.java).flattenToString()
            val enabledServicesSetting = Settings.Secure.getString(
                context.contentResolver,
                Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            ) ?: ""
            val accessibilityEnabled = Settings.Secure.getInt(
                context.contentResolver,
                Settings.Secure.ACCESSIBILITY_ENABLED,
                0
            ) == 1

            accessibilityEnabled && enabledServicesSetting.contains(expectedComponentName)
        } catch (e: Exception) {
            false
        }
    }

    /**
     * Builds the Accessibility Settings Intent targeting our exact QuickAccessibilityService
     * so the user doesn't have to hunt through "Downloaded apps" when supported by the OEM.
     */
    fun createAccessibilitySettingsIntent(context: Context): Intent {
        val componentName = ComponentName(context, QuickAccessibilityService::class.java).flattenToString()
        // Try direct service detail page on Android 10+ if resolved by the device's Settings app
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val detailIntent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                putExtra(":settings:fragment_args_key", componentName)
                val bundle = android.os.Bundle().apply {
                    putString(":settings:fragment_args_key", componentName)
                }
                putExtra(":settings:show_fragment_args", bundle)
            }
            if (detailIntent.resolveActivity(context.packageManager) != null) {
                return detailIntent
            }
        }
        return Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
            putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
            putExtra(":settings:fragment_args_key", componentName)
            val bundle = android.os.Bundle().apply {
                putString(":settings:fragment_args_key", componentName)
            }
            putExtra(":settings:show_fragment_args", bundle)
        }
    }

    /**
     * Checks in real-time whether Android 13+ (API 33+) "Restricted settings" is currently BLOCKING
     * this app from enabling its Accessibility Service.
     *
     * Uses AppOpsManager ("android:access_restricted_settings") when exposed by the OS,
     * combined with sideloaded installer source verification.
     */
    fun isRestrictedSettingsCurrentlyBlocked(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        if (isServiceEnabled(context)) return false

        // 1. Query AppOpsManager for OPSTR_ACCESS_RESTRICTED_SETTINGS ("android:access_restricted_settings")
        try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager
            if (appOps != null) {
                val mode = appOps.checkOpNoThrow(
                    "android:access_restricted_settings",
                    Process.myUid(),
                    context.packageName
                )
                if (mode == AppOpsManager.MODE_ALLOWED) {
                    // User has ALREADY unlocked "Allow restricted settings" via Biometric!
                    return false
                }
                if (mode == AppOpsManager.MODE_IGNORED || mode == AppOpsManager.MODE_ERRORED || mode == AppOpsManager.MODE_DEFAULT) {
                    return isSideloadedOnAndroid13Plus(context)
                }
            }
        } catch (_: Exception) {
            // Fallback to installer check if OEM hides this op string
        }

        return isSideloadedOnAndroid13Plus(context)
    }

    /**
     * Checks whether the user has explicitly unlocked "Allow restricted settings" in App Info on Android 13+.
     */
    fun isRestrictedSettingsAllowedBySystem(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return true
        return try {
            val appOps = context.getSystemService(Context.APP_OPS_SERVICE) as? AppOpsManager ?: return false
            val mode = appOps.checkOpNoThrow(
                "android:access_restricted_settings",
                Process.myUid(),
                context.packageName
            )
            mode == AppOpsManager.MODE_ALLOWED
        } catch (_: Exception) {
            false
        }
    }

    /**
     * Builds the App Info Settings Intent with highlight on "Allow restricted settings".
     */
    fun createAppInfoSettingsIntent(context: Context): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            putExtra(":settings:fragment_args_key", "restricted_settings")
            val bundle = android.os.Bundle().apply {
                putString(":settings:fragment_args_key", "restricted_settings")
            }
            putExtra(":settings:show_fragment_args", bundle)
        }
    }

    /**
     * Directly opens the Android Accessibility Settings screen.
     * Note: When called from an Activity, we do NOT add FLAG_ACTIVITY_NEW_TASK so pressing Back returns directly to our app.
     */
    fun openAccessibilitySettings(context: Context): Boolean {
        return try {
            val intent = createAccessibilitySettingsIntent(context).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val fallbackIntent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS).apply {
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallbackIntent)
                true
            } catch (e2: Exception) {
                false
            }
        }
    }

    /**
     * Launches a 2-screen automatic chain:
     * 1. Accessibility Settings stays right underneath on the backstack
     * 2. App Info (Allow restricted settings) opens immediately on top
     * When the visually impaired user unlocks Restricted Settings via biometric/PIN and presses Back once,
     * Android automatically lands them directly inside Accessibility Settings!
     */
    fun launchRestrictedThenAccessibilityChain(context: Context): Boolean {
        return try {
            if (context is android.app.Activity) {
                val accessibilityIntent = createAccessibilitySettingsIntent(context)
                val appInfoIntent = createAppInfoSettingsIntent(context)
                context.startActivities(arrayOf(accessibilityIntent, appInfoIntent))
                true
            } else {
                openAppInfoSettings(context)
            }
        } catch (e: Exception) {
            openAppInfoSettings(context)
        }
    }

    /**
     * Attempts to open the direct detail page for our Accessibility Service on Android 10+ (API 29+),
     * or falls back to general Accessibility Settings.
     */
    fun openServiceDetailSettings(context: Context): Boolean {
        try {
            val componentName = ComponentName(context, QuickAccessibilityService::class.java).flattenToString()
            val intent = Intent("android.settings.ACCESSIBILITY_DETAILS_SETTINGS").apply {
                putExtra(Intent.EXTRA_COMPONENT_NAME, componentName)
                data = Uri.parse("package:${context.packageName}")
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(intent)
            return true
        } catch (e: Exception) {
            // Fall back to general accessibility settings
            return openAccessibilitySettings(context)
        }
    }

    /**
     * Checks if the device is running Android 13 (API 33) or higher, where sideloaded APKs
     * may trigger the "Restricted setting" dialog unless installed via a session installer or unlocked in App Info.
     */
    fun isAndroid13OrHigher(): Boolean = Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU

    /**
     * Checks if the app was sideloaded (not installed from Google Play Store) on Android 13+.
     */
    fun isSideloadedOnAndroid13Plus(context: Context): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return false
        return try {
            val installer = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                context.packageManager.getInstallSourceInfo(context.packageName).installingPackageName
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName)
            }
            installer != "com.android.vending"
        } catch (_: Exception) {
            true
        }
    }

    /**
     * Opens this app's App Info page in system settings with highlight arguments for Restricted Settings.
     * Essential for Android 13+ where users need to tap 3-dots -> "Allow restricted settings".
     */
    fun openAppInfoSettings(context: Context): Boolean {
        return try {
            val intent = createAppInfoSettingsIntent(context).apply {
                if (context !is android.app.Activity) {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
            }
            context.startActivity(intent)
            true
        } catch (e: Exception) {
            try {
                val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:${context.packageName}")
                    if (context !is android.app.Activity) {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                }
                context.startActivity(fallback)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Opens Google Play Protect / Security settings so user or helper can easily check Play Protect status.
     */
    fun openPlayProtectOrSecuritySettings(context: Context): Boolean {
        return try {
            val intent = Intent("com.google.android.gms.security.settings.VerifyAppsSettingsActivity").apply {
                setPackage("com.google.android.gms")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(intent)
            true
        } catch (_: Exception) {
            try {
                val securityIntent = Intent(Settings.ACTION_SECURITY_SETTINGS).apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                context.startActivity(securityIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Performs a global accessibility action if the service is running.
     */
    fun performGlobalAction(action: Int): Boolean {
        val service = QuickAccessibilityService.instance ?: return false
        return service.performGlobalAction(action)
    }

    /**
     * Checks if WhatsApp or WhatsApp Business is installed on the device.
     */
    fun isWhatsAppInstalled(context: Context): Boolean {
        return getWhatsAppPackage(context) != null
    }

    /**
     * Returns the installed WhatsApp package name (com.whatsapp or com.whatsapp.w4b), or null.
     */
    fun getWhatsAppPackage(context: Context): String? {
        val pm = context.packageManager
        val packages = listOf("com.whatsapp", "com.whatsapp.w4b")
        for (pkg in packages) {
            try {
                pm.getPackageInfo(pkg, 0)
                return pkg
            } catch (_: Exception) {}
        }
        return null
    }

    /**
     * Launches WhatsApp app or WhatsApp Linked Devices settings page.
     * If not installed, falls back to Google Play Store / Web link.
     */
    fun launchWhatsApp(context: Context, isLinkedDevices: Boolean = false): Boolean {
        val pkg = getWhatsAppPackage(context)
        return try {
            if (pkg != null) {
                if (isLinkedDevices) {
                    try {
                        val linkedIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://wa.me/settings/linked_devices")).apply {
                            setPackage(pkg)
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        }
                        context.startActivity(linkedIntent)
                        return true
                    } catch (_: Exception) {
                        // fallback to standard launch
                    }
                }
                val launchIntent = context.packageManager.getLaunchIntentForPackage(pkg)?.apply {
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                }
                if (launchIntent != null) {
                    context.startActivity(launchIntent)
                    true
                } else {
                    val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://api.whatsapp.com/")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(browserIntent)
                    true
                }
            } else {
                // Open Play Store
                try {
                    val marketIntent = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=com.whatsapp")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(marketIntent)
                    true
                } catch (_: Exception) {
                    val playStoreIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=com.whatsapp")).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                    }
                    context.startActivity(playStoreIntent)
                    true
                }
            }
        } catch (e: Exception) {
            false
        }
    }
}
