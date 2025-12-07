package com.gorunjinian.metrovault.core.util

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.view.View
import android.view.WindowManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/**
 * Security utilities for protecting sensitive data
 */
object SecurityUtils {

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
     * Disables autofill for a View and its descendants.
     * This prevents the keyboard from suggesting passwords, passkeys, or other autofill options.
     * Critical for security in a Bitcoin wallet app.
     *
     * @param view The view to disable autofill on
     */
    fun disableAutofill(view: View) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS = 0x8
            // This tells the system not to use this view or any of its children for autofill
            view.importantForAutofill = View.IMPORTANT_FOR_AUTOFILL_NO_EXCLUDE_DESCENDANTS
        }
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
            e.printStackTrace()
        }
    }

    /**
     * Clears clipboard after a delay
     * Useful for automatically clearing sensitive data after copying
     * 
     * @param context Application context
     * @param delayMs Delay in milliseconds before clearing (default: 60 seconds)
     */
    fun clearClipboardAfterDelay(context: Context, delayMs: Long = 60000) {
        CoroutineScope(Dispatchers.Main).launch {
            delay(delayMs)
            clearClipboard(context)
        }
    }

    /**
     * Copies text to clipboard and automatically clears it after a delay.
     * Only clears if the clipboard still contains the same content.
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
        delayMs: Long = 20_000
    ) {
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText(label, text)
            clipboard?.setPrimaryClip(clip)

            // Use application context to ensure the coroutine scope survives navigation
            val appContext = context.applicationContext
            CoroutineScope(Dispatchers.Main).launch {
                delay(delayMs)
                // Only clear if the same content is still in clipboard
                val currentClip = try {
                    clipboard?.primaryClip?.getItemAt(0)?.text?.toString()
                } catch (e: Exception) {
                    null
                }
                if (currentClip == text) {
                    clearClipboard(appContext)
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
}
