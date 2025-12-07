package com.gorunjinian.metrovault.app

import android.app.Application
import android.util.Log
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.crypto.SecureMemoryManager

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
        Log.d(TAG, "MetroVault application starting")

        // Initialize Wallet
        wallet = Wallet.getInstance(this)

        // Initialize SecureMemoryManager
        secureMemoryManager = SecureMemoryManager.initialize(this)
        secureMemoryManager.setWallet(wallet)

        Log.d(TAG, "Security components initialized")
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.w(TAG, "Application terminating - performing emergency wipe")

        // Emergency wipe on app termination
        secureMemoryManager.performEmergencyWipe()
    }

    override fun onLowMemory() {
        super.onLowMemory()
        Log.w(TAG, "Low memory condition")
        // SecureMemoryManager handles this via ComponentCallbacks2
    }

    override fun onTrimMemory(level: Int) {
        super.onTrimMemory(level)
        Log.d(TAG, "Memory trim requested: level=$level")
        // SecureMemoryManager handles this via ComponentCallbacks2
    }
}