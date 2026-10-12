package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.core.crypto.SecureSeedCache
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.vaultovich.MnemonicCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages passphrase input and session seed caching.
 * Handles both single-sig wallet passphrases and multi-sig key passphrases.
 * 
 * Session seeds are cached by keyId (not walletId) because:
 * - The seed is fundamentally tied to a key (mnemonic + passphrase), not a wallet
 * - Multiple wallets can share the same key
 * - Multi-sig signing needs to access seeds by keyId
 * 
 * Security model:
 * - Passphrases are never stored on disk
 * - Session seeds are computed from mnemonic + passphrase
 * - Seeds are held in SecureSeedCache for the duration of the session
 * - All seeds are wiped when session ends or app backgrounds
 *
 * A key's stored seed is either its full seed (mnemonic + passphrase) or, when the passphrase must
 * not be kept on the device, its base seed (mnemonic alone). Its fingerprint is always the full
 * one, so [storedSeedMatches] tells the two apart. A key gets the full seed when a wallet that
 * saves its passphrase is created on it, keeps it while [storedSeedInUse], and is put back on the
 * base seed by [dropUnneededPassphrase] after.
 */
class PassphraseManager(
    private val secureStorage: SecureStorage,
    private val bitcoinService: BitcoinService
) {
    companion object {
        private const val TAG = "PassphraseManager"

        /**
         * Whether any of [wallets] that use [keyId] opens or signs from its stored seed without
         * asking: a single-sig wallet that doesn't ask for its passphrase, or any multisig
         * wallet. While one does, a passphrase saved in that seed stays. A multisig would ask
         * for the passphrase once it is dropped, but the user may rely on the saved one and no
         * longer remember it.
         */
        internal fun storedSeedInUse(keyId: String, wallets: List<WalletMetadata>): Boolean =
            wallets.any { keyId in it.keyIds && (it.isMultisig || !it.hasPassphrase) }
    }

    /** Session seeds, keyed by keyId (works for both single-sig and multi-sig) */
    private val sessionSeeds = SecureSeedCache()

    /**
     * Whether [key]'s stored seed is the one behind [fingerprint]. False when it holds only the
     * base seed, which needs the passphrase entered before it opens that wallet.
     */
    fun storedSeedMatches(key: WalletKeys, fingerprint: String): Boolean =
        bitcoinService.calculateFingerprintFromSeed(key.bip39Seed).equals(fingerprint, ignoreCase = true)

    /**
     * Checks if a single-sig wallet needs passphrase re-entry: there is no session seed for its
     * key, and either the wallet asks for its passphrase (hasPassphrase=true) or its stored seed
     * lacks it. The second case covers a wallet that saves its passphrase but shares a key that
     * an older version left holding only the base seed.
     */
    fun needsPassphraseInput(walletId: String, isDecoyMode: Boolean): Boolean {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false
        if (metadata.isMultisig) return false
        val keyId = metadata.keyIds.firstOrNull() ?: return false
        if (sessionSeeds.containsKey(keyId)) return false
        if (metadata.hasPassphrase) return true

        // Unreadable key: opening fails anyway, since loadWalletFull checks the same seed
        val walletKey = loadKeyOrNull(keyId, isDecoyMode) ?: return false
        return !storedSeedMatches(walletKey, metadata.masterFingerprint)
    }

    /**
     * Loads a key for a passphrase check, or null when it is missing or the session ended
     * meanwhile (SecureStorage then throws), so a check started from the UI never crashes it.
     */
    private fun loadKeyOrNull(keyId: String, isDecoyMode: Boolean): WalletKeys? = try {
        secureStorage.loadWalletKey(keyId, isDecoyMode)
    } catch (e: IllegalStateException) {
        AppLog.w(TAG) { "Cannot load key: ${e.message}" }
        null
    }

    /**
     * Sets the session seed for a wallet's key by computing it from the entered passphrase.
     * Used when user re-enters passphrase after app restart, or adds one via the Passphrase
     * shortcut. Always starts from the stored mnemonic, so any locally saved passphrase (baked
     * into the stored seed) is ignored while this session seed exists.
     *
     * @param walletId Wallet ID
     * @param passphrase User-entered passphrase
     */
    suspend fun setWalletPassphrase(walletId: String, passphrase: String, isDecoyMode: Boolean) =
        withContext(Dispatchers.IO) {
            val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return@withContext
            val keyId = metadata.keyIds.firstOrNull() ?: return@withContext
            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext
            val mnemonic = walletKey.mnemonic.split(" ")

            val seedBytes = MnemonicCode.toSeed(mnemonic, passphrase)
            try {
                val seedHex = seedBytes.toHexString()
                sessionSeeds.store(keyId, seedHex)
                AppLog.d(TAG) { "Session seed activated" }
            } finally {
                seedBytes.fill(0)
            }
        }

    /**
     * Gets the session seed for a wallet's key (if passphrase was entered this session).
     */
    fun getSessionSeed(walletId: String, isDecoyMode: Boolean): String? {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return null
        val keyId = metadata.keyIds.firstOrNull() ?: return null
        return sessionSeeds.get(keyId)
    }

    /**
     * Puts [keyId] back on its base seed once no wallet reads the stored one ([storedSeedInUse]):
     * every wallet still using it asks for the passphrase, so a passphrase saved for a wallet
     * that has since gone must not stay on disk. Call after a wallet stops using the key.
     *
     * A no-op when no wallet uses the key (the caller deletes it) or it already holds the base
     * seed. Best effort: a failure is logged, never thrown, since the caller's change is done;
     * [dropUnneededPassphrases] at the next login finishes the job. Call it off the main thread.
     */
    fun dropUnneededPassphrase(keyId: String, isDecoyMode: Boolean) {
        try {
            dropIfUnneeded(keyId, secureStorage.loadAllWalletMetadata(isDecoyMode), isDecoyMode)
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to drop unneeded passphrase: ${e.message}" }
        }
    }

    /**
     * Runs [dropUnneededPassphrase] over every key in use, for drops that were interrupted (the
     * app went to the background mid-delete) or that an older version never made. Called at
     * login; cheap when there is nothing to drop.
     */
    fun dropUnneededPassphrases(isDecoyMode: Boolean) {
        try {
            val wallets = secureStorage.loadAllWalletMetadata(isDecoyMode)
            for (keyId in wallets.flatMap { it.keyIds }.toSet()) {
                dropIfUnneeded(keyId, wallets, isDecoyMode)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to drop unneeded passphrases: ${e.message}" }
        }
    }

    private fun dropIfUnneeded(keyId: String, wallets: List<WalletMetadata>, isDecoyMode: Boolean) {
        if (wallets.none { keyId in it.keyIds } || storedSeedInUse(keyId, wallets)) return
        val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return
        // Only a stored full seed has a passphrase to drop. Checked first, as it is much cheaper
        // than deriving the base seed
        if (!storedSeedMatches(walletKey, walletKey.fingerprint)) return

        val baseSeed = MnemonicCode.toSeed(walletKey.mnemonic.split(" "), "")
        try {
            val baseSeedHex = baseSeed.toHexString()
            if (baseSeedHex.equals(walletKey.bip39Seed, ignoreCase = true)) return  // No passphrase
            if (secureStorage.saveWalletKey(walletKey.copy(bip39Seed = baseSeedHex), isDecoyMode)) {
                AppLog.d(TAG) { "Dropped a saved passphrase no wallet uses" }
            }
        } finally {
            baseSeed.fill(0)
        }
    }

    // ==================== Multi-sig Key Passphrase Support ====================

    /**
     * Data class representing a key that needs passphrase input.
     */
    data class KeyPassphraseInfo(
        val keyId: String,
        val label: String,
        val fingerprint: String,
        val mnemonic: List<String>
    )

    /**
     * Checks if a WalletKey needs passphrase input before a multisig wallet signs with it: there
     * is no session seed for it, and its stored seed is only the base seed.
     */
    private fun keyNeedsPassphraseInput(key: WalletKeys): Boolean =
        !sessionSeeds.containsKey(key.keyId) && !storedSeedMatches(key, key.fingerprint)

    /**
     * Gets keys needing passphrase input for a multi-sig wallet.
     * Returns empty list if no keys need passphrase.
     */
    suspend fun getKeysNeedingPassphrase(
        walletId: String,
        isDecoyMode: Boolean
    ): List<KeyPassphraseInfo> = withContext(Dispatchers.IO) {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode)
            ?: return@withContext emptyList()

        if (!metadata.isMultisig) return@withContext emptyList()

        metadata.keyIds.mapNotNull { keyId ->
            val walletKey = loadKeyOrNull(keyId, isDecoyMode)
            if (walletKey == null || !keyNeedsPassphraseInput(walletKey)) return@mapNotNull null
            KeyPassphraseInfo(
                keyId = keyId,
                label = walletKey.label.ifEmpty { "Key ${walletKey.fingerprint.take(8)}" },
                fingerprint = walletKey.fingerprint,
                mnemonic = walletKey.mnemonic.split(" ")
            )
        }
    }

    /**
     * Sets the session seed for a WalletKey by computing it from the entered passphrase.
     * Used when user enters passphrase for a multi-sig key.
     */
    suspend fun setKeyPassphrase(keyId: String, passphrase: String, isDecoyMode: Boolean) =
        withContext(Dispatchers.IO) {
            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext
            val mnemonic = walletKey.mnemonic.split(" ")

            val seedBytes = MnemonicCode.toSeed(mnemonic, passphrase)
            try {
                val seedHex = seedBytes.toHexString()
                sessionSeeds.store(keyId, seedHex)
                AppLog.d(TAG) { "Session seed activated" }
            } finally {
                seedBytes.fill(0)
            }
        }

    /**
     * Gets the session seed for a WalletKey (if passphrase was entered this session).
     */
    fun getSessionKeySeed(keyId: String): String? = sessionSeeds.get(keyId)

    /**
     * Data class representing a calculated fingerprint for a multi-sig local key.
     */
    data class CalculatedKeyFingerprint(
        val keyId: String,
        val originalFingerprint: String,
        val calculatedFingerprint: String,
        val isMismatch: Boolean
    )

    /**
     * Gets calculated fingerprints for all local keys in a multi-sig wallet.
     * If a session key seed exists (passphrase was entered), calculates fingerprint from that.
     * Otherwise, uses the stored fingerprint.
     */
    suspend fun getCalculatedKeyFingerprints(
        walletId: String,
        isDecoyMode: Boolean
    ): List<CalculatedKeyFingerprint> = withContext(Dispatchers.IO) {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) 
            ?: return@withContext emptyList()

        if (!metadata.isMultisig) return@withContext emptyList()

        metadata.keyIds.mapNotNull { keyId ->
            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@mapNotNull null
            val originalFingerprint = walletKey.fingerprint.lowercase()

            val sessionSeed = sessionSeeds.get(keyId)

            val calculatedFingerprint = if (sessionSeed != null) {
                bitcoinService.calculateFingerprintFromSeed(sessionSeed)?.lowercase() 
                    ?: originalFingerprint
            } else {
                originalFingerprint
            }

            CalculatedKeyFingerprint(
                keyId = keyId,
                originalFingerprint = originalFingerprint,
                calculatedFingerprint = calculatedFingerprint,
                isMismatch = calculatedFingerprint != originalFingerprint
            )
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
     */
    suspend fun getMnemonicForWallet(walletId: String, isDecoyMode: Boolean): List<String>? = 
        withContext(Dispatchers.IO) {
            try {
                val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) 
                    ?: return@withContext null
                val keyId = metadata.keyIds.firstOrNull() ?: return@withContext null
                val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) 
                    ?: return@withContext null
                walletKey.mnemonic.split(" ")
            } catch (e: Exception) {
                AppLog.e(TAG) { "Failed to get mnemonic for wallet: ${e.message}" }
                null
            }
        }

    /**
     * Clears all session seeds. Call when session ends.
     */
    fun clearAll() {
        val count = sessionSeeds.size
        sessionSeeds.clear()
        AppLog.d(TAG) { "Cleared $count session seed(s)" }
    }
}
