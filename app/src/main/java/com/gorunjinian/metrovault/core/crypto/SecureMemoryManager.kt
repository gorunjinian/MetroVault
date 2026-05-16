package com.gorunjinian.metrovault.core.crypto

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.domain.Wallet

/**
 * Manages memory security for the application.
 * Monitors lifecycle events and memory conditions to wipe sensitive data.
 */
class SecureMemoryManager private constructor(application: Application) : ComponentCallbacks2 {

    private var wallet: Wallet? = null

    init {
        application.registerComponentCallbacks(this)
    }

    fun setWallet(wallet: Wallet) {
        this.wallet = wallet
    }

    fun performEmergencyWipe() {
        AppLog.w(TAG) { "Performing emergency memory wipe" }
        wallet?.emergencyWipe()
        // Suggest garbage collection
        System.gc()
    }

    @Suppress("DEPRECATION")
    override fun onTrimMemory(level: Int) {
        when (level) {
            ComponentCallbacks2.TRIM_MEMORY_COMPLETE -> {
                // App is about to be killed - perform emergency wipe
                AppLog.w(TAG) { "TRIM_MEMORY_COMPLETE - performing emergency wipe" }
                performEmergencyWipe()
            }

            ComponentCallbacks2.TRIM_MEMORY_MODERATE,
            ComponentCallbacks2.TRIM_MEMORY_BACKGROUND -> {
                // App is in background but not critical - keep session alive
                AppLog.d(TAG) { "Memory trim level $level - session remains active" }
                // Do NOT wipe - we want to keep the session alive when backgrounded
            }

            ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW,
            ComponentCallbacks2.TRIM_MEMORY_RUNNING_MODERATE -> {
                // App is running but memory is low - keep session alive
                // User is actively using the app, don't wipe data
                AppLog.d(TAG) { "Memory trim level $level while running - session remains active" }
                // Do NOT wipe - app is actively being used
            }

            ComponentCallbacks2.TRIM_MEMORY_UI_HIDDEN -> {
                // UI is hidden (user pressed home or switched apps)
                // Wipe sensitive data for security - user must re-authenticate when returning
                AppLog.d(TAG) { "UI hidden - performing security wipe" }
                performEmergencyWipe()
            }
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        // No-op
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        AppLog.w(TAG) { "Low memory warning - wiping sensitive data" }
        performEmergencyWipe()
    }

    companion object {
        private const val TAG = "SecureMemoryManager"
        
        @Volatile
        private var instance: SecureMemoryManager? = null

        fun initialize(application: Application): SecureMemoryManager {
            return instance ?: synchronized(this) {
                instance ?: SecureMemoryManager(application).also { instance = it }
            }
        }
    }
}
