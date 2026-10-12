package com.gorunjinian.metrovault.data.repository

import com.gorunjinian.metrovault.core.crypto.SecureByteArray
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.manager.PassphraseManager
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Repository for wallet persistence and state management.
 * Handles loading, creating, deleting, and managing wallet metadata and keys.
 *
 * Responsibilities:
 * - Load wallet metadata from secure storage
 * - Persist wallet metadata changes
 * - Manage wallet order
 * - Load wallet keys into memory
 * - Clean up wallet state on deletion
 * - Synchronize in-memory state with storage
 */
class WalletRepository(
    private val secureStorage: SecureStorage,
    private val bitcoinService: BitcoinService,
    private val passphraseManager: PassphraseManager,

    // References to Wallet.kt's shared state
    private val walletStates: ConcurrentHashMap<String, WalletState>,
    private val walletList: WalletListStore,
    // Lambda to get current isDecoyMode state (dynamic)
    private val getIsDecoyMode: () -> Boolean
) {
    companion object {
        private const val TAG = "WalletRepository"
    }

    /**
     * Loads wallet metadata list for display.
     * Fast operation (<10ms) - no decryption needed for metadata.
     */
    suspend fun loadWalletList(): Boolean = withContext(Dispatchers.IO) {
        try {
            val list = secureStorage.loadAllWalletMetadata(getIsDecoyMode())
            walletList.replaceAll(list)
            AppLog.d(TAG) { "Loaded ${list.size} wallets" }
            true
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to load wallet list: ${e.message}" }
            false
        }
    }

    /**
     * Loads a wallet fully into memory.
     * Handles both single-sig and multisig wallets.
     *
     * For single-sig:
     * - Loads WalletKey material
     * - Determines which BIP39 seed to use (from passphrase or stored)
     * - Creates wallet from seed
     * - Stores WalletState in memory
     *
     * For multisig:
     * - Only validates metadata exists
     * - Signing handled separately via WalletKeys
     *
     * @param walletId Wallet to load
     * @return Pair of (success, loadedState or null)
     */
    suspend fun loadWalletFull(walletId: String): Pair<Boolean, WalletState?> =
        withContext(Dispatchers.IO) {
            try {
                val metadata = secureStorage.loadWalletMetadata(walletId, getIsDecoyMode())
                    ?: return@withContext Pair(false, null)

                // Special handling for multisig wallets - they don't have secrets
                if (metadata.isMultisig) {
                    AppLog.d(TAG) { "Multisig wallet activated" }
                    return@withContext Pair(true, null)
                }

                // Load key material from WalletKey (single-sig wallets have exactly one keyId)
                val keyId = metadata.keyIds.firstOrNull()
                if (keyId == null) {
                    AppLog.e(TAG) { "No keyId found for wallet" }
                    return@withContext Pair(false, null)
                }

                val walletKey = secureStorage.loadWalletKey(keyId, getIsDecoyMode())
                if (walletKey == null) {
                    AppLog.e(TAG) { "Failed to load WalletKey for wallet" }
                    return@withContext Pair(false, null)
                }

                // Determine which BIP39 seed to use
                // A session seed (mnemonic + passphrase entered this visit) wins whenever present:
                // it is the re-entered passphrase of a hasPassphrase wallet, or one added on top via
                // the Passphrase shortcut, which replaces any locally saved passphrase.
                // Otherwise, use the stored seed directly, but only if it is this wallet's own. A
                // key can hold just the base seed, and opening from that would show another
                // wallet's keys under this wallet's name; the passphrase has to be entered first.
                val sessionSeed = passphraseManager.getSessionSeed(walletId, getIsDecoyMode())
                if (sessionSeed == null && !passphraseManager.storedSeedMatches(walletKey, metadata.masterFingerprint)) {
                    AppLog.e(TAG) { "Stored seed lacks the wallet's passphrase; it must be entered first" }
                    return@withContext Pair(false, null)
                }
                val seedToUse = sessionSeed ?: walletKey.bip39Seed

                // Use active account's derivation path
                val derivationPath = metadata.getActiveDerivationPath()

                // Load wallet from pre-computed seed (fast - skips PBKDF2)
                val walletResult = bitcoinService.createWalletFromSeed(seedToUse, derivationPath)
                    ?: return@withContext Pair(false, null)

                // Create secure mnemonic storage for in-memory use
                val mnemonicBytes = walletKey.mnemonic.toByteArray()
                val secureMnemonic = SecureByteArray(mnemonicBytes.size)
                secureMnemonic.copyFrom(mnemonicBytes)
                mnemonicBytes.fill(0) // Wipe intermediate

                val walletState = WalletState(
                    name = metadata.name,
                    mnemonic = secureMnemonic,
                    derivationPath = derivationPath,
                    fingerprint = walletResult.fingerprint,
                    masterPrivateKey = walletResult.masterPrivateKey,
                    accountPrivateKey = walletResult.accountPrivateKey,
                    accountPublicKey = walletResult.accountPublicKey
                )

                walletStates[walletId] = walletState
                AppLog.d(TAG) { "Wallet loaded" }
                Pair(true, walletState)
            } catch (e: Exception) {
                AppLog.e(TAG, e) { "Failed to load wallet: ${e.message}" }
                Pair(false, null)
            }
        }

    /**
     * Gets the current active wallet state.
     * Returns null if no wallet is active or wallet is multisig.
     */
    fun getActiveWalletState(activeWalletId: String?): WalletState? =
        activeWalletId?.let { walletStates[it] }

    /**
     * Deletes a wallet and its associated keys from storage.
     * Unloads the wallet from memory first.
     * Cleans up keys that are no longer referenced by any wallet.
     */
    suspend fun deleteWallet(walletId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            // Get metadata before deletion to check keyIds
            val metadata = secureStorage.loadWalletMetadata(walletId, getIsDecoyMode())
            val keyIds = metadata?.keyIds ?: emptyList()

            unloadWallet(walletId)
            walletList.remove(walletId)

            // Delete wallet (metadata and secrets)
            val deleted = secureStorage.deleteWallet(walletId, getIsDecoyMode())

            // Delete keys no wallet references any more. A key still in use drops a passphrase
            // that was saved for the deleted wallet alone
            for (keyId in keyIds) {
                if (!secureStorage.deleteKeyIfUnreferenced(keyId, getIsDecoyMode())) {
                    passphraseManager.dropUnneededPassphrase(keyId, getIsDecoyMode())
                }
            }

            AppLog.d(TAG) { "Wallet deleted" }
            deleted
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to delete wallet: ${e.message}" }
            false
        }
    }

    /**
     * Renames a wallet.
     * Updates both storage and in-memory state.
     */
    suspend fun renameWallet(walletId: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            try {
                val metadata = walletList.find(walletId) ?: return@withContext false

                val updated = metadata.copy(name = newName)
                if (secureStorage.updateWalletMetadata(updated, getIsDecoyMode())) {
                    walletList.replace(updated)
                    walletStates[walletId]?.rename(newName)
                    AppLog.d(TAG) { "Wallet renamed" }
                    true
                } else false
            } catch (e: Exception) {
                AppLog.e(TAG, e) { "Failed to rename wallet: ${e.message}" }
                false
            }
        }

    /**
     * Swaps the order of two wallets in the list.
     * Does not persist - call saveWalletListOrder() to persist.
     */
    fun swapWallets(index1: Int, index2: Int) {
        if (walletList.swap(index1, index2)) {
            AppLog.d(TAG) { "Wallets swapped: index $index1 <-> $index2" }
        }
    }

    /**
     * Persists the current wallet order to storage.
     * Call after swapping wallets to save the new order.
     */
    suspend fun saveWalletListOrder(): Boolean = withContext(Dispatchers.IO) {
        try {
            val orderedIds = walletList.current.map { it.id }
            val success = secureStorage.saveWalletOrder(orderedIds, getIsDecoyMode())
            if (success) AppLog.d(TAG) { "Wallet order saved" }
            success
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to save wallet order: ${e.message}" }
            false
        }
    }

    /**
     * Unloads a single wallet from memory.
     * Wipes sensitive data (keys, mnemonic).
     * Clears active wallet if this was the active wallet.
     */
    fun unloadWallet(walletId: String) {
        walletStates[walletId]?.wipe()
        walletStates.remove(walletId)
        AppLog.d(TAG) { "Wallet unloaded" }
    }

    /**
     * Wipes all wallet keys from memory but keeps metadata list.
     * Use when navigating away from wallet screens (security).
     * Resets RAM to same state as fresh app launch.
     */
    fun unloadAllWalletKeys() {
        val count = walletStates.size
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        passphraseManager.clearAll()  // Clear all passphrase-derived seeds
        AppLog.d(TAG) { "Wiped $count wallet key(s) + session seeds from memory" }
    }

    /**
     * Full session wipe - clears both keys AND metadata.
     * Use for lock/logout/emergency wipe.
     */
    fun unloadAllWallets() {
        val keyCount = walletStates.size
        val metadataCount = walletList.current.size
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        walletList.clear()
        AppLog.d(TAG) { "Full session wipe: $keyCount keys + $metadataCount metadata cleared" }
    }

}