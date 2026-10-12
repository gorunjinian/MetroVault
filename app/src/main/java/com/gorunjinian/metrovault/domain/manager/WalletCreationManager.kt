package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.core.crypto.SessionKeyManager
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.WalletCreationError
import com.gorunjinian.metrovault.data.model.WalletCreationResult
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.repository.WalletListStore
import com.gorunjinian.metrovault.data.repository.WalletRepository
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.MnemonicCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.UUID

/**
 * Creates single-sig and multisig wallets: validates the request against the session and the
 * per-vault wallet limit, stores or reuses the [WalletKeys], writes the metadata and display
 * order, and publishes the new wallet to the in-memory list.
 *
 * Extracted from Wallet.kt to follow single responsibility principle. The vault (main or decoy)
 * is passed in per call, like the other managers.
 */
class WalletCreationManager(
    private val secureStorage: SecureStorage,
    private val bitcoinService: BitcoinService,
    private val silentPaymentManager: SilentPaymentManager,
    private val passphraseManager: PassphraseManager,
    private val sessionKeyManager: SessionKeyManager,
    private val walletRepository: WalletRepository,
    private val walletList: WalletListStore
) {
    companion object {
        private const val TAG = "WalletCreationManager"

        const val MAX_MAIN_WALLETS = 5
        const val MAX_DECOY_WALLETS = 5
    }

    /**
     * Creates a wallet from mnemonic.
     *
     * Security model:
     * - Creates a WalletKey, or reuses the one holding the same mnemonic and fingerprint
     * - A new key stores the BIP39 seed derived from mnemonic + passphrase (if saveLocally=true)
     *   or mnemonic + empty passphrase (if saveLocally=false); a reused one is upgraded to the
     *   former when this wallet saves the passphrase and the key held only the latter
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
        passphrase: String,
        savePassphraseLocally: Boolean,
        accountNumber: Int,
        isDecoyMode: Boolean
    ): WalletCreationResult = withContext(Dispatchers.IO) {
        try {
            if (!sessionKeyManager.isSessionActive.value) {
                return@withContext WalletCreationResult.Error(WalletCreationError.NOT_AUTHENTICATED)
            }

            val (canCreate, errorMsg) = canCreateWallet(isDecoyMode)
            if (!canCreate) {
                AppLog.e(TAG) { "Cannot create wallet: $errorMsg" }
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

            val fingerprint = walletResult.fingerprint.lowercase()

            // hasPassphrase = true only if passphrase used AND not saved locally
            // This triggers passphrase dialog on wallet open
            val requiresPassphraseEntry = passphrase.isNotEmpty() && !savePassphraseLocally

            // Derived before anything is written, so a failure here leaves storage untouched
            val isSp = DerivationPaths.getPurpose(fullPath) == 352
            val (spScanPubKeyHex, spSpendPubKeyHex) =
                if (isSp) deriveSilentPaymentPubKeys(mnemonic, passphrase, fullPath, accountNumber) else "" to ""

            val storedKey = storeWalletKey(mnemonic, passphrase, requiresPassphraseEntry, fingerprint, isDecoyMode)
                ?: return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
            val keyId = storedKey.keyId

            val metadata = WalletMetadata(
                id = UUID.randomUUID().toString(),
                name = name,
                derivationPath = fullPath,
                masterFingerprint = fingerprint,  // Normalized to lowercase
                hasPassphrase = requiresPassphraseEntry,
                createdAt = System.currentTimeMillis(),
                accounts = listOf(accountNumber),
                activeAccountNumber = accountNumber,
                keyIds = listOf(keyId),  // Reference to WalletKey
                isSilentPayment = isSp,
                silentPaymentScanPubKey = spScanPubKeyHex,
                silentPaymentSpendPubKey = spSpendPubKeyHex
            )

            // Key material is already saved in the WalletKey above
            if (!persistNewWallet(metadata, isDecoyMode)) {
                // Undo the key step: restore a shared key this wallet rewrote, or delete a key
                // nothing else uses
                val replacedKey = storedKey.replaced
                if (replacedKey != null) {
                    secureStorage.saveWalletKey(replacedKey, isDecoyMode)
                } else {
                    secureStorage.deleteKeyIfUnreferenced(keyId, isDecoyMode)
                }
                return@withContext WalletCreationResult.Error(WalletCreationError.STORAGE_FAILED)
            }

            // Refresh multisig key bindings in case this new key matches a cosigner
            val updatedMultisigs = secureStorage.refreshMultisigKeyBindings(isDecoyMode)
            if (updatedMultisigs > 0) {
                // Reload wallet list to reflect updated multisig bindings
                walletRepository.loadWalletList()
            }

            WalletCreationResult.Success(metadata)
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Create wallet failed: ${e.message}" }
            WalletCreationResult.Error(WalletCreationError.UNKNOWN_ERROR)
        }
    }

    /**
     * Creates a multisig wallet from a parsed configuration.
     *
     * Multisig wallets reference existing WalletKeys via fingerprint matching.
     * At least one local key must match a cosigner fingerprint.
     *
     * @param name Display name for the wallet
     * @param config Parsed multisig configuration from descriptor
     * @return the new wallet id on success, or null on failure
     */
    suspend fun createMultisigWallet(
        name: String,
        config: MultisigConfig,
        isDecoyMode: Boolean
    ): String? = withContext(Dispatchers.IO) {
        try {
            if (!sessionKeyManager.isSessionActive.value) {
                AppLog.e(TAG) { "Cannot create multisig wallet: not authenticated" }
                return@withContext null
            }

            val (canCreate, errorMsg) = canCreateWallet(isDecoyMode)
            if (!canCreate) {
                AppLog.e(TAG) { "Cannot create multisig wallet: $errorMsg" }
                return@withContext null
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
                AppLog.e(TAG) { "Cannot create multisig wallet: no local keys match cosigner fingerprints" }
                return@withContext null
            }

            // Use first local key's fingerprint as the wallet's fingerprint
            // Safe: keyIds is non-empty, so at least one cosigner has isLocal=true
            val localCosigner = updatedCosigners.firstOrNull { it.isLocal }
            if (localCosigner == null) {
                AppLog.e(TAG) { "Cannot create multisig wallet: inconsistent state - keyIds present but no local cosigner" }
                return@withContext null
            }
            val primaryFingerprint = localCosigner.fingerprint.lowercase()

            // Create updated config with keyId references, normalizing all fingerprints
            val updatedConfig = config.copy(
                cosigners = updatedCosigners.map { it.copy(fingerprint = it.fingerprint.lowercase()) },
                localKeyFingerprints = keyIds.mapNotNull { keyId ->
                    secureStorage.loadWalletKey(keyId, isDecoyMode)?.fingerprint?.lowercase()
                }
            )

            val metadata = WalletMetadata(
                id = UUID.randomUUID().toString(),
                name = name,
                derivationPath = "multisig/${config.m}of${config.n}",  // Display only
                masterFingerprint = primaryFingerprint,
                hasPassphrase = false,  // Multisig wallets don't have their own passphrase
                createdAt = System.currentTimeMillis(),
                accounts = listOf(0),
                activeAccountNumber = 0,
                isMultisig = true,
                multisigConfig = updatedConfig,
                keyIds = keyIds  // Direct references to WalletKeys
            )

            // Metadata only - no secrets stored for multisig wallets
            if (!persistNewWallet(metadata, isDecoyMode)) {
                AppLog.e(TAG) { "Failed to save multisig wallet metadata" }
                return@withContext null
            }

            AppLog.d(TAG) { "Multisig wallet created (${config.m}-of-${config.n}) with ${keyIds.size} local key(s)" }
            metadata.id
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Create multisig wallet failed: ${e.message}" }
            null
        }
    }

    private fun canCreateWallet(isDecoyMode: Boolean): Pair<Boolean, String?> {
        val maxWallets = if (isDecoyMode) MAX_DECOY_WALLETS else MAX_MAIN_WALLETS
        val vaultName = if (isDecoyMode) "Decoy" else "Main"
        val currentCount = secureStorage.getWalletCount(isDecoyMode)

        return if (currentCount >= maxWallets) {
            Pair(false, "Maximum of $maxWallets wallets reached for $vaultName vault.")
        } else {
            Pair(true, null)
        }
    }

    /**
     * The key a new wallet uses. [replaced] is the key as it was before this creation rewrote it,
     * kept to restore it if the wallet then fails to save; null when the key wasn't rewritten.
     */
    private class StoredKey(val keyId: String, val replaced: WalletKeys? = null)

    /**
     * Stores the WalletKey for a new wallet, or reuses the one already holding [mnemonic] under
     * [fingerprint] (mnemonic + passphrase). Returns null when a write failed.
     *
     * A new key holds the full seed, or only the base seed when [requiresPassphraseEntry]. A
     * reused key may hold either, depending on the wallets already using it. When this wallet
     * opens straight from the stored seed but the key holds only the base seed, the key is given
     * the full seed: wallets that ask for the passphrase still ask for it, and deleting this
     * wallet drops the passphrase again (PassphraseManager.dropUnneededPassphrase).
     */
    private fun storeWalletKey(
        mnemonic: List<String>,
        passphrase: String,
        requiresPassphraseEntry: Boolean,
        fingerprint: String,
        isDecoyMode: Boolean
    ): StoredKey? {
        val existingKey = findExistingKey(mnemonic, fingerprint, isDecoyMode)
        return when {
            existingKey == null -> {
                // Store seed WITH passphrase (wallet ready to use), or WITHOUT it when it must be
                // entered on open (base seed)
                val seedPassphrase = if (requiresPassphraseEntry) "" else passphrase
                saveNewWalletKey(mnemonic, seedPassphrase, fingerprint, isDecoyMode)
                    ?.let { keyId ->
                        AppLog.d(TAG) { "Created new key" }
                        StoredKey(keyId)
                    }
            }
            requiresPassphraseEntry || passphraseManager.storedSeedMatches(existingKey, fingerprint) -> {
                AppLog.d(TAG) { "Reusing existing key for new wallet" }
                StoredKey(existingKey.keyId)
            }
            saveFullSeed(existingKey, mnemonic, passphrase, isDecoyMode) -> {
                AppLog.d(TAG) { "Reusing existing key, now holding the full seed" }
                StoredKey(existingKey.keyId, replaced = existingKey)
            }
            else -> null
        }
    }

    /**
     * The stored key for this exact [mnemonic] and [fingerprint], if any. The mnemonic is
     * compared too, so a fingerprint collision can never make a wallet share another's key.
     */
    private fun findExistingKey(mnemonic: List<String>, fingerprint: String, isDecoyMode: Boolean): WalletKeys? {
        val phrase = mnemonic.joinToString(" ")
        return secureStorage.loadAllWalletKeys(isDecoyMode).find {
            it.fingerprint.equals(fingerprint, ignoreCase = true) && it.mnemonic == phrase
        }
    }

    /**
     * Rewrites [key] to hold the full seed, [mnemonic] + [passphrase]. Returns false when the
     * write failed. The seed bytes are wiped before returning.
     */
    private fun saveFullSeed(
        key: WalletKeys,
        mnemonic: List<String>,
        passphrase: String,
        isDecoyMode: Boolean
    ): Boolean {
        val seedBytes = MnemonicCode.toSeed(mnemonic, passphrase)
        try {
            return secureStorage.saveWalletKey(key.copy(bip39Seed = seedBytes.toHexString()), isDecoyMode)
        } finally {
            seedBytes.fill(0)
        }
    }

    /**
     * Stores a new WalletKey holding [mnemonic] and the BIP39 seed derived with [seedPassphrase].
     * Returns its keyId, or null when the write failed. The seed bytes are wiped before returning.
     */
    private fun saveNewWalletKey(
        mnemonic: List<String>,
        seedPassphrase: String,
        fingerprint: String,
        isDecoyMode: Boolean
    ): String? {
        val seedBytes = MnemonicCode.toSeed(mnemonic, seedPassphrase)
        try {
            val walletKeys = WalletKeys(
                keyId = UUID.randomUUID().toString(),
                mnemonic = mnemonic.joinToString(" "),
                bip39Seed = seedBytes.toHexString(),
                fingerprint = fingerprint,
                label = secureStorage.getNextKeyLabel(isDecoyMode)
            )
            return if (secureStorage.saveWalletKey(walletKeys, isDecoyMode)) walletKeys.keyId else null
        } finally {
            // Securely wipe seed bytes from memory
            seedBytes.fill(0)
        }
    }

    /**
     * For BIP-352 silent-payment wallets, derive the scan/spend pubkeys (hex) eagerly from the
     * WITH-passphrase seed (independent of savePassphraseLocally — those pubkeys must reflect the
     * wallet's real identity, not the base seed). Cached on metadata so the sp1q… address can be
     * shown while the wallet is locked.
     */
    private fun deriveSilentPaymentPubKeys(
        mnemonic: List<String>,
        passphrase: String,
        fullPath: String,
        accountNumber: Int
    ): Pair<String, String> {
        val realSeed = MnemonicCode.toSeed(mnemonic, passphrase)
        try {
            val master = DeterministicWallet.generate(realSeed)
            val isTestnet = DerivationPaths.isTestnet(fullPath)
            val keys = silentPaymentManager.deriveKeys(master, accountNumber, isTestnet)
            return keys.scanPublicKey.toHex() to keys.spendPublicKey.toHex()
        } finally {
            realSeed.fill(0)
        }
    }

    /**
     * Saves a new wallet's metadata, appends it to the stored display order and publishes it to
     * the in-memory list. Returns false, publishing nothing, when the metadata write fails.
     */
    private fun persistNewWallet(metadata: WalletMetadata, isDecoyMode: Boolean): Boolean {
        if (!secureStorage.saveWalletMetadata(metadata, isDecoyMode)) return false
        val order = secureStorage.loadWalletOrder(isDecoyMode).orEmpty() + metadata.id
        secureStorage.saveWalletOrder(order, isDecoyMode)
        walletList.add(metadata)
        return true
    }
}
