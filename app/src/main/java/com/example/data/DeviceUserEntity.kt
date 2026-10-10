package com.example.data

import androidx.room.Entity
import androidx.room.PrimaryKey

/**
 * Local Room database entity representing a unique device installation registered
 * as an individual user in the database.
 */
@Entity(tableName = "device_user_records")
data class DeviceUserEntity(
    @PrimaryKey
    val deviceId: String,
    val serverUserId: String = "",
    val channelId: String = "",
    val isChannelLoggedIn: Boolean = false,
    val userLabel: String = "",
    val deviceModel: String = "",
    val manufacturer: String = "",
    val androidVersion: String = "",
    val osSdkInt: Int = 34,
    val screenResolution: String = "",
    val screenDensity: String = "",
    val networkType: String = "",
    val carrierName: String = "",
    val defaultLanguage: String = "",
    val timeZone: String = "",
    val appVersion: String = "1.0",
    val installerPackage: String = "",
    val isServiceEnabled: Boolean = false,
    val isInstallNotificationSent: Boolean = false,
    val firstInstalledAt: Long = System.currentTimeMillis(),
    val lastSyncedAt: Long = System.currentTimeMillis()
)
