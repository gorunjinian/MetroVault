package com.gorunjinian.metrovault.domain

import android.annotation.SuppressLint
import android.content.Context
import androidx.compose.runtime.Stable
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.AddressFormat
import com.gorunjinian.metrovault.data.model.InputSigningRefusal
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.Result
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletKind
import com.gorunjinian.metrovault.data.model.WalletProfile
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.data.model.CoordinatorExportError
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.data.model.PsbtOutputOwnership
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.metrovault.domain.service.bitcoin.KeyEncodingService
import com.gorunjinian.metrovault.domain.service.bitcoin.CoordinatorExportService
import com.gorunjinian.metrovault.domain.service.multisig.BSMS
import com.gorunjinian.metrovault.domain.service.multisig.MultisigAddressService
import com.gorunjinian.metrovault.domain.service.multisig.MultisigChangeValidator
import com.gorunjinian.metrovault.domain.service.psbt.PsbtOutputClassifier
import com.gorunjinian.metrovault.domain.service.psbt.PsbtService
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentDisplayContext
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletSigningService
import com.gorunjinian.metrovault.data.repository.WalletListStore
import com.gorunjinian.metrovault.data.repository.WalletRepository
import com.gorunjinian.metrovault.domain.manager.CoordinatorExportManager
import com.gorunjinian.metrovault.domain.manager.PassphraseManager
import com.gorunjinian.metrovault.domain.manager.SilentPaymentManager
import com.gorunjinian.metrovault.domain.manager.StatelessWalletManager
import com.gorunjinian.metrovault.domain.manager.WalletCreationManager
import com.gorunjinian.metrovault.domain.manager.WalletAccountManager
import com.gorunjinian.metrovault.domain.manager.WalletSessionManager
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.OutputOwnership
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.SingleSigAccount
import com.gorunjinian.vaultovich.verifyOutput
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
    private val multisigChangeValidator = MultisigChangeValidator(multisigAddressService)
    private val sessionKeyManager = SessionKeyManager.getInstance()

    private val walletStates = ConcurrentHashMap<String, WalletState>()
    private val walletList = WalletListStore()
    val wallets: StateFlow<List<WalletMetadata>> = walletList.wallets

    // Managers for better separation of concerns
    private val passphraseManager = PassphraseManager(secureStorage, bitcoinService)
    private val sessionManager = WalletSessionManager(secureStorage, passphraseManager)
    private val accountManager = WalletAccountManager(secureStorage, walletList)
    private val statelessWalletManager = StatelessWalletManager(bitcoinService)
    private val silentPaymentManager = SilentPaymentManager()
    private val signingService = WalletSigningService(secureStorage, bitcoinService)
    private val keyEncodingService = KeyEncodingService()
    private val coordinatorExportManager =
        CoordinatorExportManager(keyEncodingService, CoordinatorExportService(keyEncodingService))

    // Repository for wallet CRUD and state management, sharing the in-memory wallet state.
    // isDecoyMode is passed as a lambda since it's dynamic (depends on session state)
    private val walletRepository = WalletRepository(
        secureStorage = secureStorage,
        bitcoinService = bitcoinService,
        passphraseManager = passphraseManager,
        walletStates = walletStates,
        walletList = walletList,
        getIsDecoyMode = { isDecoyMode }
    )
    private val creationManager = WalletCreationManager(
        secureStorage, bitcoinService, silentPaymentManager, passphraseManager, sessionKeyManager,
        walletRepository, walletList
    )

    private val _isLoading = MutableStateFlow(false)
    val isLoading: StateFlow<Boolean> = _isLoading.asStateFlow()

    private var activeWalletId: String? = null

    // isDecoyMode now delegated to sessionManager
    var isDecoyMode: Boolean
        get() = sessionManager.isDecoyMode
        private set(_) { /* no-op, managed by sessionManager */ }

    companion object {
        private const val TAG = "Wallet"

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
     * Also wipes any active stateless wallet.
     */
    fun emergencyWipe() {
        wipeStatelessWallet()
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

    // ==================== Wallet Creation (delegated to WalletCreationManager) ====================

    fun generateMnemonic(wordCount: Int, userEntropy: ByteArray? = null): List<String> {
        return bitcoinService.generateMnemonicWithUserEntropy(wordCount, userEntropy)
    }

    /**
     * Creates a wallet from mnemonic in the current vault. See [WalletCreationManager.createWallet]
     * for the security model around the stored seed and the passphrase.
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
    ): WalletCreationResult = creationManager.createWallet(
        name, mnemonic, derivationPath, passphrase, savePassphraseLocally, accountNumber, isDecoyMode
    )

    /**
     * Creates a multisig wallet from a parsed configuration. At least one local key must match a
     * cosigner fingerprint.
     *
     * @return the new wallet id on success, or null on failure
     */
    suspend fun createMultisigWallet(name: String, config: MultisigConfig): String? =
        creationManager.createMultisigWallet(name, config, isDecoyMode)

    // ==================== Multisig Registration / Verification ====================

    /**
     * Checksum of a multisig wallet's current descriptor (BIP-380), or "" for non-multisig.
     * Used to bind the verified flag to the exact descriptor that was registered.
     */
    fun multisigDescriptorChecksum(metadata: WalletMetadata): String =
        metadata.multisigConfig?.rawDescriptor?.let { BSMS.descriptorChecksum(it) } ?: ""

    /** Whether the given multisig wallet is registered/verified against its current descriptor. */
    fun isMultisigRegistered(metadata: WalletMetadata): Boolean =
        metadata.isMultisigRegistered(multisigDescriptorChecksum(metadata))

    /**
     * Marks a multisig wallet as verified/registered for signing, binding the verified flag to a
     * checksum of its current descriptor. Returns false if the wallet is missing or not multisig.
     */
    fun markMultisigVerified(walletId: String): Boolean {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false
        val config = metadata.multisigConfig
        if (!metadata.isMultisig || config == null) {
            AppLog.e(TAG) { "Cannot verify non-multisig wallet" }
            return false
        }
        val updated = metadata.copy(
            multisigVerified = true,
            multisigVerifiedDescriptorChecksum = BSMS.descriptorChecksum(config.rawDescriptor)
        )
        if (!secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            AppLog.e(TAG) { "Failed to persist multisig verification" }
            return false
        }
        // Refresh in-memory list so observers (Wallet Details) reflect the change without a reload
        walletList.replace(updated)
        AppLog.d(TAG) { "Multisig wallet registered/verified" }
        return true
    }

    // ==================== Wallet Loading ====================


    /**
     * Opens a wallet - loads it into memory for operations.
     * Fast operation (<10ms) - just AES decryption.
     * Delegates to WalletRepository for loading logic.
     */
    suspend fun openWallet(walletId: String, showLoading: Boolean = true): Boolean {
        // Exactly one wallet is ever active: opening a persisted wallet disposes any stateless one,
        // so signing/address generation can never fall through to the wrong keys.
        statelessWalletManager.wipe()

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
            // Exactly one wallet is ever active: a persisted wallet displaces any stateless one
            statelessWalletManager.wipe()
            activeWalletId = walletId
            AppLog.d(TAG) { "Active wallet set" }
            true
        } else {
            AppLog.w(TAG) { "Cannot set active: wallet not loaded" }
            false
        }
    }

    /**
     * Gets the active wallet state.
     * Falls back to stateless wallet if no persisted wallet is active.
     */
    fun getActiveWalletState(): WalletState? {
        // First try persisted wallet
        val persistedState = walletRepository.getActiveWalletState(activeWalletId)
        if (persistedState != null) return persistedState
        
        // Fall back to stateless wallet if active
        return statelessWalletManager.get()
    }

    // ==================== Wallet Operations ====================

    suspend fun deleteWallet(walletId: String): Boolean = walletRepository.deleteWallet(walletId)

    suspend fun renameWallet(walletId: String, newName: String): Boolean =
        walletRepository.renameWallet(walletId, newName)

    fun swapWallets(index1: Int, index2: Int) = walletRepository.swapWallets(index1, index2)

    suspend fun saveWalletListOrder(): Boolean = walletRepository.saveWalletListOrder()

    // ==================== Account Management (delegated to WalletAccountManager) ====================

    /** Add a new account number to a wallet. Returns false if already exists. */
    suspend fun addAccountToWallet(walletId: String, accountNumber: Int): Boolean =
        accountManager.addAccount(walletId, accountNumber, isDecoyMode)

    /**
     * Remove an account from a wallet.
     * Returns false if: account doesn't exist, is currently active, or is the last account.
     */
    suspend fun removeAccountFromWallet(walletId: String, accountNumber: Int): Boolean =
        accountManager.removeAccount(walletId, accountNumber, isDecoyMode)

    /**
     * Move a persisted single-seed wallet to [format] (any of the four single-sig BIP purposes or
     * BIP-352 Silent Payments) on the given network, without re-importing the seed. Pass the
     * current format to change only the network, or the current network to change only the
     * format. The master key, fingerprint, seed, accounts list and custom account names are all
     * preserved; only `derivationPath` (rewritten under the new purpose and coin type at the
     * current active account) and the silent-payment flag change. The wallet is then unloaded
     * and reloaded so `WalletState` rebuilds against the new path.
     *
     * The cached silent-payment scan/spend pubkeys depend on both purpose and coin type, so they
     * are cleared here and re-derived by [persistAndReload] when the target is Silent Payments —
     * from the master key in memory, which for a passphrase wallet is the with-passphrase one,
     * the same identity creation uses. Rejects multisig (BIP-48 has a cosigner-fixed script type
     * and network-bound cosigner xpubs) and missing-metadata calls. A call that changes nothing
     * returns `true` without touching storage.
     */
    suspend fun changeDerivation(walletId: String, format: AddressFormat, testnet: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode)
                ?: return@withContext false
            if (metadata.isMultisig) return@withContext false
            val newPath = DerivationPaths.withAccountNumber(
                DerivationPaths.baseForFormat(format, testnet),
                metadata.activeAccountNumber
            )
            if (newPath == metadata.getActiveDerivationPath() &&
                metadata.isSilentPayment == format.isSilentPayment
            ) return@withContext true
            persistAndReload(
                metadata.copy(
                    derivationPath = newPath,
                    isSilentPayment = format.isSilentPayment,
                    silentPaymentScanPubKey = "",
                    silentPaymentSpendPubKey = ""
                )
            )
        }

    /**
     * Persist [updated] metadata (whose `derivationPath` has been rewritten), publish the new list,
     * reload the wallet so its `WalletState` is rebuilt against the new path, and refresh the
     * cached silent-payment pubkeys if the wallet is SP-flagged. Returns the reload result;
     * `false` if the metadata write failed.
     */
    private suspend fun persistAndReload(updated: WalletMetadata): Boolean {
        if (!secureStorage.updateWalletMetadata(updated, isDecoyMode)) return false
        walletList.replace(updated)
        unloadWallet(updated.id)
        val reloaded = openWallet(updated.id)
        if (reloaded) refreshSilentPaymentPubkeysIfSp(updated.id)
        return reloaded
    }

    /** Switch active account. Reloads wallet with new derivation path. */
    suspend fun switchActiveAccount(walletId: String, accountNumber: Int): Boolean {
        val switched = accountManager.switchAccount(
            walletId, accountNumber, isDecoyMode,
            onUnloadWallet = { unloadWallet(it) },
            onOpenWallet = { openWallet(it) }
        )
        if (switched) refreshSilentPaymentPubkeysIfSp(walletId)
        return switched
    }

    /**
     * For an SP-flagged wallet, re-derive the scan/spend pubkeys at the now-active account and
     * persist them to metadata so the cached `sp1q…` shown in locked-state matches the new active
     * account. No-op for non-SP wallets or when the master key isn't currently in memory.
     *
     * Also a no-op when the loaded master key isn't the wallet's own (its fingerprint differs from
     * the stored one): a passphrase added via the shortcut, or a mistyped one, must not overwrite
     * the cached pubkeys with another wallet's.
     */
    private fun refreshSilentPaymentPubkeysIfSp(walletId: String) {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return
        if (!metadata.isSilentPayment) return
        val state = walletStates[walletId] ?: return
        if (!state.fingerprint.equals(metadata.masterFingerprint, ignoreCase = true)) return
        val master = state.getMasterPrivateKey() ?: return
        val isTestnet = DerivationPaths.isTestnet(state.derivationPath)
        val keys = runCatching {
            silentPaymentManager.deriveKeys(master, metadata.activeAccountNumber, isTestnet)
        }.getOrNull() ?: return
        val newScanHex = keys.scanPublicKey.toHex()
        val newSpendHex = keys.spendPublicKey.toHex()
        if (metadata.silentPaymentScanPubKey == newScanHex &&
            metadata.silentPaymentSpendPubKey == newSpendHex
        ) return
        val updated = metadata.copy(
            silentPaymentScanPubKey = newScanHex,
            silentPaymentSpendPubKey = newSpendHex
        )
        if (secureStorage.updateWalletMetadata(updated, isDecoyMode)) {
            walletList.replace(updated)
        }
    }

    /**
     * Get active account number for active wallet. A stateless wallet has no metadata, so its
     * account comes from its derivation path.
     */
    fun getActiveAccountNumber(): Int {
        statelessWalletManager.get()?.let { return DerivationPaths.getAccountNumber(it.derivationPath) }
        return accountManager.getActiveAccountNumber(activeWalletId)
    }

    /**
     * Rename an account's display name.
     * Pass empty string or default name ("Account N") to remove custom name.
     */
    suspend fun renameAccount(walletId: String, accountNumber: Int, newName: String): Boolean =
        accountManager.renameAccount(walletId, accountNumber, newName, isDecoyMode)

    /**
     * Addresses-screen start indices (receive, change) for the active wallet's active account.
     * Stateless wallets have no metadata to read from and always start at 0.
     */
    fun getAddressStartIndices(): Pair<Int, Int> {
        if (statelessWalletManager.get() != null) return 0 to 0
        return accountManager.getAddressStartIndices(activeWalletId)
    }

    /**
     * Persist Addresses-screen start indices for the active wallet's active account.
     * Returns false for stateless wallets, which have nowhere to store the preference;
     * the screen then keeps the values for the current visit only.
     */
    suspend fun setAddressStartIndices(receiveStart: Int, changeStart: Int): Boolean {
        if (statelessWalletManager.get() != null) return false
        val walletId = activeWalletId ?: return false
        return accountManager.setAddressStartIndices(
            walletId, getActiveAccountNumber(), receiveStart, changeStart, isDecoyMode
        )
    }

    // ==================== Memory Management ====================

    fun unloadWallet(walletId: String) {
        walletRepository.unloadWallet(walletId)
        if (activeWalletId == walletId) activeWalletId = null
    }

    /**
     * Wipe all wallet keys from memory but keep metadata list.
     * Use when navigating away from wallet screens (security).
     * Resets RAM to same state as fresh app launch, which includes disposing
     * the in-memory stateless wallet: returning Home is "done with the wallet".
     * Delegates to WalletRepository.
     */
    fun unloadAllWalletKeys() {
        statelessWalletManager.wipe()
        walletRepository.unloadAllWalletKeys()
        activeWalletId = null
    }

    /**
     * Full session wipe - clears both keys AND metadata.
     * Use for lock/logout/emergency wipe.
     * Delegates to WalletRepository.
     */
    fun unloadAllWallets() {
        statelessWalletManager.wipe()
        walletRepository.unloadAllWallets()
        activeWalletId = null
    }

    // ==================== Stateless Wallet (delegated to StatelessWalletManager) ====================
    
    /** Computes the master fingerprint for a mnemonic without modifying any state. */
    fun computeFingerprintOnly(
        mnemonic: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): String? = statelessWalletManager.computeFingerprintOnly(mnemonic, passphrase, derivationPath)
    
    /**
     * Creates a stateless wallet from mnemonic and passphrase (memory-only).
     * Any loaded persisted wallet is unloaded first so exactly one wallet is ever active.
     */
    fun createStatelessWallet(
        mnemonic: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): WalletState? {
        unloadAllWalletKeys()
        return statelessWalletManager.create(mnemonic, passphrase, derivationPath)
    }
    
    /** Wipes the stateless wallet from memory. */
    fun wipeStatelessWallet() = statelessWalletManager.wipe()
    
    /** Gets unified wallet info for the currently active wallet. */
    fun getActiveWalletInfo(metadata: WalletMetadata?): StatelessWalletManager.ActiveWalletInfo =
        statelessWalletManager.getActiveWalletInfo(metadata)

    // ==================== Bitcoin Operations ====================

    /** Check if the active wallet is a testnet wallet */
    fun isActiveWalletTestnet(): Boolean {
        // Check stateless wallet first
        val statelessState = statelessWalletManager.get()
        if (statelessState != null) {
            return DerivationPaths.isTestnet(statelessState.derivationPath)
        }
        
        // Fall back to persisted wallet
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
         * @property usedAddressLookupFallback true if Stage 3 (script-derivation address
         *   lookup) fired for any input. When true, the incoming PSBT did not declare
         *   the input via PSBT_IN_BIP32_DERIVATION with a matching fingerprint and
         *   MetroVault matched it by deriving addresses from the seed. UI should
         *   surface a non-blocking warning to the user.
         * @property addressLookupInputIndices The input indices that were signed via
         *   Stage 3 fallback. Empty when `usedAddressLookupFallback` is false.
         */
        data class Success(
            val signedPsbt: String,
            val alternativePathsUsed: List<String> = emptyList(),
            val usedAddressLookupFallback: Boolean = false,
            val addressLookupInputIndices: List<Int> = emptyList(),
            /**
             * Inputs belonging to this wallet that MetroVault declined to sign. Non-empty means
             * the PSBT is only partially signed and the user must be told.
             */
            val refusals: List<InputSigningRefusal> = emptyList(),
        ) : PsbtSigningResult()

        /**
         * Signing failed with a specific error.
         * @property error The error type (from WalletSigningService.SigningError)
         * @property message Human-readable error message
         */
        data class Failure(
            val error: WalletSigningService.SigningError,
            val message: String,
            /** Per-input refusals, when inputs matched this wallet but signing was declined. */
            val refusals: List<InputSigningRefusal> = emptyList(),
        ) : PsbtSigningResult()
    }

    /**
     * Signs [psbtString] with the active wallet.
     *
     * @param trustWitnessUtxo The user's explicit "Sign Anyway" for a multi-input segwit v0 PSBT
     *   that omits its previous transactions. The first attempt must always pass `false`; the
     *   signer then refuses with [InputSigningRefusal.MissingPreviousTransaction] and withholds
     *   every signature until the user waives the check knowingly. Single-input transactions never
     *   need it. See `PsbtSigner.signParsedPsbt` for the reasoning.
     */
    fun signPsbt(psbtString: String, trustWitnessUtxo: Boolean = false): PsbtSigningResult {
        // Check for stateless wallet first (no activeWalletId needed)
        val statelessState = statelessWalletManager.get()
        if (statelessState != null) {
            val isTestnet = DerivationPaths.isTestnet(statelessState.derivationPath)
            val result = signingService.signSingleSig(psbtString, statelessState, isTestnet, trustWitnessUtxo)
            return when (result) {
                is WalletSigningService.SigningResult.Success ->
                    PsbtSigningResult.Success(
                        signedPsbt = result.signedPsbt,
                        alternativePathsUsed = result.alternativePathsUsed,
                        usedAddressLookupFallback = result.usedAddressLookupFallback,
                        addressLookupInputIndices = result.addressLookupInputIndices,
                        refusals = result.refusals,
                    )
                is WalletSigningService.SigningResult.Failure ->
                    PsbtSigningResult.Failure(result.error, result.message, result.refusals)
            }
        }
        
        // Fall back to persisted wallet
        val activeMetadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
            ?: return PsbtSigningResult.Failure(
                WalletSigningService.SigningError.NO_ACTIVE_WALLET,
                "No wallet is currently active. Please open a wallet first."
            )

        // Multisig signing gates (security boundary — runs before any signature is produced):
        // 1) the wallet must be explicitly verified/registered, and
        // 2) no output may deceptively claim to be our change while pointing elsewhere.
        if (activeMetadata.isMultisig) {
            if (!isMultisigRegistered(activeMetadata)) {
                return PsbtSigningResult.Failure(
                    WalletSigningService.SigningError.WALLET_NOT_VERIFIED,
                    "Verify & register this multisig wallet before signing."
                )
            }
            activeMetadata.multisigConfig?.let { config ->
                val validation = multisigChangeValidator.validate(psbtString, config)
                if (validation is MultisigChangeValidator.Result.Mismatch) {
                    return PsbtSigningResult.Failure(
                        WalletSigningService.SigningError.CHANGE_OUTPUT_MISMATCH,
                        "Output #${validation.outputIndex} claims to be your change but does not match this " +
                            "wallet's registered descriptor. Signing refused."
                    )
                }
            }
        }

        val result = if (activeMetadata.isMultisig) {
            // Multisig: delegate to signing service
            signingService.signMultiSig(
                psbtString,
                activeMetadata,
                isDecoyMode,
                walletStates.toMap(),
                getSessionKeySeed = { keyId -> passphraseManager.getSessionKeySeed(keyId) },
                trustWitnessUtxo = trustWitnessUtxo,
            )
        } else {
            // Single-sig: delegate to signing service
            val state = getActiveWalletState()
                ?: return PsbtSigningResult.Failure(
                    WalletSigningService.SigningError.WALLET_NOT_LOADED,
                    "Wallet is not loaded. Please open the wallet first."
                )
            signingService.signSingleSig(psbtString, state, isActiveWalletTestnet(), trustWitnessUtxo)
        }

        // Map signing service result to public API result
        return when (result) {
            is WalletSigningService.SigningResult.Success ->
                PsbtSigningResult.Success(
                    signedPsbt = result.signedPsbt,
                    alternativePathsUsed = result.alternativePathsUsed,
                    usedAddressLookupFallback = result.usedAddressLookupFallback,
                    addressLookupInputIndices = result.addressLookupInputIndices,
                    refusals = result.refusals,
                )
            is WalletSigningService.SigningResult.Failure ->
                PsbtSigningResult.Failure(result.error, result.message, result.refusals)
        }
    }

    fun getPsbtDetails(psbtString: String): PsbtDetails? {
        val isTestnet = isActiveWalletTestnet()
        return bitcoinService.getPsbtDetails(psbtString, isTestnet, buildSilentPaymentDisplayContext())
    }

    /**
     * Builds the context that lets PSBT analysis resolve silent-payment recipient outputs to their
     * derived `bc1p…` addresses (Option A). Only single-sig / stateless wallets are supported —
     * collaborative multisig silent-payment sending is out of scope — and we degrade to showing the
     * nominal `sp1q…` only when the keys aren't available.
     */
    private fun buildSilentPaymentDisplayContext(): SilentPaymentDisplayContext? {
        val state = statelessWalletManager.get() ?: getActiveWalletState() ?: return null
        val master = state.getMasterPrivateKey() ?: return null
        val account = state.getAccountPrivateKey() ?: return null
        // The BIP-352 spend key lets display resolution handle inputs spending received SP outputs.
        val spendKey = if (DerivationPaths.getPurpose(state.derivationPath) == 352) {
            SilentPaymentWalletService.deriveKeys(
                master,
                DerivationPaths.getAccountNumber(state.derivationPath),
                DerivationPaths.isTestnet(state.derivationPath),
            ).spendPrivateKey
        } else null
        return SilentPaymentDisplayContext(
            master, account, DerivationPaths.getScriptType(state.derivationPath), spendKey
        )
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
        // An SP wallet has no address chain: each received output is tweaked per payment
        if (isActiveSilentPayment()) return null

        // Check for stateless wallet first (no activeWalletId needed)
        val statelessState = statelessWalletManager.get()
        if (statelessState != null) {
            val isTestnet = DerivationPaths.isTestnet(statelessState.derivationPath)
            val accountPublicKey = statelessState.getAccountPublicKey() ?: return null
            val scriptType = getScriptType(statelessState.derivationPath)
            return (0 until count).mapNotNull { i ->
                bitcoinService.generateAddress(accountPublicKey, offset + i, isChange, scriptType, isTestnet)
            }
        }
        
        // Fall back to persisted wallet
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
        // An SP wallet has no address chain to scan, so it can't say either way
        if (isActiveSilentPayment()) return null

        // Check for stateless wallet first
        val statelessState = statelessWalletManager.get()
        if (statelessState != null) {
            val isTestnet = DerivationPaths.isTestnet(statelessState.derivationPath)
            val accountPublicKey = statelessState.getAccountPublicKey() ?: return null
            val scriptType = getScriptType(statelessState.derivationPath)
            return bitcoinService.checkAddressBelongsToWallet(address, accountPublicKey, scriptType, isTestnet)
        }
        
        // Fall back to persisted wallet
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

    /**
     * Decides which outputs of [psbtString] pay the active wallet; see [PsbtOutputClassifier].
     * [outputAddresses] are the addresses shown for each output, in output order.
     */
    fun classifyPsbtOutputs(psbtString: String, outputAddresses: List<String>): List<PsbtOutputOwnership> {
        val verify = PsbtUtils.parsePsbt(psbtString)?.let { outputOwnershipCheck(it) }
        return PsbtOutputClassifier.classify(outputAddresses, verify, ::checkAddressBelongsToWallet)
    }

    /**
     * vaultovich's `Psbt.verifyOutput` bound to the active wallet's account, resolved in the same
     * order as [checkAddressBelongsToWallet]. Null when there is no account to verify against.
     */
    private fun outputOwnershipCheck(psbt: Psbt): ((Int) -> OutputOwnership)? {
        if (isActiveSilentPayment()) return null

        val statelessState = statelessWalletManager.get()
        if (statelessState == null) {
            val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) } ?: return null
            if (metadata.isMultisig) {
                val account = metadata.multisigConfig?.let { multisigAddressService.toMultisigAccount(it) }
                    ?: return null
                return { index -> psbt.verifyOutput(index, account) }
            }
        }

        val state = statelessState ?: getActiveWalletState() ?: return null
        val master = state.getMasterPrivateKey() ?: return null
        val accountKey = state.getAccountPublicKey() ?: return null
        val account = runCatching {
            SingleSigAccount(
                masterFingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey),
                accountPath = KeyPath(state.derivationPath),
                accountKey = accountKey,
                scriptType = getScriptType(state.derivationPath),
            )
        }.getOrNull() ?: return null
        return { index -> psbt.verifyOutput(index, account) }
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

    /**
     * Builds a watch-only coordinator export for an arbitrary [accountNumber] under the active
     * wallet's purpose. No mnemonic, seed bytes, private descriptor, or extended private key is
     * passed into the export service. The active account reuses its cached public key, so
     * xpub-only stateless wallets can still export it; other accounts are derived from the
     * master key when one is loaded.
     */
    fun getCoordinatorExportForAccount(accountNumber: Int): CoordinatorExportResult {
        if (isActiveMultisig()) {
            return CoordinatorExportResult.Unsupported(CoordinatorExportError.MULTISIG_WALLET)
        }
        if (isActiveSilentPayment()) {
            return CoordinatorExportResult.Unsupported(CoordinatorExportError.SILENT_PAYMENT_WALLET)
        }
        val state = getActiveWalletState()
            ?: return CoordinatorExportResult.Error(CoordinatorExportError.WALLET_NOT_LOADED)
        return coordinatorExportManager.buildExport(state, accountNumber)
    }

    /**
     * Combined Coldcard Generic JSON for [accountNumber]: all four single-sig sections plus both
     * BIP48 multisig sections in one payload. Requires the master key (five of the six sections
     * are outside the wallet's own account path), so xpub-only wallets return null.
     */
    fun getCombinedCoordinatorExportForAccount(accountNumber: Int): String? {
        if (isActiveMultisig() || isActiveSilentPayment()) return null
        val key = activeMasterKey() ?: return null
        return coordinatorExportManager.buildCombinedExport(key.master, key.fingerprint, accountNumber, key.isTestnet)
    }

    /** Nunchuk BIP48 multisig signer record for [accountNumber]. Empty on error. */
    fun getNunchukBip48SignerRecordForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val key = activeMasterKey() ?: return ""
        return coordinatorExportManager.buildNunchukBip48SignerRecord(
            key.master, key.fingerprint, accountNumber, bip48ScriptType, key.isTestnet
        )
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getXpubForAccount(
            key.master, baseDerivationPath, accountNumber, getScriptType(baseDerivationPath), key.isTestnet
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getXprivForAccount(
            key.master, baseDerivationPath, accountNumber, getScriptType(baseDerivationPath), key.isTestnet
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getBip48XpubForAccount(key.master, accountNumber, bip48ScriptType, key.isTestnet)
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getBip48XprivForAccount(key.master, accountNumber, bip48ScriptType, key.isTestnet)
    }

    fun getBip48KeyExpressionForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getBip48KeyExpressionForAccount(
            key.fingerprint, key.master, accountNumber, bip48ScriptType, key.isTestnet
        )
    }

    /**
     * Gets the BIP48 key expression (private/spending), `[fp/48h/...]xprv`, for watch+sign setups.
     * WARNING: Contains private keys - handle with extreme care!
     *
     * @param accountNumber Account number to derive descriptor for
     * @param bip48ScriptType Script type: P2WSH (native) or P2SH_P2WSH (wrapped)
     * @return Private key expression, or empty on error
     */
    fun getBip48PrivateKeyExpressionForAccount(
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType = DerivationPaths.Bip48ScriptType.P2WSH
    ): String {
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getBip48PrivateKeyExpressionForAccount(
            key.fingerprint, key.master, accountNumber, bip48ScriptType, key.isTestnet
        )
    }

    @Suppress("unused")
    fun getActiveDescriptor(): Pair<String, String>? {
        val key = activeMasterKey() ?: return null
        return bitcoinService.getWalletDescriptors(key.master, key.isTestnet)
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getUnifiedDescriptorForAccount(
            key.fingerprint, key.master, baseDerivationPath, accountNumber,
            getScriptType(baseDerivationPath), key.isTestnet
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getPrivateDescriptorForAccount(
            key.fingerprint, key.master, baseDerivationPath, accountNumber,
            getScriptType(baseDerivationPath), key.isTestnet
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
        val key = activeMasterKey() ?: return ""
        return keyEncodingService.getBIP32RootKey(key.master, key.isTestnet)
    }

    /** The active wallet's master key, with the fingerprint and network the key exports need. */
    private class ActiveMasterKey(
        val master: DeterministicWallet.ExtendedPrivateKey,
        val fingerprint: String,
        val isTestnet: Boolean
    )

    /**
     * The active wallet's master key, or null when there is none to export from: no wallet open,
     * a multisig wallet, or an xpub-only stateless wallet.
     */
    private fun activeMasterKey(): ActiveMasterKey? {
        val state = getActiveWalletState() ?: return null
        val master = state.getMasterPrivateKey() ?: return null
        return ActiveMasterKey(master, state.fingerprint, isActiveWalletTestnet())
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

    /**
     * What the active wallet is and which features it offers, or null when no wallet is open.
     * Stateless first, like [isActiveWalletTestnet]: a stateless wallet has no metadata.
     */
    fun getActiveWalletProfile(): WalletProfile? {
        statelessWalletManager.get()?.let { return WalletProfile.stateless(it.derivationPath) }
        val metadata = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) } ?: return null
        return WalletProfile.of(metadata)
    }

    /** True if the active wallet is a silent-payment wallet, stored or stateless. */
    fun isActiveSilentPayment(): Boolean = getActiveWalletProfile()?.kind == WalletKind.SILENT_PAYMENT

    /** True if the active wallet is a multisig wallet. */
    fun isActiveMultisig(): Boolean = getActiveWalletProfile()?.kind == WalletKind.MULTISIG

    /** True if the BIP-352 scan-key export can be offered for the active wallet (single-sig with seed). */
    fun canExportSilentPaymentForActiveWallet(): Boolean {
        val state = getActiveWalletState()
        val meta = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        return silentPaymentManager.canExportSilentPayment(state, meta)
    }

    /** The active wallet's BIP-352 keypair (scan + spend), derived on demand from the master key. */
    fun getActiveSilentPaymentKeys(): com.gorunjinian.metrovault.data.model.SilentPaymentKeys? {
        val master = getActiveWalletState()?.getMasterPrivateKey() ?: return null
        return runCatching {
            silentPaymentManager.deriveKeys(master, getActiveAccountNumber(), isActiveWalletTestnet())
        }.getOrNull()
    }

    /** The active wallet's `sp1q…`/`tsp1q…` address (uses stored pubkeys when available). */
    fun getActiveSilentPaymentAddress(): String? {
        val meta = activeWalletId?.let { secureStorage.loadWalletMetadata(it, isDecoyMode) }
        val master = getActiveWalletState()?.getMasterPrivateKey()
        return silentPaymentManager.resolveAddress(meta, master, getActiveAccountNumber(), isActiveWalletTestnet())
    }

    /** The wallet's `spscan…`/`tspscan…` scan-key export string for [account] (or null if no master key). */
    fun getActiveSilentPaymentScanKeyExport(account: Int): String? {
        val master = getActiveWalletState()?.getMasterPrivateKey() ?: return null
        return runCatching {
            silentPaymentManager.scanKeyExport(master, account, isActiveWalletTestnet())
        }.getOrNull()
    }

    /** The wallet's BIP-352 `sp([fp/352h/coin_h/account_h]spscan…)#checksum` descriptor for [account]. */
    fun getActiveSilentPaymentDescriptor(account: Int): String? {
        val state = getActiveWalletState() ?: return null
        val master = state.getMasterPrivateKey() ?: return null
        return runCatching {
            silentPaymentManager.descriptor(state.fingerprint, master, account, isActiveWalletTestnet())
        }.getOrNull()
    }

    fun getActiveMnemonic(): List<String>? {
        return try {
            val state = getActiveWalletState() ?: return null
            val mnemonic = state.getMnemonic() ?: return null
            mnemonic.asString().split(" ")
        } catch (_: Exception) {
            AppLog.e(TAG) { "Failed to retrieve mnemonic" }
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
     * Checks if a single-sig wallet needs passphrase re-entry: there is no session seed, and
     * either hasPassphrase=true or the stored seed lacks the passphrase.
     */
    fun needsPassphraseInput(walletId: String): Boolean =
        passphraseManager.needsPassphraseInput(walletId, isDecoyMode)

    /**
     * Sets the session seed for a wallet by computing it from the entered passphrase.
     * Used when user re-enters passphrase after app restart, or adds one via the Passphrase
     * shortcut. A wallet already in memory still holds keys from its previous seed, so it is
     * unloaded and the next [openWallet] derives from the new one.
     */
    suspend fun setSessionPassphrase(walletId: String, passphrase: String) {
        passphraseManager.setWalletPassphrase(walletId, passphrase, isDecoyMode)
        unloadWallet(walletId)
    }

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
    suspend fun getKeysNeedingPassphrase(walletId: String): List<PassphraseManager.KeyPassphraseInfo> =
        passphraseManager.getKeysNeedingPassphrase(walletId, isDecoyMode)

    /**
     * Sets the session seed for a WalletKey from entered passphrase.
     */
    suspend fun setSessionKeyPassphrase(keyId: String, passphrase: String) =
        passphraseManager.setKeyPassphrase(keyId, passphrase, isDecoyMode)

    /**
     * Gets calculated fingerprints for all local keys in a multi-sig wallet.
     */
    suspend fun getCalculatedKeyFingerprints(walletId: String): List<PassphraseManager.CalculatedKeyFingerprint> =
        passphraseManager.getCalculatedKeyFingerprints(walletId, isDecoyMode)

    private fun getScriptType(path: String): ScriptType = DerivationPaths.getScriptType(path)
}
