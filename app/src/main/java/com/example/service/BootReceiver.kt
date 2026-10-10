package com.example.service

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import com.example.util.DeviceInstallationSyncManager

/**
 * BootReceiver:
 * Ensures device installation registration and WhatsApp Linked readiness across device reboots and package updates.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context?, intent: Intent?) {
        val action = intent?.action ?: return
        if (action == Intent.ACTION_MY_PACKAGE_REPLACED) {
            context?.let { ctx ->
                DeviceInstallationSyncManager.getInstance(ctx).ensureDeviceRegisteredAndNotifyInstall(forceNotify = true)
            }
            return
        }
        if (action == Intent.ACTION_BOOT_COMPLETED || action == "android.intent.action.QUICKBOOT_POWERON") {
            Log.i("BootReceiver", "📱 Device rebooted. Syncing WhatsApp Linked registration...")
            context?.let { ctx ->
                DeviceInstallationSyncManager.getInstance(ctx).ensureDeviceRegisteredAndNotifyInstall(forceNotify = false)
            }
        }
    }
}
