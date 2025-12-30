package com.gorunjinian.metrovault.domain.manager

import android.util.Log
import com.gorunjinian.metrovault.core.crypto.SecureSeedCache
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Manages passphrase input and session seed caching.
 * Handles both single-sig wallet passphrases and multi-sig key passphrases.
 * 
 * Extracted from Wallet.kt to follow single responsibility principle.
 * 
 * Security model:
 * - Passphrases are never stored on disk
 * - Session seeds are computed from mnemonic + passphrase
 * - Seeds are held in SecureSeedCache for the duration of the session
 * - All seeds are wiped when session ends or app backgrounds
 */
class PassphraseManager(
    private val secureStorage: SecureStorage,
    private val bitcoinService: BitcoinService
) {
    companion object {
        private const val TAG = "PassphraseManager"
    }

    /** Session seeds for wallets (keyed by walletId) */
    val sessionSeeds = SecureSeedCache()

    /** Session seeds for WalletKeys (keyed by keyId, used by multi-sig) */
    val sessionKeySeeds = SecureSeedCache()

    /**
     * Checks if a wallet needs passphrase re-entry.
     * A wallet needs passphrase if hasPassphrase=true and we don't have a session seed.
     */
    fun needsPassphraseInput(walletId: String, isDecoyMode: Boolean): Boolean {
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
    suspend fun setWalletPassphrase(walletId: String, passphrase: String, isDecoyMode: Boolean) =
        withContext(Dispatchers.IO) {
            val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return@withContext
            val keyId = metadata.keyIds.firstOrNull() ?: return@withContext
            val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext
            val mnemonic = walletKey.mnemonic.split(" ")

            val seedBytes = MnemonicCode.toSeed(mnemonic, passphrase)
            try {
                val seedHex = seedBytes.toHexString()
                sessionSeeds.store(walletId, seedHex)
                Log.d(TAG, "Session seed set for wallet: $walletId")
            } finally {
                seedBytes.fill(0)
            }
        }

    /**
     * Gets the session seed for a wallet (if passphrase was entered this session).
     */
    fun getSessionSeed(walletId: String): String? = sessionSeeds.get(walletId)

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
     * Checks if a WalletKey needs passphrase input.
     * A key needs passphrase if ANY single-sig wallet using that key has hasPassphrase=true
     * and we don't have a session seed for that key yet.
     */
    fun keyNeedsPassphraseInput(keyId: String, isDecoyMode: Boolean): Boolean {
        if (sessionKeySeeds.containsKey(keyId)) return false

        val allWallets = secureStorage.loadAllWalletMetadata(isDecoyMode)
        return allWallets.any { metadata ->
            !metadata.isMultisig &&
            metadata.keyIds.contains(keyId) &&
            metadata.hasPassphrase
        }
    }

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
            if (keyNeedsPassphraseInput(keyId, isDecoyMode)) {
                val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode)
                walletKey?.let {
                    KeyPassphraseInfo(
                        keyId = keyId,
                        label = it.label.ifEmpty { "Key ${it.fingerprint.take(8)}" },
                        fingerprint = it.fingerprint,
                        mnemonic = it.mnemonic.split(" ")
                    )
                }
            } else null
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
                sessionKeySeeds.store(keyId, seedHex)
                Log.d(TAG, "Session seed set for key: $keyId")
            } finally {
                seedBytes.fill(0)
            }
        }

    /**
     * Gets the session seed for a WalletKey (if passphrase was entered this session).
     */
    fun getSessionKeySeed(keyId: String): String? = sessionKeySeeds.get(keyId)

    /**
     * Gets the mnemonic for a WalletKey by ID.
     */
    suspend fun getMnemonicForKey(keyId: String, isDecoyMode: Boolean): List<String>? = 
        withContext(Dispatchers.IO) {
            try {
                val walletKey = secureStorage.loadWalletKey(keyId, isDecoyMode) ?: return@withContext null
                walletKey.mnemonic.split(" ")
            } catch (e: Exception) {
                Log.e(TAG, "Failed to get mnemonic for key: ${e.message}")
                null
            }
        }

    /**
     * Checks if a multi-sig wallet needs passphrase input for any of its local keys.
     */
    fun multisigNeedsPassphraseInput(walletId: String, isDecoyMode: Boolean): Boolean {
        val metadata = secureStorage.loadWalletMetadata(walletId, isDecoyMode) ?: return false
        if (!metadata.isMultisig) return false
        return metadata.keyIds.any { keyId -> keyNeedsPassphraseInput(keyId, isDecoyMode) }
    }

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

            val sessionSeed = sessionKeySeeds.get(keyId)

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
                Log.e(TAG, "Failed to get mnemonic for wallet: ${e.message}")
                null
            }
        }

    /**
     * Clears all session seeds. Call when session ends.
     */
    fun clearAll() {
        val walletCount = sessionSeeds.size
        val keyCount = sessionKeySeeds.size
        sessionSeeds.clear()
        sessionKeySeeds.clear()
        Log.d(TAG, "Cleared $walletCount wallet seed(s) + $keyCount key seed(s)")
    }
}
