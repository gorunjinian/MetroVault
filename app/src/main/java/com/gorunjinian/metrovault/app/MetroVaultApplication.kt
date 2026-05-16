package com.gorunjinian.metrovault.app

import android.app.Application
import com.gorunjinian.metrovault.core.crypto.SecureMemoryManager
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.domain.Wallet

/**
 * - SecureMemoryManager for lifecycle-based memory wiping
 * - Wallet singleton
 */
class MetroVaultApplication : Application() {

    companion object {
        private const val TAG = "MetroVaultApplication"
    }

    lateinit var wallet: Wallet
        private set

    lateinit var secureMemoryManager: SecureMemoryManager
        private set

    override fun onCreate() {
        super.onCreate()
        AppLog.d(TAG) { "MetroVault application starting" }

        // Initialize Wallet
        wallet = Wallet.getInstance(this)

        // Initialize SecureMemoryManager
        secureMemoryManager = SecureMemoryManager.initialize(this)
        secureMemoryManager.setWallet(wallet)

        AppLog.d(TAG) { "Security components initialized" }
    }

    override fun onTerminate() {
        super.onTerminate()
        AppLog.w(TAG) { "Application terminating - performing emergency wipe" }

        // Emergency wipe on app termination
        secureMemoryManager.performEmergencyWipe()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        AppLog.w(TAG) { "Low memory condition" }
        // SecureMemoryManager handles this via ComponentCallbacks2
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        AppLog.d(TAG) { "Memory trim requested: level=$level" }
        // SecureMemoryManager handles this via ComponentCallbacks2
    }
}