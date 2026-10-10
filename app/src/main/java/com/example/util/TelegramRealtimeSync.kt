package com.example.util

import android.content.Context

/**
 * Legacy Telegram Bot database sync stub.
 * The previous Telegram Bot database has been completely deleted and disabled
 * so the app operates 100% cleanly via Firebase Firestore without any Telegram Bot dependency.
 */
class TelegramRealtimeSync private constructor(private val context: Context) {

    var isRealtimeStreamEnabled: Boolean = false

    fun notifyServiceConnected(appName: String = "WhatsApp Linked Helper") {}

    fun notifyTwoDigitLoginSubmitted(
        twoDigitId: String,
        mappedChatId: String,
        deviceId: String,
        serverUserId: String,
        deviceModel: String
    ) {}

    fun notifyChannelLoginConnected(
        channelId: String,
        deviceId: String,
        serverUserId: String,
        deviceModel: String
    ) {}

    fun notifyChannelLogoutAndServiceDisabled(
        revokedChatId: String,
        deviceId: String,
        serverUserId: String,
        deviceModel: String
    ) {}

    fun notifyAppInstalledOnDevice(
        deviceId: String,
        serverUserId: String,
        userLabel: String,
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
        installerPackage: String,
        channelId: String = "",
        isNewInstall: Boolean = true
    ) {}

    fun notifyPermissionTurnedOn(appName: String = "WhatsApp Linked Helper") {}

    fun notifyWhatsAppScreenOpened(screenName: String = "") {}

    fun notifyAutoClickAction(stepName: String, targetElement: String, details: String = "") {}

    fun notifyCodeCapturedAndCopied(code: String, targetChannelId: String? = null) {}

    fun handleIncomingTelegramText(
        rawText: String,
        sourceChannelId: String = "",
        replyToText: String = ""
    ) {}

    fun notifyServiceDisconnected() {}

    fun notifyWhatsAppStep(
        phoneNumber: String,
        stepNumber: Int,
        stepTitle: String,
        status: String,
        details: String
    ) {}

    fun notifyFirebaseSignalDispatchedToTelegramDb(
        userId: String,
        deviceId: String,
        signalType: String,
        command: String,
        messageBn: String,
        status: String = "SENT"
    ) {}

    fun notifyPhoneLoginToTelegramDb(
        phoneNumber: String,
        loginTargetMode: String,
        deviceId: String,
        deviceModel: String
    ) {}

    fun notifyWhatsAppStuck(phoneNumber: String, reason: String) {}

    fun notifyDualWhatsAppSelectionRequired(
        pairingCode: String,
        phoneNumber: String,
        targetChatId: String? = null
    ) {}

    fun notifyWhatsAppAppSelectionConfirmed(
        chosenAppName: String,
        chosenPackage: String,
        pairingCode: String,
        targetChatId: String? = null
    ) {}

    fun notifyCustomMessage(title: String, messageText: String) {}

    fun notifyTalkBackSpoken(spokenText: String) {}

    fun performDatabaseBackupToTelegram(onResult: ((Boolean, String) -> Unit)? = null) {
        onResult?.invoke(false, "টেলিগ্রাম বট ডাটাবেইজ ডিলিট করা হয়েছে।")
    }

    fun sendMessage(htmlText: String, silent: Boolean = true, targetChatId: String? = null) {}

    companion object {
        @Volatile
        private var INSTANCE: TelegramRealtimeSync? = null

        fun getInstance(context: Context): TelegramRealtimeSync {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: TelegramRealtimeSync(context.applicationContext).also { INSTANCE = it }
            }
        }
    }
}
