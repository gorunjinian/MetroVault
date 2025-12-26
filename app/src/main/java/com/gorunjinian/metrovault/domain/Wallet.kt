package com.gorunjinian.metrovault.domain

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.WalletCreationError
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletSecrets
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.service.BitcoinService
import com.gorunjinian.metrovault.domain.service.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.PsbtDetails
import com.gorunjinian.metrovault.core.crypto.SecureByteArray
import com.gorunjinian.metrovault.core.crypto.SecureSeedCache
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
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
class Wallet(context: Context) {

    private val secureStorage = SecureStorage(context)
    private val bitcoinService = BitcoinService()
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
     */
    fun setSession(isDecoy: Boolean) {
        this.isDecoyMode = isDecoy
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
            val seedBytes =
                com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode.toSeed(mnemonic, seedPassphrase)
            val seedHex = seedBytes.joinToString("") { "%02x".format(it) }

            // hasPassphrase = true only if passphrase used AND not saved locally
            // This triggers passphrase dialog on wallet open
            val requiresPassphraseEntry = passphrase.isNotEmpty() && !savePassphraseLocally

            val metadata = WalletMetadata(
                id = walletId,
                name = name,
                derivationPath = fullPath,
                masterFingerprint = walletResult.fingerprint,  // Always the fingerprint WITH passphrase
                hasPassphrase = requiresPassphraseEntry,
                createdAt = System.currentTimeMillis(),
                accounts = listOf(accountNumber),
                activeAccountNumber = accountNumber
            )

            val secrets = WalletSecrets(
                mnemonic = mnemonicString,
                bip39Seed = seedHex
            )

            // Save to storage
            if (!secureStorage.saveWalletMetadata(metadata, isDecoyMode)) {
                return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
            }
            if (!secureStorage.saveWalletSecrets(walletId, secrets, isDecoyMode)) {
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
        } catch (e: Exception) {
            Log.e(TAG, "Create wallet failed: ${e.message}", e)
            WalletCreationResult.Error(WalletCreationError.UNKNOWN_ERROR)
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
            val secrets = secureStorage.loadWalletSecrets(walletId, isDecoyMode)
                ?: return@withContext false

            // Determine which BIP39 seed to use
            // If hasPassphrase=true, check for session seed (computed from user-entered passphrase)
            // Otherwise, use the stored seed directly
            val seedToUse = if (metadata.hasPassphrase) {
                // Check if user has entered passphrase this session
                sessionSeeds.get(walletId) ?: secrets.bip39Seed  // Fallback to stored base seed
            } else {
                secrets.bip39Seed
            }

            // Use active account's derivation path
            val derivationPath = metadata.getActiveDerivationPath()

            // Load wallet from pre-computed seed (fast - skips PBKDF2)
            val walletResult = bitcoinService.createWalletFromSeed(seedToUse, derivationPath)
                ?: return@withContext false

            // Create secure mnemonic storage for in-memory use
            val mnemonicBytes = secrets.mnemonic.toByteArray()
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
        unloadWallet(walletId)
        synchronized(walletListLock) {
            _walletMetadataList.removeAll { it.id == walletId }
            _wallets.value = _walletMetadataList.toList()
        }
        secureStorage.deleteWallet(walletId, isDecoyMode)
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
        val path = getActiveWalletDerivationPath()
        return DerivationPaths.isTestnet(path)
    }

    fun signPsbt(psbtString: String): String? {
        val state = getActiveWalletState() ?: return null
        val masterPrivateKey = state.getMasterPrivateKey() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.signPsbt(psbtString, masterPrivateKey, accountPrivateKey, scriptType, isTestnet)
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
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        return (0 until count).mapNotNull { i ->
            bitcoinService.generateAddress(accountPublicKey, offset + i, isChange, scriptType, isTestnet)
        }
    }

    fun checkAddressBelongsToWallet(address: String): AddressCheckResult? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.checkAddressBelongsToWallet(address, accountPublicKey, scriptType, isTestnet)
    }

    // ==================== Wallet Info ====================

    fun getActiveWalletName() = getActiveWalletState()?.name ?: "No Wallet"

    fun getActiveWalletDerivationPath() = getActiveWalletState()?.derivationPath ?: ""

    fun getActiveXpub(): String? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getAccountXpub(accountPublicKey, scriptType, isTestnet)
    }

    fun getActiveXpriv(): String? {
        val state = getActiveWalletState() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getAccountXpriv(accountPrivateKey, scriptType, isTestnet)
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
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
     * Gets the unified output descriptor (public/watch-only) for the active wallet.
     * Uses multipath syntax compatible with Sparrow, Bitcoin Core, etc.
     *
     * @return Descriptor string with checksum
     */
    fun getActiveUnifiedDescriptor(): String? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val fingerprint = state.fingerprint
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        
        return bitcoinService.getWalletDescriptor(
            fingerprint = fingerprint,
            accountPath = state.derivationPath,
            accountPublicKey = accountPublicKey,
            scriptType = scriptType,
            isTestnet = isTestnet
        )
    }

    /**
     * Gets the private (spending) descriptor for the active wallet.
     * WARNING: Contains private keys - handle with extreme care!
     * Uses multipath syntax compatible with Sparrow, Bitcoin Core, etc.
     *
     * @return Private descriptor string with checksum
     */
    fun getActivePrivateDescriptor(): String? {
        val state = getActiveWalletState() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val fingerprint = state.fingerprint
        val scriptType = getScriptType(state.derivationPath)
        val isTestnet = isActiveWalletTestnet()
        
        return bitcoinService.getPrivateWalletDescriptor(
            fingerprint = fingerprint,
            accountPath = state.derivationPath,
            accountPrivateKey = accountPrivateKey,
            scriptType = scriptType,
            isTestnet = isTestnet
        )
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
        } catch (e: Exception) {
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
        } catch (e: Exception) {
            ""
        }
    }

    fun getMasterFingerprint() = getActiveWalletState()?.fingerprint

    fun getActiveWalletId(): String? = activeWalletId

    fun getActiveDerivationPath(): String? = getActiveWalletState()?.derivationPath

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
        // Load mnemonic for this wallet
        val secrets = secureStorage.loadWalletSecrets(walletId, isDecoyMode) ?: return@withContext
        val mnemonic = secrets.mnemonic.split(" ")

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
     * This requires loading secrets from storage.
     */
    suspend fun getMnemonicForWallet(walletId: String): List<String>? = withContext(Dispatchers.IO) {
        try {
            val secrets = secureStorage.loadWalletSecrets(walletId, isDecoyMode) ?: return@withContext null
            secrets.mnemonic.split(" ")
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get mnemonic for wallet: ${e.message}")
            null
        }
    }

    // ==================== Helpers ====================

    private fun getScriptType(path: String): ScriptType = DerivationPaths.getScriptType(path)
}