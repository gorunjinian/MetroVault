package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Service responsible for BIP39 mnemonic operations.
 * Handles mnemonic generation, validation, and fingerprint calculation.
 */
class MnemonicService {

    companion object {
        private const val TAG = "MnemonicService"
    }

    /**
     * Generates a BIP39 mnemonic using system entropy only.
     */
    @Suppress("unused") // Public API for future use/testing
    fun generateMnemonic(wordCount: Int = 24): List<String> {
        return generateMnemonicWithUserEntropy(wordCount, null)
    }

    /**
     * Generates a BIP39 mnemonic, optionally mixing user-provided entropy with system entropy.
     *
     * @param wordCount Number of words (12, 15, 18, 21, or 24)
     * @param userEntropy Optional user-provided entropy (from coin flips, dice rolls, etc.)
     * @return List of mnemonic words
     *
     * Security: User entropy supplements but never replaces system entropy.
     * Even if userEntropy is predictable, the result is cryptographically secure
     * because system entropy from SecureRandom is always mixed in.
     */
    fun generateMnemonicWithUserEntropy(wordCount: Int = 24, userEntropy: ByteArray?): List<String> {
        val entropySize = when (wordCount) {
            12 -> 16  // 128 bits
            15 -> 20  // 160 bits
            18 -> 24  // 192 bits
            21 -> 28  // 224 bits
            24 -> 32  // 256 bits
            else -> 32
        }

        val systemEntropy = ByteArray(entropySize)
        var combinedEntropy: ByteArray? = null
        var finalEntropy: ByteArray? = null

        return try {
            // Always generate full system entropy
            SecureRandom().nextBytes(systemEntropy)

            finalEntropy = if (userEntropy != null && userEntropy.isNotEmpty()) {
                // Mix user entropy with system entropy using SHA-256
                combinedEntropy = userEntropy + systemEntropy
                val hash = MessageDigest.getInstance("SHA-256").digest(combinedEntropy)
                // Take the required number of bytes
                hash.copyOfRange(0, entropySize)
            } else {
                // No user entropy, use system entropy directly
                systemEntropy.copyOf()
            }

            MnemonicCode.toMnemonics(finalEntropy)
        } finally {
            // Securely wipe all entropy from memory
            systemEntropy.fill(0)
            combinedEntropy?.fill(0)
            finalEntropy?.fill(0)
        }
    }

    /**
     * Validates a BIP39 mnemonic phrase.
     */
    fun validateMnemonic(words: List<String>): Boolean {
        return try {
            MnemonicCode.validate(words)
            true
        } catch (_: Exception) {
            // Don't log exception details - might contain partial mnemonic info
            Log.w(TAG, "Mnemonic validation failed")
            false
        }
    }

    /**
     * Calculates the master fingerprint from mnemonic and passphrase.
     * Used for real-time fingerprint preview in passphrase dialogs.
     * 
     * @return 8-character hex fingerprint or null on error
     */
    fun calculateFingerprint(mnemonicWords: List<String>, passphrase: String): String? {
        var seed: ByteArray? = null
        return try {
            seed = MnemonicCode.toSeed(mnemonicWords, passphrase)
            val masterPrivateKey = DeterministicWallet.generate(seed.byteVector())
            BitcoinUtils.computeFingerprintHex(masterPrivateKey.publicKey)
        } catch (_: Exception) {
            Log.w(TAG, "Failed to calculate fingerprint")
            null
        } finally {
            // Securely wipe seed from memory
            seed?.fill(0)
        }
    }
}
