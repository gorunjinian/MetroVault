package com.gorunjinian.metrovault.core.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.WindowManager
import com.gorunjinian.metrovault.core.logging.AppLog
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlin.time.Duration.Companion.milliseconds

/**
 * Security utilities for protecting sensitive data
 */
object SecurityUtils {

    private const val TAG = "SecurityUtils"

    /**
     * Disables screenshots and screen recording for an activity
     */
    fun disableScreenshots(activity: Activity) {
        activity.window.setFlags(
            WindowManager.LayoutParams.FLAG_SECURE,
            WindowManager.LayoutParams.FLAG_SECURE
        )
    }


    /**
     * Clears clipboard - compatible with API 26+
     */
    fun clearClipboard(context: Context) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
                clipboard?.clearPrimaryClip()
            } else {
                // Fallback for API < 28: set empty clip
                clipboard?.setPrimaryClip(ClipData.newPlainText("", ""))
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to clear clipboard: ${e.message}" }
        }
    }

    /** Default window before a sensitive clipboard entry is wiped. */
    const val SENSITIVE_CLIPBOARD_CLEAR_MS = 20_000L

    /**
     * Single entry point for every clipboard write in the app.
     *
     * Secrets (private keys, derived passwords, seed material) must pass
     * `sensitive = true` so the entry is wiped after [SENSITIVE_CLIPBOARD_CLEAR_MS];
     * public values (addresses, xpubs, signatures) pass `false` and stay until the
     * user replaces them.
     *
     * @param context Any context; the application context is used for the delayed clear
     * @param label Label for the clipboard data
     * @param text Text to copy
     * @param sensitive Whether the value must be auto-cleared
     * @param delayMs Clear delay, only honoured when [sensitive] is true
     */
    fun copyToClipboard(
        context: Context,
        label: String,
        text: String,
        sensitive: Boolean,
        delayMs: Long = SENSITIVE_CLIPBOARD_CLEAR_MS
    ) {
        if (sensitive) {
            copyToClipboardWithAutoClear(context, label, text, delayMs)
            return
        }
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            clipboard?.setPrimaryClip(ClipData.newPlainText(label, text))
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to copy to clipboard: ${e.message}" }
        }
    }

    /**
     * Copies text to clipboard and automatically clears it after a delay.
     * Only clears if the clipboard still contains the same content.
     *
     * Prefer [copyToClipboard] with `sensitive = true` at call sites; this stays
     * public for the QR card, which always deals with exportable secrets.
     *
     * @param context Application context
     * @param label Label for the clipboard data
     * @param text Text to copy
     * @param delayMs Delay in milliseconds before clearing (default: 20 seconds)
     */
    fun copyToClipboardWithAutoClear(
        context: Context,
        label: String,
        text: String,
        delayMs: Long = SENSITIVE_CLIPBOARD_CLEAR_MS
    ) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)

            // Use application context to ensure the coroutine scope survives navigation
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                delay(delayMs.milliseconds)
                // Only clear if the same content is still in clipboard
                val currentClip = try {
                    clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                } catch (_: Exception) {
                    null
                }
                if (currentClip == text) {
                    clearClipboard(appContext)
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to copy to clipboard: ${e.message}" }
        }
    }
}
