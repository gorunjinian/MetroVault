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
import com.gorunjinian.metrovault.data.model.WalletSecrets
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.service.BitcoinService
import com.gorunjinian.metrovault.domain.service.AddressCheckResult
import com.gorunjinian.metrovault.domain.service.PsbtDetails
import com.gorunjinian.metrovault.core.crypto.SecureByteArray
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
        secureStorage.clearSession()
        unloadAllWallets()
        Log.d(TAG, "Session cleared")
    }

    /**
     * Emergency wipe - clears all sensitive data immediately.
     */
    fun emergencyWipe() {
        Log.w(TAG, "EMERGENCY WIPE - Clearing all wallet data from memory")
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
     * Fast operation (<10ms) after login.
     */
    suspend fun createWallet(
        name: String,
        mnemonic: List<String>,
        derivationPath: String,
        passphrase: String = ""
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

            // Create wallet to get fingerprint
            val walletResult = bitcoinService.createWalletFromMnemonic(mnemonic, passphrase, derivationPath)
                ?: return@withContext WalletCreationResult.Error(WalletCreationError.WALLET_CREATION_FAILED)

            val walletId = java.util.UUID.randomUUID().toString()
            val mnemonicString = mnemonic.joinToString(" ")

            // Create metadata
            val metadata = WalletMetadata(
                id = walletId,
                name = name,
                derivationPath = derivationPath,
                masterFingerprint = walletResult.fingerprint,
                hasPassphrase = passphrase.isNotEmpty(),
                createdAt = System.currentTimeMillis()
            )

            // Create secrets (plaintext - encrypted by SecureStorage)
            val secrets = WalletSecrets(
                mnemonic = mnemonicString,
                passphrase = passphrase
            )

            // Save to storage
            if (!secureStorage.saveWalletMetadata(metadata, isDecoyMode)) {
                return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
            }
            if (!secureStorage.saveWalletSecrets(walletId, secrets, isDecoyMode)) {
                return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
            }

            // Update wallet order
            val currentOrder = secureStorage.loadWalletOrder(isDecoyMode)?.toMutableList() ?: mutableListOf()
            currentOrder.add(walletId)
            secureStorage.saveWalletOrder(currentOrder, isDecoyMode)

            // Update in-memory list
            synchronized(walletListLock) {
                _walletMetadataList.add(metadata)
                _wallets.value = _walletMetadataList.toList()
            }

            Log.d(TAG, "Wallet created: $walletId")
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

            val mnemonic = secrets.mnemonic.split(" ")
            val passphrase = secrets.passphrase

            val walletResult = bitcoinService.createWalletFromMnemonic(mnemonic, passphrase, metadata.derivationPath)
                ?: return@withContext false

            // Create secure mnemonic storage for in-memory use
            val mnemonicBytes = secrets.mnemonic.toByteArray()
            val secureMnemonic = SecureByteArray(mnemonicBytes.size)
            System.arraycopy(mnemonicBytes, 0, secureMnemonic.get(), 0, mnemonicBytes.size)
            mnemonicBytes.fill(0) // Wipe intermediate

            val walletState = WalletState(
                name = metadata.name,
                mnemonic = secureMnemonic,
                derivationPath = metadata.derivationPath,
                fingerprint = walletResult.fingerprint,
                masterPrivateKey = walletResult.masterPrivateKey,
                accountPrivateKey = walletResult.accountPrivateKey,
                accountPublicKey = walletResult.accountPublicKey
            )

            walletStates[walletId] = walletState
            activeWalletId = walletId
            Log.d(TAG, "Wallet loaded: $walletId")
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

    suspend fun renameWallet(walletId: String, newName: String): Boolean = withContext(Dispatchers.IO) {
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

    // ==================== Memory Management ====================

    fun unloadWallet(walletId: String) {
        walletStates[walletId]?.wipe()
        walletStates.remove(walletId)
        if (activeWalletId == walletId) activeWalletId = null
    }

    fun unloadAllWallets() {
        walletStates.values.forEach { it.wipe() }
        walletStates.clear()
        synchronized(walletListLock) {
            _walletMetadataList.clear()
            _wallets.value = emptyList()
        }
        activeWalletId = null
    }

    // ==================== Bitcoin Operations ====================

    fun signPsbt(psbtString: String): String? {
        val state = getActiveWalletState() ?: return null
        val masterPrivateKey = state.getMasterPrivateKey() ?: return null
        val accountPrivateKey = state.getAccountPrivateKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return bitcoinService.signPsbt(psbtString, masterPrivateKey, accountPrivateKey, scriptType)
    }

    fun getPsbtDetails(psbtString: String): PsbtDetails? {
        return bitcoinService.getPsbtDetails(psbtString)
    }

    fun generateAddresses(count: Int, offset: Int = 0, isChange: Boolean = false): List<BitcoinAddress>? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return (0 until count).mapNotNull { i ->
            bitcoinService.generateAddress(accountPublicKey, offset + i, isChange, scriptType)
        }
    }

    fun checkAddressBelongsToWallet(address: String): AddressCheckResult? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return bitcoinService.checkAddressBelongsToWallet(address, accountPublicKey, scriptType)
    }

    // ==================== Wallet Info ====================

    fun getActiveWalletName() = getActiveWalletState()?.name ?: "No Wallet"

    fun getActiveWalletDerivationPath() = getActiveWalletState()?.derivationPath ?: ""

    fun getActiveXpub(): String? {
        val state = getActiveWalletState() ?: return null
        val accountPublicKey = state.getAccountPublicKey() ?: return null
        val scriptType = getScriptType(state.derivationPath)
        return bitcoinService.getAccountXpub(accountPublicKey, scriptType)
    }

    @Suppress("unused")
    fun getActiveDescriptor(): Pair<String, String>? {
        val state = getActiveWalletState() ?: return null
        val masterPrivateKey = state.getMasterPrivateKey() ?: return null
        return bitcoinService.getWalletDescriptors(masterPrivateKey)
    }

    fun getMasterFingerprint() = getActiveWalletState()?.fingerprint

    fun getActiveMnemonic(): List<String>? {
        return try {
            val state = getActiveWalletState() ?: return null
            val mnemonic = state.getMnemonic() ?: return null
            String(mnemonic.get()).split(" ")
        } catch (_: Exception) {
            Log.e(TAG, "Failed to retrieve mnemonic")
            null
        }
    }

    // ==================== Helpers ====================

    private fun getScriptType(path: String): ScriptType {
        return when {
            path.startsWith("m/86'") -> ScriptType.P2TR
            path.startsWith("m/84'") -> ScriptType.P2WPKH
            path.startsWith("m/49'") -> ScriptType.P2SH_P2WPKH
            path.startsWith("m/44'") -> ScriptType.P2PKH
            else -> ScriptType.P2WPKH
        }
    }
}
