package com.gorunjinian.metrovault.domain.manager

import android.util.Log
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService

/**
 * Manager for stateless (memory-only) wallets.
 * 
 * Stateless wallets exist only in RAM and are never persisted to storage.
 * They are created via SeedQR import and wiped when the session locks or
 * when the user navigates away.
 * 
 * This manager encapsulates all stateless wallet state and operations,
 * keeping the main Wallet class focused on persistent wallet management.
 */
class StatelessWalletManager(
    private val bitcoinService: BitcoinService
) {
    companion object {
        private const val TAG = "StatelessWalletManager"
    }
    
    /**
     * Stateless wallet state - exists only in memory, never persisted.
     */
    @Volatile
    private var state: WalletState? = null
    
    /**
     * Computes the master fingerprint for a mnemonic without modifying any state.
     * Use this for fingerprint preview during import flows to avoid race conditions.
     * 
     * @param mnemonic List of BIP39 words (12 or 24)
     * @param passphrase Optional BIP39 passphrase
     * @param derivationPath Derivation path to use
     * @return Fingerprint string (lowercase) if successful, null on error
     */
    fun computeFingerprintOnly(
        mnemonic: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): String? {
        return try {
            if (!bitcoinService.validateMnemonic(mnemonic)) {
                return null
            }
            val walletResult = bitcoinService.createWalletFromMnemonic(mnemonic, passphrase, derivationPath)
            walletResult?.fingerprint?.lowercase()
        } catch (e: Exception) {
            Log.e(TAG, "Error computing fingerprint: ${e.message}")
            null
        }
    }
    
    /**
     * Creates a stateless wallet from mnemonic and passphrase.
     * The wallet exists only in memory and is never persisted to storage.
     * 
     * @param mnemonic List of BIP39 words (12 or 24)
     * @param passphrase Optional BIP39 passphrase
     * @param derivationPath Derivation path to use
     * @return WalletState if successful, null on error
     */
    fun create(
        mnemonic: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): WalletState? {
        // Wipe any existing stateless wallet first
        wipe()
        
        return try {
            // Validate mnemonic
            if (!bitcoinService.validateMnemonic(mnemonic)) {
                Log.e(TAG, "Invalid mnemonic for stateless wallet")
                return null
            }
            
            // Create wallet in memory (no persistence)
            val walletResult = bitcoinService.createWalletFromMnemonic(mnemonic, passphrase, derivationPath)
            if (walletResult == null) {
                Log.e(TAG, "Failed to create stateless wallet from mnemonic")
                return null
            }
            
            // Create WalletState with the derived keys
            val walletState = WalletState(
                name = "Stateless Wallet",
                mnemonic = null, // Don't store mnemonic in stateless wallet
                derivationPath = derivationPath,
                fingerprint = walletResult.fingerprint.lowercase(),
                masterPrivateKey = walletResult.masterPrivateKey,
                accountPrivateKey = walletResult.accountPrivateKey,
                accountPublicKey = walletResult.accountPublicKey
            )
            
            state = walletState
            Log.d(TAG, "Stateless wallet created with fingerprint: ${walletState.fingerprint}")
            walletState
        } catch (e: Exception) {
            Log.e(TAG, "Error creating stateless wallet: ${e.message}", e)
            null
        }
    }
    
    /**
     * Gets the current stateless wallet state, if one exists.
     */
    fun get(): WalletState? = state
    
    /**
     * Wipes the stateless wallet from memory.
     * Called automatically when exiting the stateless wallet screen or on session lock.
     */
    fun wipe() {
        state?.let { walletState ->
            try {
                walletState.wipe()
                Log.d(TAG, "Stateless wallet wiped from memory")
            } catch (e: Exception) {
                Log.e(TAG, "Error wiping stateless wallet: ${e.message}")
            }
        }
        state = null
    }
    
    /**
     * Checks if a stateless wallet is currently active.
     */
    fun hasWallet(): Boolean = state != null
    
    /**
     * Unified wallet info for UI screens, handles both stateless and persistent wallets.
     * Eliminates repeated branching logic in screens like AccountKeysScreen and DescriptorsScreen.
     */
    data class ActiveWalletInfo(
        val isStateless: Boolean,
        val accountNumber: Int,
        val derivationPath: String,
        val accounts: List<Int>
    )
    
    /**
     * Gets unified wallet info for the currently active wallet.
     * Works for both stateless and persistent wallets.
     * 
     * @param metadata The metadata for persistent wallets (used when no stateless wallet is active)
     * @return ActiveWalletInfo with account and derivation info
     */
    fun getActiveWalletInfo(metadata: WalletMetadata?): ActiveWalletInfo {
        val statelessState = state
        return if (statelessState != null) {
            val accountNum = DerivationPaths.getAccountNumber(statelessState.derivationPath)
            ActiveWalletInfo(
                isStateless = true,
                accountNumber = accountNum,
                derivationPath = statelessState.derivationPath,
                accounts = listOf(accountNum)
            )
        } else {
            ActiveWalletInfo(
                isStateless = false,
                accountNumber = metadata?.activeAccountNumber ?: 0,
                derivationPath = metadata?.derivationPath ?: "",
                accounts = metadata?.accounts?.sorted() ?: listOf(0)
            )
        }
    }
}
