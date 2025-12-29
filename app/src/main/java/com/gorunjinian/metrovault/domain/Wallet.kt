package com.gorunjinian.metrovault.domain

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.WalletCreationError
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.model.WalletKey
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.service.BitcoinService
import com.gorunjinian.metrovault.domain.service.AddressCheckResult
import com.gorunjinian.metrovault.core.crypto.SecureByteArray
import com.gorunjinian.metrovault.core.crypto.SecureSeedCache
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.data.model.PsbtDetails
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.util.concurrent.ConcurrentHashMap

/**
 * Main Wallet Manager for the application.
 * Coordinates between the UI, SecureStorage, and the BitcoinService.
 *
 * Simplified architecture:
 * - Session key derived at login (PBKDF2 + HKDF)
 * - All wallet operations use session key (<5ms each)
 * - No repeated PBKDF2 during session
 * 
 * Marked as @Stable for Compose: although this class contains mutable internal state,
 * it's a singleton whose identity doesn't change, so Compose shouldn't recompose just
 * because this instance is passed as a parameter.
 */
@Stable
class   Wallet(context: Context) {

    private val secureStorage = SecureStorage(context)
    private val bitcoinService = BitcoinService()
    private val multisigAddressService = com.gorunjinian.metrovault.domain.service.MultisigAddressService()
    private val sessionKeyManager = SessionKeyManager.getInstance()

    private val walletStates = ConcurrentHashMap<String, WalletState>()
    private val _walletMetadataList = mutableListOf<WalletMetadata>()
    private val walletListLock = Any()

    // Session-only BIP39 seeds for wallets where hasPassphrase = true
    // These are computed from user-entered passphrase and wiped when app goes to background
    // Uses SecureSeedCache instead of String map to ensure seeds can be securely wiped from memory
    private val sessionSeeds = SecureSeedCache()

    private val _wallets = MutableStateFlow<List<WalletMetadata>>(emptyList())
    val wallets: StateFlow<List<WalletMetadata>> = _wallets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var activeWalletId: String? = null

    var isDecoyMode: Boolean = false
        private set

    companion object {
        private const val TAG = "Wallet"

        const val MAX_MAIN_WALLETS = 5
        const val MAX_DECOY_WALLETS = 5

        @SuppressLint("StaticFieldLeak")
        @Volatile
        private var instance: Wallet? = null

        fun getInstance(context: Context): Wallet {
            return instance ?: synchronized(this) {
                instance ?: Wallet(context.applicationContext).also { instance = it }
            }
        }
    }

    // ==================== Session Management ====================

    /**
     * Sets the session after successful login.
     * Called by the login screen after SecureStorage.verifyPassword() succeeds.
     * Runs migration silently if needed.
     */
    fun setSession(isDecoy: Boolean) {
        this.isDecoyMode = isDecoy
        // Run silent migration if needed (one-time, after login)
        secureStorage.runMigrationIfNeeded(isDecoy)
        Log.d(TAG, "Session set: isDecoy=$isDecoy")
    }

    /**
     * Clears the session and all sensitive data.
     */
    fun clearSession() {
        isDecoyMode = false
        sessionSeeds.clear()  // Clear session-only seeds
        secureStorage.clearSession()
        unloadAllWallets()
        Log.d(TAG, "Session cleared")
    }

    /**
     * Emergency wipe - clears all sensitive data immediately.
     */
    fun emergencyWipe() {
        Log.w(TAG, "EMERGENCY WIPE - Clearing all wallet data from memory")
        sessionSeeds.clear()  // Clear session-only seeds
        clearSession()
        System.gc()
    }

    // ==================== Wallet List Operations ====================

    /**
     * Loads wallet metadata list for display.
     * Fast operation (<10ms) - no decryption needed for metadata.
     */
    suspend fun loadWalletList(): Boolean = withContext(Dispatchers.IO) {
        try {
            val list = secureStorage.loadAllWalletMetadata(isDecoyMode)
            synchronized(walletListLock) {
                _walletMetadataList.clear()
                _walletMetadataList.addAll(list)
                _wallets.value = _walletMetadataList.toList()
            }
            Log.d(TAG, "Loaded ${list.size} wallets")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet list: ${e.message}", e)
            false
        }
    }

    /**
     * Refreshes the wallet list using current session.
     */
    suspend fun refreshWallets(): Boolean = loadWalletList()

    fun canCreateWallet(): Pair<Boolean, String?> {
        val maxWallets = if (isDecoyMode) MAX_DECOY_WALLETS else MAX_MAIN_WALLETS
        val vaultName = if (isDecoyMode) "Decoy" else "Main"
        val currentCount = secureStorage.getWalletCount(isDecoyMode)

        return if (currentCount >= maxWallets) {
            Pair(false, "Maximum of $maxWallets wallets reached for $vaultName vault.")
        } else {
            Pair(true, null)
        }
    }

    // ==================== Wallet Creation ====================

    fun generateMnemonic(wordCount: Int, userEntropy: ByteArray? = null): List<String> {
        return bitcoinService.generateMnemonicWithUserEntropy(wordCount, userEntropy)
    }

    /**
     * Creates a wallet from mnemonic.
     *
     * Security model:
     * - Creates or reuses a WalletKey based on fingerprint matching
     * - Always stores BIP39 seed derived from mnemonic + passphrase (if saveLocally=true)
     *   or mnemonic + empty passphrase (if saveLocally=false)
     * - Passphrase never stored directly on disk
     * - hasPassphrase=true means passphrase entry required on wallet open
     *
     * @param savePassphraseLocally If false, only base seed (without passphrase) is saved,
     *        and hasPassphrase is set to require re-entry on open
     */
    suspend fun createWallet(
        name: String,
        mnemonic: List<String>,
        derivationPath: String,
        passphrase: String = "",
        savePassphraseLocally: Boolean = true,
        accountNumber: Int = 0
    ): WalletCreationResult = withContext(Dispatchers.IO) {
        try {
            if (!sessionKeyManager.isSessionActive.value) {
                return@withContext WalletCreationResult.Error(WalletCreationError.NOT_AUTHENTICATED)
            }

            val (canCreate, errorMsg) = canCreateWallet()
            if (!canCreate) {
                Log.e(TAG, "Cannot create wallet: $errorMsg")
                return@withContext WalletCreationResult.Error(WalletCreationError.MAX_WALLETS_REACHED)
            }

            if (!bitcoinService.validateMnemonic(mnemonic)) {
                return@withContext WalletCreationResult.Error(WalletCreationError.INVALID_MNEMONIC)
            }

            // Build full derivation path with account number
            val fullPath = DerivationPaths.withAccountNumber(derivationPath, accountNumber)

            // Create wallet with passphrase to get correct fingerprint
            val walletResult =
                bitcoinService.createWalletFromMnemonic(mnemonic, passphrase, fullPath)
                    ?: return@withContext WalletCreationResult.Error(WalletCreationError.WALLET_CREATION_FAILED)

            val walletId = java.util.UUID.randomUUID().toString()
            val mnemonicString = mnemonic.joinToString(" ")

            // Compute BIP39 seed to store
            // If savePassphraseLocally=true: store seed WITH passphrase (wallet ready to use)
            // If savePassphraseLocally=false: store seed WITHOUT passphrase (base seed, requires passphrase on open)
            val seedPassphrase = if (savePassphraseLocally) passphrase else ""
            var seedBytes: ByteArray? = null
            try {
                seedBytes =
                    com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode.toSeed(mnemonic, seedPassphrase)
                val seedHex = seedBytes.joinToString("") { "%02x".format(it) }

                // hasPassphrase = true only if passphrase used AND not saved locally
                // This triggers passphrase dialog on wallet open
                val requiresPassphraseEntry = passphrase.isNotEmpty() && !savePassphraseLocally
                val fingerprint = walletResult.fingerprint.lowercase()

                // Check if a WalletKey with this fingerprint already exists (deduplication)
                val existingKey = secureStorage.findKeyByFingerprint(fingerprint, isDecoyMode)
                val keyId: String
                
                if (existingKey != null) {
                    // Reuse existing key
                    keyId = existingKey.keyId
                    Log.d(TAG, "Reusing existing key $keyId for new wallet")
                } else {
                    // Create new WalletKey
                    keyId = java.util.UUID.randomUUID().toString()
                    val keyLabel = secureStorage.getNextKeyLabel(isDecoyMode)
                    
                    val walletKey = WalletKey(
                        keyId = keyId,
                        mnemonic = mnemonicString,
                        bip39Seed = seedHex,
                        fingerprint = fingerprint,
                        label = keyLabel
                    )
                    
                    if (!secureStorage.saveWalletKey(walletKey, isDecoyMode)) {
                        return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
                    }
                    Log.d(TAG, "Created new key $keyId ($keyLabel)")
                }

                val metadata = WalletMetadata(
                    id = walletId,
                    name = name,
                    derivationPath = fullPath,
                    masterFingerprint = walletResult.fingerprint.lowercase(),  // Normalized to lowercase
                    hasPassphrase = requiresPassphraseEntry,
                    createdAt = System.currentTimeMillis(),
                    accounts = listOf(accountNumber),
                    activeAccountNumber = accountNumber,
                    keyIds = listOf(keyId)  // Reference to WalletKey
                )

                // Save wallet metadata (key material is already saved in WalletKey above)
                if (!secureStorage.saveWalletMetadata(metadata, isDecoyMode)) {
                    return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
                }

                // Update wallet order
                val currentOrder =
                    secureStorage.loadWalletOrder(isDecoyMode)?.toMutableList() ?: mutableListOf()
                currentOrder.add(walletId)
                secureStorage.saveWalletOrder(currentOrder, isDecoyMode)

                // Update in-memory list
                synchronized(walletListLock) {
                    _walletMetadataList.add(metadata)
                    _wallets.value = _walletMetadataList.toList()
                }
                WalletCreationResult.Success(metadata)
            } finally {
                // Securely wipe seed bytes from memory
                seedBytes?.fill(0)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Create wallet failed: ${e.message}", e)
            WalletCreationResult.Error(WalletCreationError.UNKNOWN_ERROR)
        }
    }

    // ==================== Multisig Wallet Creation ====================

    /**
     * Creates a multisig wallet from a parsed configuration.
     * 
     * Multisig wallets reference existing WalletKeys via fingerprint matching.
     * At least one local key must match a cosigner fingerprint.
     * 
     * @param name Display name for the wallet
     * @param config Parsed multisig configuration from descriptor
     * @return true if wallet was created successfully
     */
    suspend fun createMultisigWallet(
        name: String,
        config: MultisigConfig
    ): Boolean = withContext(Dispatchers.IO) {
        try {
            if (!sessionKeyManager.isSessionActive.value) {
                Log.e(TAG, "Cannot create multisig wallet: not authenticated")
                return@withContext false
            }

            val (canCreate, errorMsg) = canCreateWallet()
            if (!canCreate) {
                Log.e(TAG, "Cannot create multisig wallet: $errorMsg")
                return@withContext false
            }

            // Match cosigner fingerprints to local WalletKeys
            val keyIds = mutableListOf<String>()
            val updatedCosigners = config.cosigners.map { cosigner ->
                val localKey = secureStorage.findKeyByFingerprint(cosigner.fingerprint, isDecoyMode)
                if (localKey != null) {
                    keyIds.add(localKey.keyId)
                    cosigner.copy(isLocal = true, keyId = localKey.keyId)
                } else {
                    cosigner.copy(isLocal = false, keyId = null)
                }
            }
            
            // Validate we have at least one local key (signing device app requirement)
            if (keyIds.isEmpty()) {
                Log.e(TAG, "Cannot create multisig wallet: no local keys match cosigner fingerprints")
                return@withContext false
            }

            val walletId = java.util.UUID.randomUUID().toString()

            // Use first local key's fingerprint as the wallet's fingerprint
            // Safe: keyIds is non-empty, so at least one cosigner has isLocal=true
            val localCosigner = updatedCosigners.firstOrNull { it.isLocal }
            if (localCosigner == null) {
                Log.e(TAG, "Cannot create multisig wallet: inconsistent state - keyIds present but no local cosigner")
                return@withContext false
            }
            val primaryFingerprint = localCosigner.fingerprint.lowercase()

            // Create updated config with keyId references, normalizing all fingerprints
            val updatedConfig = config.copy(
                cosigners = updatedCosigners.map { it.copy(fingerprint = it.fingerprint.lowercase()) },
                localKeyFingerprints = keyIds.mapNotNull { keyId ->
                    secureStorage.loadWalletKey(keyId, isDecoyMode)?.fingerprint?.lowercase()
                }
            )
            
            // Determine derivation path from the first cosigner (for display purposes)
            val displayPath = "multisig/${config.m}of${config.n}"

            val metadata = WalletMetadata(
                id = walletId,
                name = name,
                derivationPath = displayPath,
                masterFingerprint = primaryFingerprint,
                hasPassphrase = false,  // Multisig wallets don't have their own passphrase
                createdAt = System.currentTimeMillis(),
                accounts = listOf(0),
                activeAccountNumber = 0,
                isMultisig = true,
                multisigConfig = updatedConfig,
                keyIds = keyIds  // Direct references to WalletKeys
            )

            // Save metadata only - no secrets stored for multisig wallets
            if (!secureStorage.saveWalletMetadata(metadata, isDecoyMode)) {
                Log.e(TAG, "Failed to save multisig wallet metadata")
                return@withContext false
            }

            // Update wallet order
            val currentOrder =
                secureStorage.loadWalletOrder(isDecoyMode)?.toMutableList() ?: mutableListOf()
            currentOrder.add(walletId)
            secureStorage.saveWalletOrder(currentOrder, isDecoyMode)

            // Update in-memory list
            synchronized(walletListLock) {
                _walletMetadataList.add(metadata)
                _wallets.value = _walletMetadataList.toList()
            }
            
            Log.d(TAG, "Multisig wallet created: $walletId (${config.m}-of-${config.n}) with ${keyIds.size} local key(s)")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Create multisig wallet failed: ${e.message}", e)
            false
        }
    }

    // ==================== Wallet Loading ====================


    /**
     * Opens a wallet - loads it into memory for operations.
     * Fast operation (<10ms) - just AES decryption.
     */
    suspend fun openWallet(walletId: String, showLoading: Boolean = true): Boolean {
        // Already loaded and active
        if (walletStates.containsKey(walletId) && activeWalletId == walletId) {
            return true
        }

        // Loaded but not active
        if (walletStates.containsKey(walletId)) {
            return setActiveWallet(walletId)
        }

        // Need to load from storage
        if (showLoading) _isLoading.value = true

        return try {
            withContext(Dispatchers.IO) {
                loadWalletFull(walletId)
            }
        } finally {
            if (showLoading) _isLoading.value = false
        }
    }

    private suspend fun loadWalletFull(walletId: String): Boolean = withContext(Dispatchers.IO) {
        try {
            val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode)
                ?: return@withContext false

            // Special handling for multisig wallets - they don't have secrets
            if (metadata.isMultisig) {
                // For multisig wallets, we don't load actual wallet crypto state
                // We just need to set the wallet as active for viewing details
                // The signing will be handled by loading the corresponding single-sig wallets
                activeWalletId = walletId
                Log.d(TAG, "Multisig wallet activated: $walletId")
                return@withContext true
            }

            // Load key material from WalletKey (single-sig wallets have exactly one keyId)
            val keyId = metadata.keyIds.firstOrNull()
            if (keyId == null) {
                Log.e(TAG, "No keyId found for wallet $walletId")
                return@withContext false
            }

            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode)
            if (walletKey == null) {
                Log.e(TAG, "Failed to load WalletKey $keyId for wallet $walletId")
                return@withContext false
            }

            // Determine which BIP39 seed to use
            // If hasPassphrase=true, check for session seed (computed from user-entered passphrase)
            // Otherwise, use the stored seed directly
            val seedToUse = if (metadata.hasPassphrase) {
                // Check if user has entered passphrase this session
                sessionSeeds.get(walletId) ?: walletKey.bip39Seed  // Fallback to stored base seed
            } else {
                walletKey.bip39Seed
            }

            // Use active account's derivation path
            val derivationPath = metadata.getActiveDerivationPath()

            // Load wallet from pre-computed seed (fast - skips PBKDF2)
            val walletResult = bitcoinService.createWalletFromSeed(seedToUse, derivationPath)
                ?: return@withContext false

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
            activeWalletId = walletId
            Log.d(TAG, "Wallet loaded: $walletId (fingerprint=${walletResult.fingerprint})")
            true
        } catch (e: Exception) {
            Log.e(TAG, "Failed to load wallet: ${e.message}", e)
            false
        }
    }

    fun setActiveWallet(walletId: String): Boolean {
        return if (walletStates.containsKey(walletId)) {
            activeWalletId = walletId
            Log.d(TAG, "Active wallet set: $walletId")
            true
        } else {
            Log.w(TAG, "Cannot set active: wallet not loaded")
            false
        }
    }

    fun getActiveWalletState(): WalletState? = activeWalletId?.let { walletStates[it] }

    // ==================== Wallet Operations ====================

    suspend fun deleteWallet(walletId: String): Boolean = withContext(Dispatchers.IO) {
        // Get metadata before deletion to check keyIds
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode)
        val keyIds = metadata?.keyIds ?: emptyList()
        
        unloadWallet(walletId)
        synchronized(walletListLock) {
            _walletMetadataList.removeAll { it.id == walletId }
            _wallets.value = _walletMetadataList.toList()
        }
        
        // Delete wallet (metadata and secrets)
        val deleted = secureStorage.deleteWallet(walletId, isDecoyMode)
        
        // Clean up keys that are no longer referenced by any wallet
        for (keyId in keyIds) {
            secureStorage.deleteKeyIfUnreferenced(keyId, isDecoyMode)
        }
        
        deleted
    }

    suspend fun renameWallet(walletId: String, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = synchronized(walletListLock) {
                _walletMetadataList.find { it.id == walletId }
            } ?: return@withContext false

            val updated = metadata.copy(name = newName)
            if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
                synchronized(walletListLock) {
                    val idx = _walletMetadataList.indexOfFirst { it.id == walletId }
                    if (idx != -1) {
                        _walletMetadataList[idx] = updated
                        _wallets.value = _walletMetadataList.toList()
                    }
                }
                walletStates[walletId]?.rename(newName)
                true
            } else false
        }

    fun swapWallets(index1: Int, index2: Int) {
        synchronized(walletListLock) {
            if (index1 in _walletMetadataList.indices && index2 in _walletMetadataList.indices) {
                val temp = _walletMetadataList[index1]
                _walletMetadataList[index1] = _walletMetadataList[index2]
                _walletMetadataList[index2] = temp
                _wallets.value = _walletMetadataList.toList()
            }
        }
    }

    suspend fun saveWalletListOrder(): Boolean = withContext(Dispatchers.IO) {
        val orderedIds = synchronized(walletListLock) {
            _walletMetadataList.map { it.id }
        }
        secureStorage.saveWalletOrder(orderedIds, isDecoyMode)
    }

    // ==================== Account Management ====================

    /** Add a new account number to a wallet. Returns false if already exists. */
    suspend fun addAccountToWallet(walletId: String, accountNumber: Int): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = synchronized(walletListLock) {
                _walletMetadataList.find { it.id == walletId }
            } ?: return@withContext false

            if (metadata.accounts.contains(accountNumber)) return@withContext false

            val updated = metadata.copy(
                accounts = (metadata.accounts + accountNumber).sorted()
            )

            if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
                synchronized(walletListLock) {
                    val idx = _walletMetadataList.indexOfFirst { it.id == walletId }
                    if (idx >= 0) {
                        _walletMetadataList[idx] = updated
                        _wallets.value = _walletMetadataList.toList()
                    }
                }
                Log.d(TAG, "Added account $accountNumber to wallet $walletId")
                true
            } else false
        }

    /**
     * Remove an account from a wallet.
     * Returns false if: account doesn't exist, is currently active, or is the last account.
     */
    suspend fun removeAccountFromWallet(walletId: String, accountNumber: Int): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = synchronized(walletListLock) {
                _walletMetadataList.find { it.id == walletId }
            } ?: return@withContext false

            // Cannot remove account that doesn't exist
            if (!metadata.accounts.contains(accountNumber)) return@withContext false

            // Cannot remove the currently active account
            if (metadata.activeAccountNumber == accountNumber) return@withContext false

            // Cannot remove the last remaining account
            if (metadata.accounts.size <= 1) return@withContext false

            val updated = metadata.copy(
                accounts = metadata.accounts.filter { it != accountNumber }
            )

            if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
                synchronized(walletListLock) {
                    val idx = _walletMetadataList.indexOfFirst { it.id == walletId }
                    if (idx >= 0) {
                        _walletMetadataList[idx] = updated
                        _wallets.value = _walletMetadataList.toList()
                    }
                }
                Log.d(TAG, "Removed account $accountNumber from wallet $walletId")
                true
            } else false
        }

    /** Switch active account. Reloads wallet with new derivation path. */
    suspend fun switchActiveAccount(walletId: String, accountNumber: Int): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = synchronized(walletListLock) {
                _walletMetadataList.find { it.id == walletId }
            } ?: return@withContext false

            if (!metadata.accounts.contains(accountNumber)) return@withContext false
            if (metadata.activeAccountNumber == accountNumber) return@withContext true

            val updated = metadata.copy(activeAccountNumber = accountNumber)

            if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
                synchronized(walletListLock) {
                    val idx = _walletMetadataList.indexOfFirst { it.id == walletId }
                    if (idx >= 0) {
                        _walletMetadataList[idx] = updated
                        _wallets.value = _walletMetadataList.toList()
                    }
                }
                // Reload wallet with new account's path
                unloadWallet(walletId)
                val reloaded = openWallet(walletId)
                Log.d(TAG, "Switched to account $accountNumber: reload=$reloaded")
                reloaded
            } else false
        }


    /** Get active account number for active wallet */
    fun getActiveAccountNumber(): Int {
        val walletId = activeWalletId ?: return 0
        return synchronized(walletListLock) {
            _walletMetadataList.find { it.id == walletId }?.activeAccountNumber ?: 0
        }
    }

    /**
     * Rename an account's display name.
     * Pass empty string or default name ("Account N") to remove custom name.
     */
    suspend fun renameAccount(walletId: String, accountNumber: Int, newName: String): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = synchronized(walletListLock) {
                _walletMetadataList.find { it.id == walletId }
            } ?: return@withContext false

            // Account must exist
            if (!metadata.accounts.contains(accountNumber)) return@withContext false

            // Determine if we should store the name or use default
            val defaultName = "Account $accountNumber"
            val trimmedName = newName.trim()

            val updatedNames = if (trimmedName.isEmpty() || trimmedName == defaultName) {
                // Remove custom name, use default
                metadata.accountNames - accountNumber
            } else {
                // Store custom name
                metadata.accountNames + (accountNumber to trimmedName)
            }

            val updated = metadata.copy(accountNames = updatedNames)

            if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
                synchronized(walletListLock) {
                    val idx = _walletMetadataList.indexOfFirst { it.id == walletId }
                    if (idx >= 0) {
                        _walletMetadataList[idx] = updated
                        _wallets.value = _walletMetadataList.toList()
                    }
                }
                Log.d(TAG, "Renamed account $accountNumber to '$trimmedName' in wallet $walletId")
                true
            } else false
        }

    // ==================== Memory Management ====================

    fun unloadWallet(walletId: String) {
        walletStates[walletId]?.wipe()
        walletStates.remove(walletId)
        if (activeWalletId == walletId) activeWalletId = null
    }

    /**
     * Wipe all wallet keys from memory but keep metadata list.
     * Use when navigating away from wallet screens (security).
     * Resets RAM to same state as fresh app launch.
     */
    fun unloadAllWalletKeys() {
        val count = walletStates.size
        val seedCount = sessionSeeds.size
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        sessionSeeds.clear()  // Clear passphrase-derived seeds
        activeWalletId = null
        Log.d(
            TAG,
            "Wiped $count wallet key(s) + $seedCount session seed(s) from memory (HomeScreen navigation)"
        )
    }

    /**
     * Full session wipe - clears both keys AND metadata.
     * Use for lock/logout/emergency wipe.
     */
    fun unloadAllWallets() {
        val keyCount = walletStates.size
        val metadataCount = _walletMetadataList.size
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        synchronized(walletListLock) {
            _walletMetadataList.clear()
            _wallets.value = emptyList()
        }
        activeWalletId = null
        Log.d(
            TAG,
            "Full session wipe: $keyCount keys + $metadataCount metadata cleared"
        )
    }

    // ==================== Bitcoin Operations ====================

    /** Check if the active wallet is a testnet wallet */
    fun isActiveWalletTestnet(): Boolean {
        val walletId = activeWalletId ?: return false
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false

        // For multisig wallets, check the config's isTestnet method
        if (metadata.isMultisig && metadata.multisigConfig != null) {
            return metadata.multisigConfig.isTestnet()
        }

        // For single-sig wallets, check derivation path
        val path = getActiveWalletDerivationPath()
        return DerivationPaths.isTestnet(path)
    }

    /**
     * Result of PSBT signing operation.
     */
    sealed class PsbtSigningResult {
        /**
         * Signing succeeded.
         * @property signedPsbt The signed PSBT in base64 format
         * @property alternativePathsUsed List of alternative derivation paths used (for diagnostics)
         */
        data class Success(
            val signedPsbt: String,
            val alternativePathsUsed: List<String> = emptyList()
        ) : PsbtSigningResult()

        /**
         * Signing failed with a specific error.
         * @property error The error type
         * @property message Human-readable error message
         */
        data class Failure(
            val error: SigningError,
            val message: String
        ) : PsbtSigningResult()
    }

    /**
     * Specific error types for PSBT signing failures.
     */
    enum class SigningError {
        /** No wallet is currently active/loaded */
        NO_ACTIVE_WALLET,
        /** Wallet state is not loaded (keys not in memory) */
        WALLET_NOT_LOADED,
        /** Multisig wallet has no local keys configured */
        NO_LOCAL_KEYS,
        /** Failed to load key material from storage */
        KEY_LOAD_FAILED,
        /** Multisig config is missing or invalid */
        INVALID_MULTISIG_CONFIG,
        /** PSBT signing operation failed (no inputs could be signed) */
        SIGNING_FAILED,
        /** Key derivation failed */
        KEY_DERIVATION_FAILED
    }

    fun signPsbt(psbtString: String): PsbtSigningResult {
        val activeMetadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        if (activeMetadata == null) {
            return PsbtSigningResult.Failure(
                SigningError.NO_ACTIVE_WALLET,
                "No wallet is currently active. Please open a wallet first."
            )
        }

        val isMultisig = activeMetadata.isMultisig
        val allAlternativePathsUsed = mutableListOf<String>()

        if (isMultisig) {
            // Multisig wallet: iterate through local keys referenced by this wallet.
            val keyIds = activeMetadata.keyIds
            Log.d(TAG, "Multisig signing: keyIds=$keyIds")
            if (keyIds.isEmpty()) {
                Log.e(TAG, "No keyIds found for multisig wallet")
                return PsbtSigningResult.Failure(
                    SigningError.NO_LOCAL_KEYS,
                    "This multisig wallet has no local signing keys. Import a key that matches one of the cosigner fingerprints."
                )
            }

            val config = activeMetadata.multisigConfig
            if (config == null) {
                Log.e(TAG, "No multisigConfig found")
                return PsbtSigningResult.Failure(
                    SigningError.INVALID_MULTISIG_CONFIG,
                    "Multisig configuration is missing. Re-import the wallet descriptor."
                )
            }

            var signedPsbt = psbtString
            var signedCount = 0
            val failedKeys = mutableListOf<String>()

            // Find wallets that use these keys
            for (keyId in keyIds) {
                Log.d(TAG, "Processing keyId: $keyId")
                val key = secureStorage.loadWalletKey(keyId, isDecoyMode)
                if (key == null) {
                    Log.e(TAG, "Failed to load WalletKey for keyId: $keyId")
                    failedKeys.add(keyId)
                    continue
                }
                Log.d(TAG, "Loaded key: fingerprint=${key.fingerprint}, seedLen=${key.bip39Seed.length}")

                // Find a single-sig wallet with this key to get the derivation path
                // Check already-loaded wallet states first
                val matchingState = walletStates.entries.find { (id, _) ->
                    val meta = secureStorage.loadWalletMetadata(id, isDecoyMode)
                    meta?.keyIds?.contains(keyId) == true && !meta.isMultisig
                }?.value

                if (matchingState != null) {
                    val masterPrivateKey = matchingState.getMasterPrivateKey() ?: continue
                    val accountPrivateKey = matchingState.getAccountPrivateKey() ?: continue
                    val scriptType = getScriptType(matchingState.derivationPath)
                    val isTestnet = DerivationPaths.isTestnet(matchingState.derivationPath)

                    val signingResult = bitcoinService.signPsbt(
                        signedPsbt,
                        masterPrivateKey,
                        accountPrivateKey,
                        scriptType,
                        isTestnet
                    )

                    if (signingResult != null) {
                        signedPsbt = signingResult.signedPsbt
                        allAlternativePathsUsed.addAll(signingResult.alternativePathsUsed)
                        signedCount++
                        Log.d(TAG, "Successfully signed portion of PSBT with key: $keyId")
                    }
                } else {
                    // Key exists but no wallet state loaded - try to create temporary signing state
                    // Use the key's mnemonic to sign directly with the multisig cosigner derivation path
                    Log.d(TAG, "No matching wallet state, trying direct signing for key: ${key.fingerprint}")
                    val cosigner = config.cosigners.find {
                        it.fingerprint.equals(key.fingerprint, ignoreCase = true)
                    }
                    if (cosigner == null) {
                        Log.e(TAG, "No cosigner found matching fingerprint: ${key.fingerprint}")
                        Log.d(TAG, "Available cosigners: ${config.cosigners.map { it.fingerprint }}")
                        continue
                    }

                    // Parse derivation path from cosigner (ensure m/ prefix)
                    val rawPath = cosigner.derivationPath
                    val derivationPath = if (rawPath.startsWith("m/")) rawPath else "m/$rawPath"
                    Log.d(TAG, "Using derivation path: $derivationPath (raw: $rawPath)")

                    // Create wallet from the key's seed
                    val walletResult = bitcoinService.createWalletFromSeed(key.bip39Seed, derivationPath)
                    if (walletResult != null) {
                        val scriptType = getScriptType(derivationPath)
                        val isTestnet = DerivationPaths.isTestnet(derivationPath)

                        val signingResult = bitcoinService.signPsbt(
                            signedPsbt,
                            walletResult.masterPrivateKey,
                            walletResult.accountPrivateKey,
                            scriptType,
                            isTestnet
                        )

                        if (signingResult != null) {
                            signedPsbt = signingResult.signedPsbt
                            allAlternativePathsUsed.addAll(signingResult.alternativePathsUsed)
                            signedCount++
                            Log.d(TAG, "Successfully signed portion of PSBT with key: $keyId (direct)")
                        }
                    } else {
                        Log.e(TAG, "Failed to derive wallet from key: ${key.fingerprint}")
                    }
                }
            }

            return if (signedCount > 0) {
                Log.d(TAG, "Multisig signing complete: $signedCount/${keyIds.size} keys signed")
                PsbtSigningResult.Success(signedPsbt, allAlternativePathsUsed)
            } else {
                val errorMsg = if (failedKeys.isNotEmpty()) {
                    "Failed to load ${failedKeys.size} key(s). Ensure the wallet is properly loaded."
                } else {
                    "No inputs could be signed. The PSBT may not contain inputs for your keys."
                }
                PsbtSigningResult.Failure(SigningError.SIGNING_FAILED, errorMsg)
            }
        } else {
            // Single-sig wallet
            val state = getActiveWalletState()
            if (state == null) {
                return PsbtSigningResult.Failure(
                    SigningError.WALLET_NOT_LOADED,
                    "Wallet is not loaded. Please open the wallet first."
                )
            }

            val masterPrivateKey = state.getMasterPrivateKey()
            val accountPrivateKey = state.getAccountPrivateKey()
            if (masterPrivateKey == null || accountPrivateKey == null) {
                return PsbtSigningResult.Failure(
                    SigningError.KEY_DERIVATION_FAILED,
                    "Failed to access wallet keys. Try reloading the wallet."
                )
            }

            val scriptType = getScriptType(state.derivationPath)
            val isTestnet = isActiveWalletTestnet()
            val signingResult = bitcoinService.signPsbt(psbtString, masterPrivateKey, accountPrivateKey, scriptType, isTestnet)

            return if (signingResult != null) {
                PsbtSigningResult.Success(signingResult.signedPsbt, signingResult.alternativePathsUsed)
            } else {
                PsbtSigningResult.Failure(
                    SigningError.SIGNING_FAILED,
                    "Failed to sign PSBT. The transaction may not contain inputs for this wallet."
                )
            }
        }
    }

    fun getPsbtDetails(psbtString: String): PsbtDetails? {
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getPsbtDetails(psbtString, isTestnet)
    }

    fun generateAddresses(
        count: Int,
        offset: Int = 0,
        isChange: Boolean = false
    ): List<BitcoinAddress>? {
        val walletId = activeWalletId ?: return null
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return null
        val isTestnet = isActiveWalletTestnet()
        
        // Handle multisig wallets using MultisigAddressService
        if (metadata.isMultisig && metadata.multisigConfig != null) {
            return (0 until count).mapNotNull { i ->
                when (val result = multisigAddressService.generateMultisigAddress(
                    config = metadata.multisigConfig,
                    index = offset + i,
                    isChange = isChange,
                    isTestnet = isTestnet
                )) {
                    is com.gorunjinian.metrovault.domain.service.MultisigAddressService.MultisigAddressResult.Success -> result.address
                    is com.gorunjinian.metrovault.domain.service.MultisigAddressService.MultisigAddressResult.Error -> null
                }
            }
        }
        
        // Regular single-sig wallet
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return (0 until count).mapNotNull { i ->
            bitcoinService.generateAddress(accountPublicKey, offset + i, isChange, scriptType, isTestnet)
        }
    }

    fun checkAddressBelongsToWallet(address: String): AddressCheckResult? {
        val walletId = activeWalletId ?: return null
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return null
        val isTestnet = isActiveWalletTestnet()
        
        // Handle multisig wallets using MultisigAddressService
        if (metadata.isMultisig && metadata.multisigConfig != null) {
            return multisigAddressService.checkAddressBelongsToMultisig(
                address = address,
                config = metadata.multisigConfig,
                isTestnet = isTestnet
            )
        }
        
        // Regular single-sig wallet
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return bitcoinService.checkAddressBelongsToWallet(address, accountPublicKey, scriptType, isTestnet)
    }

    // ==================== Wallet Info ====================

    fun getActiveWalletName(): String {
        // Try wallet state first, then fallback to metadata for multisig wallets
        val state = getActiveWalletState()
        if (state != null) return state.name
        
        // Fallback to metadata for multisig wallets
        val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        return metadata?.name ?: "No Wallet"
    }

    fun getActiveWalletDerivationPath(): String {
        // Try wallet state first, then fallback to metadata for multisig wallets
        val state = getActiveWalletState()
        if (state != null) return state.derivationPath
        
        // Fallback to metadata derivation path for multisig wallets
        val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        return metadata?.derivationPath ?: ""
    }

    /**
     * Gets the extended public key (xpub/ypub/zpub) for a specific account number.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive key for
     * @return Extended public key string, or empty string on error
     */
    fun getXpubForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val accountPath = DerivationPaths.withAccountNumber(baseDerivationPath, accountNumber)
        val scriptType = getScriptType(accountPath)
        val isTestnet = isActiveWalletTestnet()
        
        return try {
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(accountPath)
            val accountPublicKey = accountPrivateKey.extendedPublicKey
            bitcoinService.getAccountXpub(accountPublicKey, scriptType, isTestnet)
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Gets the extended private key (xprv/yprv/zprv) for a specific account number.
     * WARNING: Contains private keys - handle with extreme care!
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive key for
     * @return Extended private key string, or empty string on error
     */
    fun getXprivForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val accountPath = DerivationPaths.withAccountNumber(baseDerivationPath, accountNumber)
        val scriptType = getScriptType(accountPath)
        val isTestnet = isActiveWalletTestnet()
        
        return try {
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(accountPath)
            bitcoinService.getAccountXpriv(accountPrivateKey, scriptType, isTestnet)
        } catch (_: Exception) {
            ""
        }
    }

    @Suppress("unused")
    fun getActiveDescriptor(): Pair<String, String>? {
        val state = getActiveWalletState() ?: return null
        val masterPrivateKey = state.getMasterPrivateKey() ?: return null
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getWalletDescriptors(masterPrivateKey, isTestnet)
    }

    /**
     * Gets the unified output descriptor (public/watch-only) for a specific account number.
     * Uses multipath syntax compatible with Sparrow, Bitcoin Core, etc.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive descriptor for
     * @return Descriptor string with checksum, or empty string on error
     */
    fun getUnifiedDescriptorForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val accountPath = DerivationPaths.withAccountNumber(baseDerivationPath, accountNumber)
        val scriptType = getScriptType(accountPath)
        val isTestnet = isActiveWalletTestnet()
        
        return try {
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(accountPath)
            val accountPublicKey = accountPrivateKey.extendedPublicKey
            
            bitcoinService.getWalletDescriptor(
                fingerprint = fingerprint,
                accountPath = accountPath,
                accountPublicKey = accountPublicKey,
                scriptType = scriptType,
                isTestnet = isTestnet
            )
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Gets the private (spending) descriptor for a specific account number.
     * WARNING: Contains private keys - handle with extreme care!
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive descriptor for
     * @return Private descriptor string with checksum, or empty string on error
     */
    fun getPrivateDescriptorForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val accountPath = DerivationPaths.withAccountNumber(baseDerivationPath, accountNumber)
        val scriptType = getScriptType(accountPath)
        val isTestnet = isActiveWalletTestnet()
        
        return try {
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(accountPath)
            
            bitcoinService.getPrivateWalletDescriptor(
                fingerprint = fingerprint,
                accountPath = accountPath,
                accountPrivateKey = accountPrivateKey,
                scriptType = scriptType,
                isTestnet = isTestnet
            )
        } catch (_: Exception) {
            ""
        }
    }

    /**
     * Gets the BIP32 root key (master private key at depth 0).
     * WARNING: This is the root of all derived keys - handle with extreme care!
     *
     * @return Master private key as xprv (mainnet) or tprv (testnet), or empty string on error
     */
    fun getBIP32RootKey(): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val isTestnet = isActiveWalletTestnet()

        return try {
            masterPrivateKey.encode(isTestnet)
        } catch (_: Exception) {
            ""
        }
    }

    fun getMasterFingerprint(): String? {
        // Try wallet state first, then fallback to metadata for multisig wallets
        val state = getActiveWalletState()
        if (state != null) return state.fingerprint
        
        // Fallback to metadata for multisig wallets
        val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        return metadata?.masterFingerprint
    }

    fun getActiveWalletId(): String? = activeWalletId

    fun getActiveDerivationPath(): String? {
        // Try wallet state first, then fallback to metadata for multisig wallets
        val state = getActiveWalletState()
        if (state != null) return state.derivationPath
        
        // Fallback to metadata for multisig wallets
        val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        return metadata?.derivationPath
    }

    fun getActiveMnemonic(): List<String>? {
        return try {
            val state = getActiveWalletState() ?: return null
            val mnemonic = state.getMnemonic() ?: return null
            mnemonic.asString().split(" ")
        } catch (_: Exception) {
            Log.e(TAG, "Failed to retrieve mnemonic")
            null
        }
    }

    /**
     * Gets the public and private key for a specific address.
     * @param index Address index
     * @param isChange Whether this is a change address
     * @return AddressKeyPair with public key (hex) and private key (WIF), or null on error
     */
    fun getAddressKeys(index: Int, isChange: Boolean): BitcoinService.AddressKeyPair? {
        val state = getActiveWalletState() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getAddressKeys(accountPrivateKey, index, isChange, isTestnet)
    }

    // ==================== Session Seed Management ====================

    /**
     * Checks if a wallet needs passphrase re-entry.
     * This happens when hasPassphrase=true and we don't have a session seed.
     */
    fun needsPassphraseInput(walletId: String): Boolean {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false
        return metadata.hasPassphrase && !sessionSeeds.containsKey(walletId)
    }

    /**
     * Sets the session seed for a wallet by computing it from the entered passphrase.
     * Used when user re-enters passphrase after app restart.
     *
     * @param walletId Wallet ID
     * @param passphrase User-entered passphrase
     */
    suspend fun setSessionPassphrase(walletId: String, passphrase: String) = withContext(Dispatchers.IO) {
        // Load mnemonic for this wallet via WalletKey
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return@withContext
        val keyId = metadata.keyIds.firstOrNull() ?: return@withContext
        val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext
        val mnemonic = walletKey.mnemonic.split(" ")

        // Compute BIP39 seed from mnemonic + passphrase
        val seedBytes = com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode.toSeed(mnemonic, passphrase)
        try {
            val seedHex = seedBytes.joinToString("") { "%02x".format(it) }

            // Store in session (SecureSeedCache ensures old seeds are wiped before replacement)
            sessionSeeds.store(walletId, seedHex)
            Log.d(TAG, "Session seed set for wallet: $walletId")
        } finally {
            // Wipe seed bytes from memory
            seedBytes.fill(0)
        }
    }

    /**
     * Calculates master fingerprint from mnemonic and passphrase.
     * Used for real-time fingerprint preview in passphrase dialogs.
     */
    fun calculateFingerprint(mnemonic: List<String>, passphrase: String): String? {
        return bitcoinService.calculateFingerprint(mnemonic, passphrase)
    }

    /**
     * Gets the mnemonic for a wallet by ID (for passphrase re-entry dialog).
     * This requires loading the WalletKey from storage.
     */
    suspend fun getMnemonicForWallet(walletId: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return@withContext null
            val keyId = metadata.keyIds.firstOrNull() ?: return@withContext null
            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext null
            walletKey.mnemonic.split(" ")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic for wallet: ${e.message}")
            null
        }
    }

    // ==================== Helpers ====================

    private fun getScriptType(path: String): ScriptType = DerivationPaths.getScriptType(path)
}