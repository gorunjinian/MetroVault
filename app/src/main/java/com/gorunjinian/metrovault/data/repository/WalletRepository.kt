package com.gorunjinian.metrovault.data.repository

import android.util.Log
import com.gorunjinian.metrovault.core.crypto.SecureByteArray
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.manager.PassphraseManager
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
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
    private val walletMetadataList: MutableList<WalletMetadata>,
    private val walletsFlow: MutableStateFlow<List<WalletMetadata>>,
    private val walletListLock: Any,
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
            synchronized(walletListLock) {
                walletMetadataList.clear()
                walletMetadataList.addAll(list)
                walletsFlow.value = walletMetadataList.toList()
            }
            Log.d(TAG, "Loaded ${list.size} wallets")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet list: ${e.message}", e)
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
                    Log.d(TAG, "Multisig wallet activated: $walletId")
                    return@withContext Pair(true, null)
                }

                // Load key material from WalletKey (single-sig wallets have exactly one keyId)
                val keyId = metadata.keyIds.firstOrNull()
                if (keyId == null) {
                    Log.e(TAG, "No keyId found for wallet $walletId")
                    return@withContext Pair(false, null)
                }

                val walletKey = secureStorage.loadWalletKey(keyId, getIsDecoyMode())
                if (walletKey == null) {
                    Log.e(TAG, "Failed to load WalletKey $keyId for wallet $walletId")
                    return@withContext Pair(false, null)
                }

                // Determine which BIP39 seed to use
                // If hasPassphrase=true, check for session seed (computed from user-entered passphrase)
                // Otherwise, use the stored seed directly
                val seedToUse = if (metadata.hasPassphrase) {
                    // Check if user has entered passphrase this session
                    passphraseManager.getSessionSeed(walletId, getIsDecoyMode())
                        ?: walletKey.bip39Seed  // Fallback to stored base seed
                } else {
                    walletKey.bip39Seed
                }

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
                Log.d(TAG, "Wallet loaded: $walletId (fingerprint=${walletResult.fingerprint})")
                Pair(true, walletState)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load wallet: ${e.message}", e)
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
            synchronized(walletListLock) {
                walletMetadataList.removeAll { it.id == walletId }
                walletsFlow.value = walletMetadataList.toList()
            }

            // Delete wallet (metadata and secrets)
            val deleted = secureStorage.deleteWallet(walletId, getIsDecoyMode())

            // Clean up keys that are no longer referenced by any wallet
            for (keyId in keyIds) {
                secureStorage.deleteKeyIfUnreferenced(keyId, getIsDecoyMode())
            }

            Log.d(TAG, "Wallet deleted: $walletId")
            deleted
        } catch (e: Exception) {
            Log.e(TAG, "Failed to delete wallet: ${e.message}", e)
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
                val metadata = synchronized(walletListLock) {
                    walletMetadataList.find { it.id == walletId }
                } ?: return@withContext false

                val updated = metadata.copy(name = newName)
                if (secureStorage.updateWalletMetadata(updated, getIsDecoyMode())) {
                    synchronized(walletListLock) {
                        val idx = walletMetadataList.indexOfFirst { it.id == walletId }
                        if (idx != -1) {
                            walletMetadataList[idx] = updated
                            walletsFlow.value = walletMetadataList.toList()
                        }
                    }
                    walletStates[walletId]?.rename(newName)
                    Log.d(TAG, "Wallet renamed: $walletId -> $newName")
                    true
                } else false
            } catch (e: Exception) {
                Log.e(TAG, "Failed to rename wallet: ${e.message}", e)
                false
            }
        }

    /**
     * Swaps the order of two wallets in the list.
     * Does not persist - call saveWalletListOrder() to persist.
     */
    fun swapWallets(index1: Int, index2: Int) {
        synchronized(walletListLock) {
            if (index1 in walletMetadataList.indices && index2 in walletMetadataList.indices) {
                val temp = walletMetadataList[index1]
                walletMetadataList[index1] = walletMetadataList[index2]
                walletMetadataList[index2] = temp
                walletsFlow.value = walletMetadataList.toList()
                Log.d(TAG, "Wallets swapped: index $index1 <-> $index2")
            }
        }
    }

    /**
     * Persists the current wallet order to storage.
     * Call after swapping wallets to save the new order.
     */
    suspend fun saveWalletListOrder(): Boolean = withContext(Dispatchers.IO) {
        try {
            val orderedIds = synchronized(walletListLock) {
                walletMetadataList.map { it.id }
            }
            val success = secureStorage.saveWalletOrder(orderedIds, getIsDecoyMode())
            if (success) Log.d(TAG, "Wallet order saved")
            success
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save wallet order: ${e.message}", e)
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
        Log.d(TAG, "Wallet unloaded: $walletId")
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
        Log.d(TAG, "Wiped $count wallet key(s) + session seeds from memory")
    }

    /**
     * Full session wipe - clears both keys AND metadata.
     * Use for lock/logout/emergency wipe.
     */
    fun unloadAllWallets() {
        val keyCount = walletStates.size
        val metadataCount = walletMetadataList.size
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        synchronized(walletListLock) {
            walletMetadataList.clear()
            walletsFlow.value = emptyList()
        }
        Log.d(
            TAG,
            "Full session wipe: $keyCount keys + $metadataCount metadata cleared"
        )
    }

}