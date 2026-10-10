package com.example.util

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.JavascriptInterface
import android.widget.Toast

/**
 * Official JavascriptInterface bridge for accessibility assistance.
 * Allows web views, hybrid components, or JS scripts to safely copy verification / pairing codes
 * directly into the Android system ClipboardManager.
 */
class AndroidBridge(
    private val context: Context,
    private val onCopiedCallback: ((String) -> Unit)? = null
) {
    private val mainHandler = Handler(Looper.getMainLooper())

    /**
     * Called from JavaScript via: AndroidBridge.copyToClipboard(pairingCode);
     * Safely copies the code into Android's official ClipboardManager.
     */
    @JavascriptInterface
    fun copyToClipboard(pairingCode: String) {
        if (pairingCode.isBlank()) return

        mainHandler.post {
            try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val clip = ClipData.newPlainText("otp", pairingCode)
                clipboard.setPrimaryClip(clip)

                Toast.makeText(
                    context,
                    "ক্লিপবোর্ডে কপি করা হয়েছে: $pairingCode",
                    Toast.LENGTH_SHORT
                ).show()

                onCopiedCallback?.invoke(pairingCode)
            } catch (e: Exception) {
                e.printStackTrace()
            }
        }
    }

    /**
     * Retrieves the currently active text from Android ClipboardManager.
     */
    @JavascriptInterface
    fun getClipboardText(): String {
        return try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            val item = clipboard.primaryClip?.getItemAt(0)
            item?.text?.toString() ?: ""
        } catch (e: Exception) {
            ""
        }
    }

    companion object {
        const val JAVASCRIPT_OBJ_NAME = "AndroidBridge"

        /**
         * Native helper method to perform clipboard copy strictly when the text is not already in clipboard,
         * preventing repeated Android 12+/Samsung/Xiaomi "Copied to clipboard" toast spam.
         */
        fun copyToSystemClipboard(context: Context, text: String, label: String = "otp"): Boolean {
            val cleanTarget = text.trim()
            if (cleanTarget.isBlank()) return false
            return try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val existing = clipboard.primaryClip?.getItemAt(0)?.text?.toString()?.trim().orEmpty()
                if (existing == cleanTarget) {
                    return true
                }
                val clip = ClipData.newPlainText(label, cleanTarget)
                clipboard.setPrimaryClip(clip)
                true
            } catch (e: Exception) {
                e.printStackTrace()
                false
            }
        }

        fun getClipboardText(context: Context): String {
            return try {
                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                val item = clipboard.primaryClip?.getItemAt(0)
                item?.text?.toString().orEmpty()
            } catch (e: Exception) {
                ""
            }
        }
    }
}
