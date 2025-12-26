package com.gorunjinian.metrovault.domain.service

import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.BlockHash
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.data.model.DerivationPaths

/**
 * Shared utility functions used across Bitcoin services.
 */
object BitcoinUtils {

    /**
     * Gets the appropriate chain hash (genesis block hash) based on network type.
     * Used for address generation and PSBT parsing.
     */
    fun getChainHash(isTestnet: Boolean): BlockHash {
        return if (isTestnet) Block.Testnet4GenesisBlock.hash else Block.LivenetGenesisBlock.hash
    }

    /**
     * Gets the chain hash from a derivation path by checking the coin type.
     */
    fun getChainHashFromPath(derivationPath: String): BlockHash {
        return getChainHash(DerivationPaths.isTestnet(derivationPath))
    }

    /**
     * Computes the master key fingerprint as an 8-character hex string.
     * This is the first 4 bytes of hash160(public key).
     */
    fun computeFingerprintHex(publicKey: PublicKey): String {
        val hash160 = Crypto.hash160(publicKey.value.toByteArray())
        return hash160.take(4).joinToString("") { "%02x".format(it) }
    }

    /**
     * Computes the master key fingerprint as an unsigned Long.
     * Used for BIP-174 PSBT matching (big-endian, 4 bytes).
     */
    fun computeFingerprintLong(publicKey: PublicKey): Long {
        val hash160 = Crypto.hash160(publicKey.value.toByteArray())
        return ((hash160[0].toLong() and 0xFF) shl 24) or
               ((hash160[1].toLong() and 0xFF) shl 16) or
               ((hash160[2].toLong() and 0xFF) shl 8) or
               (hash160[3].toLong() and 0xFF)
    }

    /**
     * Parses a BIP32 derivation path string into a list of path indices.
     * Validates path format before parsing.
     *
     * @param pathString Path in format "m/44'/0'/0'" or "m/44h/0h/0h"
     * @return List of path indices (with hardened flag applied)
     * @throws IllegalArgumentException if path format is invalid
     */
    fun parseDerivationPath(pathString: String): List<Long> {
        // Validate path format with regex
        val pathRegex = Regex("""^m(/\d+['h]?)+$""")
        require(pathString.matches(pathRegex)) {
            "Invalid derivation path format: $pathString. Expected format: m/44'/0'/0'"
        }

        val path = pathString.removePrefix("m/").split("/")
        return path.map { segment ->
            val isHardened = segment.endsWith("'") || segment.endsWith("h")
            val indexStr = segment.removeSuffix("'").removeSuffix("h")

            // Validate index is a valid number
            val index = indexStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid path index: $segment")

            // Validate index range (BIP32 allows 0 to 2^31-1)
            require(index in 0..2147483647L) {
                "Path index out of range: $index"
            }

            if (isHardened) DeterministicWallet.hardened(index) else index
        }
    }
}
