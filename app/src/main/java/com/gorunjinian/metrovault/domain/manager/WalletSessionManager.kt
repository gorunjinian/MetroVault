package com.gorunjinian.metrovault.domain.manager

import android.util.Log
import com.gorunjinian.metrovault.core.storage.SecureStorage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages wallet session lifecycle.
 * Handles login/logout, decoy mode, and session cleanup.
 * 
 * Extracted from Wallet.kt to follow single responsibility principle.
 */
class WalletSessionManager(
    private val secureStorage: SecureStorage,
    private val passphraseManager: PassphraseManager
) {
    companion object {
        private const val TAG = "WalletSessionManager"
    }

    var isDecoyMode: Boolean = false
        private set

    /**
     * Sets the session after successful login.
     * Runs migration silently if needed.
     * 
     * @param isDecoy Whether this is a decoy vault session
     */
    suspend fun setSession(isDecoy: Boolean) {
        this.isDecoyMode = isDecoy
        withContext(Dispatchers.IO) {
            secureStorage.runMigrationIfNeeded(isDecoy)
        }
        Log.d(TAG, "Session set: isDecoy=$isDecoy")
    }

    /**
     * Clears the current session and all sensitive data.
     * 
     * @param onUnloadWallets Callback to unload all wallet states from memory
     */
    fun clearSession(onUnloadWallets: () -> Unit) {
        isDecoyMode = false
        passphraseManager.clearAll()
        secureStorage.clearSession()
        onUnloadWallets()
        Log.d(TAG, "Session cleared")
    }

    /**
     * Emergency wipe - clears all sensitive data immediately.
     * Use when emergency situations require immediate data destruction.
     * 
     * @param onClearSession Callback to clear the full session
     */
    fun emergencyWipe(onClearSession: () -> Unit) {
        Log.w(TAG, "EMERGENCY WIPE - Clearing all wallet data from memory")
        passphraseManager.clearAll()
        onClearSession()
        System.gc()
    }
}
