package com.gorunjinian.metrovault.domain

import android.annotation.SuppressLint
import android.content.Context
import android.util.Log
import androidx.compose.runtime.Stable
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.WalletCreationError
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.metrovault.domain.service.bitcoin.KeyEncodingService
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.domain.service.psbt.PsbtService
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletSigningService
import com.gorunjinian.metrovault.data.repository.WalletRepository
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
    private val multisigAddressService = MultisigAddressService()
    private val sessionKeyManager = SessionKeyManager.getInstance()

    // Managers functionality for better separation of concerns
    private val passphraseManager = com.gorunjinian.metrovault.domain.manager.PassphraseManager(secureStorage, bitcoinService)
    private val sessionManager = com.gorunjinian.metrovault.domain.manager.WalletSessionManager(secureStorage, passphraseManager)
    private val accountManager = com.gorunjinian.metrovault.domain.manager.WalletAccountManager(secureStorage)
    private val signingService = WalletSigningService(secureStorage, bitcoinService)
    private val keyEncodingService = KeyEncodingService()

    private val walletStates = ConcurrentHashMap<String, WalletState>()
    private val _walletMetadataList = mutableListOf<WalletMetadata>()
    private val walletListLock = Any()

    private val _wallets = MutableStateFlow<List<WalletMetadata>>(emptyList())
    val wallets: StateFlow<List<WalletMetadata>> = _wallets.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var activeWalletId: String? = null

    // Repository for wallet CRUD and state management
    private var walletRepository: WalletRepository

    // isDecoyMode now delegated to sessionManager
    var isDecoyMode: Boolean
        get() = sessionManager.isDecoyMode
        private set(_) { /* no-op, managed by sessionManager */ }

    init {
        // Initialize repository with shared state
        // Pass a lambda for isDecoyMode since it's dynamic (depends on session state)
        walletRepository = WalletRepository(
            secureStorage = secureStorage,
            bitcoinService = bitcoinService,
            passphraseManager = passphraseManager,
            walletStates = walletStates,
            walletMetadataList = _walletMetadataList,
            walletsFlow = _wallets,
            walletListLock = walletListLock,
            getIsDecoyMode = { isDecoyMode }
        )
    }

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
     * Runs migration silently on background thread if needed.
     */
    suspend fun setSession(isDecoy: Boolean) {
        sessionManager.setSession(isDecoy)
    }

    /**
     * Clears the session and all sensitive data.
     */
    fun clearSession() {
        sessionManager.clearSession { unloadAllWallets() }
    }

    /**
     * Emergency wipe - clears all sensitive data immediately.
     */
    fun emergencyWipe() {
        sessionManager.emergencyWipe { clearSession() }
    }

    // ==================== Wallet List Operations ====================

    /**
     * Loads wallet metadata list for display.
     * Fast operation (<10ms) - no decryption needed for metadata.
     * Delegates to WalletRepository.
     */
    suspend fun loadWalletList(): Boolean = walletRepository.loadWalletList()

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
                val seedHex = seedBytes.toHexString()

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
                    
                    val walletKeys = WalletKeys(
                        keyId = keyId,
                        mnemonic = mnemonicString,
                        bip39Seed = seedHex,
                        fingerprint = fingerprint,
                        label = keyLabel
                    )
                    
                    if (!secureStorage.saveWalletKey(walletKeys, isDecoyMode)) {
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
                
                // Refresh multisig key bindings in case this new key matches a cosigner
                val updatedMultisigs = secureStorage.refreshMultisigKeyBindings(isDecoyMode)
                if (updatedMultisigs > 0) {
                    // Reload wallet list to reflect updated multisig bindings
                    loadWalletList()
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
     * Delegates to WalletRepository for loading logic.
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
            val (success, _) = walletRepository.loadWalletFull(walletId)
            if (success) {
                activeWalletId = walletId
            }
            success
        } finally {
            if (showLoading) _isLoading.value = false
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

    fun getActiveWalletState(): WalletState? = walletRepository.getActiveWalletState(activeWalletId)

    // ==================== Wallet Operations ====================

    suspend fun deleteWallet(walletId: String): Boolean = walletRepository.deleteWallet(walletId)

    suspend fun renameWallet(walletId: String, newName: String): Boolean =
        walletRepository.renameWallet(walletId, newName)

    fun swapWallets(index1: Int, index2: Int) = walletRepository.swapWallets(index1, index2)

    suspend fun saveWalletListOrder(): Boolean = walletRepository.saveWalletListOrder()

    // ==================== Account Management (delegated to WalletAccountManager) ====================

    /** Add a new account number to a wallet. Returns false if already exists. */
    suspend fun addAccountToWallet(walletId: String, accountNumber: Int): Boolean =
        accountManager.addAccount(
            walletId, accountNumber, isDecoyMode, 
            _walletMetadataList, walletListLock, _wallets
        )

    /**
     * Remove an account from a wallet.
     * Returns false if: account doesn't exist, is currently active, or is the last account.
     */
    suspend fun removeAccountFromWallet(walletId: String, accountNumber: Int): Boolean =
        accountManager.removeAccount(
            walletId, accountNumber, isDecoyMode,
            _walletMetadataList, walletListLock, _wallets
        )

    /** Switch active account. Reloads wallet with new derivation path. */
    suspend fun switchActiveAccount(walletId: String, accountNumber: Int): Boolean =
        accountManager.switchAccount(
            walletId, accountNumber, isDecoyMode,
            _walletMetadataList, walletListLock, _wallets,
            onUnloadWallet = { unloadWallet(it) },
            onOpenWallet = { openWallet(it) }
        )

    /** Get active account number for active wallet */
    fun getActiveAccountNumber(): Int =
        accountManager.getActiveAccountNumber(activeWalletId, _walletMetadataList, walletListLock)

    /**
     * Rename an account's display name.
     * Pass empty string or default name ("Account N") to remove custom name.
     */
    suspend fun renameAccount(walletId: String, accountNumber: Int, newName: String): Boolean =
        accountManager.renameAccount(
            walletId, accountNumber, newName, isDecoyMode,
            _walletMetadataList, walletListLock, _wallets
        )

    // ==================== Memory Management ====================

    fun unloadWallet(walletId: String) {
        walletRepository.unloadWallet(walletId)
        if (activeWalletId == walletId) activeWalletId = null
    }

    /**
     * Wipe all wallet keys from memory but keep metadata list.
     * Use when navigating away from wallet screens (security).
     * Resets RAM to same state as fresh app launch.
     * Delegates to WalletRepository.
     */
    fun unloadAllWalletKeys() {
        walletRepository.unloadAllWalletKeys()
        activeWalletId = null
    }

    /**
     * Full session wipe - clears both keys AND metadata.
     * Use for lock/logout/emergency wipe.
     * Delegates to WalletRepository.
     */
    fun unloadAllWallets() {
        walletRepository.unloadAllWallets()
        activeWalletId = null
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

    /** Check if the active wallet is a multisig wallet */
    fun isActiveWalletMultisig(): Boolean {
        val walletId = activeWalletId ?: return false
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false
        return metadata.isMultisig
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
         * @property error The error type (from WalletSigningService.SigningError)
         * @property message Human-readable error message
         */
        data class Failure(
            val error: WalletSigningService.SigningError,
            val message: String
        ) : PsbtSigningResult()
    }

    fun signPsbt(psbtString: String): PsbtSigningResult {
        val activeMetadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
            ?: return PsbtSigningResult.Failure(
                WalletSigningService.SigningError.NO_ACTIVE_WALLET,
                "No wallet is currently active. Please open a wallet first."
            )

        val result = if (activeMetadata.isMultisig) {
            // Multisig: delegate to signing service
            signingService.signMultiSig(
                psbtString,
                activeMetadata,
                isDecoyMode,
                walletStates.toMap(),
                getSessionKeySeed = { keyId -> passphraseManager.getSessionKeySeed(keyId) }
            )
        } else {
            // Single-sig: delegate to signing service
            val state = getActiveWalletState()
                ?: return PsbtSigningResult.Failure(
                    WalletSigningService.SigningError.WALLET_NOT_LOADED,
                    "Wallet is not loaded. Please open the wallet first."
                )
            signingService.signSingleSig(psbtString, state, isActiveWalletTestnet())
        }

        // Map signing service result to public API result
        return when (result) {
            is WalletSigningService.SigningResult.Success ->
                PsbtSigningResult.Success(result.signedPsbt, result.alternativePathsUsed)
            is WalletSigningService.SigningResult.Failure ->
                PsbtSigningResult.Failure(result.error, result.message)
        }
    }

    fun getPsbtDetails(psbtString: String): PsbtDetails? {
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getPsbtDetails(psbtString, isTestnet)
    }

    /**
     * Checks if a PSBT can be finalized (has all required signatures).
     * Works for both single-sig (1 signature) and multi-sig (m-of-n with >= m signatures).
     */
    fun canFinalize(psbtString: String): Boolean {
        return bitcoinService.canFinalize(psbtString)
    }
    
    @Deprecated("Use canFinalize() instead", ReplaceWith("canFinalize(psbtString)"))
    fun canFinalizeSingleSig(psbtString: String): Boolean {
        return canFinalize(psbtString)
    }

    /**
     * Finalizes a single-sig PSBT and extracts the raw transaction.
     * @return FinalizePsbtResult with raw tx hex on success, or error message on failure
     */
    fun finalizePsbt(psbtString: String): PsbtService.FinalizePsbtResult {
        return bitcoinService.finalizePsbt(psbtString)
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
                    is Result.Success -> result.value
                    is Result.Error -> null
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
     * Delegates to KeyEncodingService with account derivation.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive key for
     * @return Extended public key string, or empty string on error
     */
    fun getXpubForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val scriptType = getScriptType(baseDerivationPath)
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getXpubForAccount(
            masterPrivateKey, baseDerivationPath, accountNumber, scriptType, isTestnet
        )
    }

    /**
     * Gets the extended private key (xprv/yprv/zprv) for a specific account number.
     * WARNING: Contains private keys - handle with extreme care!
     * Delegates to KeyEncodingService with account derivation.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive key for
     * @return Extended private key string, or empty string on error
     */
    fun getXprivForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val scriptType = getScriptType(baseDerivationPath)
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getXprivForAccount(
            masterPrivateKey, baseDerivationPath, accountNumber, scriptType, isTestnet
        )
    }

    // ==================== BIP48 Multisig Export ====================

    /**
     * Gets the BIP48 extended public key (Zpub/Vpub/Ypub/Upub) for multisig coordinator import.
     * Use this to add this wallet as a cosigner in Sparrow, Specter, or other coordinators.
     *
     * @param accountNumber Account number to derive key for
     * @param bip48ScriptType Script type: P2WSH (native) or P2SH_P2WSH (wrapped)
     * @return BIP48 xpub string (Zpub/Vpub for P2WSH, Ypub/Upub for P2SH-P2WSH), or empty on error
     */
    fun getBip48XpubForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getBip48XpubForAccount(
            masterPrivateKey, accountNumber, bip48ScriptType, isTestnet
        )
    }

    /**
     * Gets the BIP48 extended private key (Zprv/Vprv/Yprv/Uprv) for multisig wallet with signing.
     * WARNING: Contains private keys - handle with extreme care!
     *
     * @param accountNumber Account number to derive key for
     * @param bip48ScriptType Script type: P2WSH (native) or P2SH_P2WSH (wrapped)
     * @return BIP48 xprv string, or empty on error
     */
    fun getBip48XprivForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getBip48XprivForAccount(
            masterPrivateKey, accountNumber, bip48ScriptType, isTestnet
        )
    }

    /**
     * Gets the BIP48 derivation path for the given account and script type.
     * Useful for displaying the path alongside the exported key.
     *
     * @param accountNumber Account number
     * @param bip48ScriptType Script type: P2WSH or P2SH_P2WSH
     * @return Path string like "m/48'/0'/0'/2'"
     */
    fun getBip48PathForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val isTestnet = isActiveWalletTestnet()
        return DerivationPaths.bip48(accountNumber, bip48ScriptType, isTestnet)
    }


    fun getBip48DescriptorForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getBip48DescriptorForAccount(
            fingerprint, masterPrivateKey, accountNumber, bip48ScriptType, isTestnet
        )
    }

    /**
     * Gets the BIP48 multisig descriptor (private/spending) for watch+sign setups.
     * WARNING: Contains private keys - handle with extreme care!
     *
     * @param accountNumber Account number to derive descriptor for
     * @param bip48ScriptType Script type: P2WSH (native) or P2SH_P2WSH (wrapped)
     * @return Private descriptor string with checksum, or empty on error
     */
    fun getBip48PrivateDescriptorForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getBip48PrivateDescriptorForAccount(
            fingerprint, masterPrivateKey, accountNumber, bip48ScriptType, isTestnet
        )
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
     * Delegates to KeyEncodingService with account derivation.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive descriptor for
     * @return Descriptor string with checksum, or empty string on error
     */
    fun getUnifiedDescriptorForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val scriptType = getScriptType(baseDerivationPath)
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getUnifiedDescriptorForAccount(
            fingerprint, masterPrivateKey, baseDerivationPath, accountNumber, scriptType, isTestnet
        )
    }

    /**
     * Gets the private (spending) descriptor for a specific account number.
     * WARNING: Contains private keys - handle with extreme care!
     * Delegates to KeyEncodingService with account derivation.
     *
     * @param baseDerivationPath Base derivation path for the wallet
     * @param accountNumber Account number to derive descriptor for
     * @return Private descriptor string with checksum, or empty string on error
     */
    fun getPrivateDescriptorForAccount(baseDerivationPath: String, accountNumber: Int): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val fingerprint = state.fingerprint
        val scriptType = getScriptType(baseDerivationPath)
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getPrivateDescriptorForAccount(
            fingerprint, masterPrivateKey, baseDerivationPath, accountNumber, scriptType, isTestnet
        )
    }

    /**
     * Gets the BIP32 root key (master private key at depth 0).
     * WARNING: This is the root of all derived keys - handle with extreme care!
     * Delegates to KeyEncodingService.
     *
     * @return Master private key as xprv (mainnet) or tprv (testnet), or empty string on error
     */
    fun getBIP32RootKey(): String {
        val state = getActiveWalletState() ?: return ""
        val masterPrivateKey = state.getMasterPrivateKey() ?: return ""
        val isTestnet = isActiveWalletTestnet()

        return keyEncodingService.getBIP32RootKey(masterPrivateKey, isTestnet)
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
    fun getAddressKeys(index: Int, isChange: Boolean): AddressService.AddressKeyPair? {
        val state = getActiveWalletState() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getAddressKeys(accountPrivateKey, index, isChange, isTestnet)
    }

    // ==================== Session Seed Management (delegated to PassphraseManager) ====================

    /**
     * Checks if a wallet needs passphrase re-entry.
     * This happens when hasPassphrase=true and we don't have a session seed.
     */
    fun needsPassphraseInput(walletId: String): Boolean =
        passphraseManager.needsPassphraseInput(walletId, isDecoyMode)

    /**
     * Sets the session seed for a wallet by computing it from the entered passphrase.
     * Used when user re-enters passphrase after app restart.
     */
    suspend fun setSessionPassphrase(walletId: String, passphrase: String) =
        passphraseManager.setWalletPassphrase(walletId, passphrase, isDecoyMode)

    /**
     * Calculates master fingerprint from mnemonic and passphrase.
     * Used for real-time fingerprint preview in passphrase dialogs.
     */
    fun calculateFingerprint(mnemonic: List<String>, passphrase: String): String? =
        passphraseManager.calculateFingerprint(mnemonic, passphrase)

    /**
     * Gets the mnemonic for a wallet by ID (for passphrase re-entry dialog).
     */
    suspend fun getMnemonicForWallet(walletId: String): List<String>? =
        passphraseManager.getMnemonicForWallet(walletId, isDecoyMode)

    // ==================== Multi-sig Passphrase Support (delegated to PassphraseManager) ====================

    /**
     * Gets the list of keys needing passphrase input for a multi-sig wallet.
     */
    suspend fun getKeysNeedingPassphrase(walletId: String): List<com.gorunjinian.metrovault.domain.manager.PassphraseManager.KeyPassphraseInfo> =
        passphraseManager.getKeysNeedingPassphrase(walletId, isDecoyMode)

    /**
     * Sets the session seed for a WalletKey from entered passphrase.
     */
    suspend fun setSessionKeyPassphrase(keyId: String, passphrase: String) =
        passphraseManager.setKeyPassphrase(keyId, passphrase, isDecoyMode)

    /**
     * Gets calculated fingerprints for all local keys in a multi-sig wallet.
     */
    suspend fun getCalculatedKeyFingerprints(walletId: String): List<com.gorunjinian.metrovault.domain.manager.PassphraseManager.CalculatedKeyFingerprint> =
        passphraseManager.getCalculatedKeyFingerprints(walletId, isDecoyMode)

    private fun getScriptType(path: String): ScriptType = DerivationPaths.getScriptType(path)
}