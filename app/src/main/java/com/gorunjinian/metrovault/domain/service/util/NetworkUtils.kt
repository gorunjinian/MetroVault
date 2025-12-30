package com.gorunjinian.metrovault.domain.service.util

import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Centralized network (mainnet/testnet) detection utilities.
 *
 * Provides a single source of truth for determining whether keys,
 * derivation paths, or xpub strings belong to testnet or mainnet.
 */
object NetworkUtils {

    // ==================== Testnet xpub string prefixes ====================

    private val TESTNET_XPUB_PREFIXES = listOf(
        "tpub",  // BIP44 testnet public
        "tprv",  // BIP44 testnet private
        "upub",  // BIP49 testnet public (nested segwit)
        "uprv",  // BIP49 testnet private
        "vpub",  // BIP84 testnet public (native segwit)
        "vprv"   // BIP84 testnet private
        // Note: Uppercase Vpub/Upub (BIP48 multisig) are also testnet but handled by prefix int check
    )

    // ==================== Derivation Path Detection ====================

    /**
     * Check if a derivation path is for testnet by examining the coin_type.
     * Per BIP-44, coin_type = 0' is mainnet, coin_type = 1' is testnet.
     *
     * @param derivationPath Path in format "m/84'/1'/0'" or "48h/1h/0h/2h"
     * @return true if coin_type = 1 (testnet)
     */
    fun isTestnetPath(derivationPath: String): Boolean {
        // Handle both formats: with or without 'm/' prefix
        val normalizedPath = derivationPath.removePrefix("m/").removePrefix("M/")
        val pathParts = normalizedPath.split("/").filter { it.isNotEmpty() }

        // coin_type is the second element (purpose/coin_type/account)
        if (pathParts.size >= 2) {
            val coinType = pathParts[1]
                .replace("'", "")
                .replace("h", "")
                .replace("H", "")
            return coinType == "1"
        }
        return false
    }

    // ==================== Xpub String Detection ====================

    /**
     * Check if an xpub/xprv string is for testnet based on its prefix.
     *
     * @param xpub Extended public or private key string (e.g., "tpub...", "xpub...")
     * @return true if the prefix indicates testnet
     */
    fun isTestnetXpub(xpub: String): Boolean {
        val lower = xpub.lowercase()
        return TESTNET_XPUB_PREFIXES.any { lower.startsWith(it) }
    }

    // ==================== Version Prefix Detection ====================

    /**
     * Check if a version prefix byte (as Int) indicates testnet.
     * Covers both single-sig (BIP44/49/84) and multisig (BIP48) prefixes.
     *
     * @param prefix The 4-byte version prefix as an Int
     * @return true if the prefix is a testnet prefix
     */
    fun isTestnetPrefix(prefix: Int): Boolean {
        return prefix == DeterministicWallet.tpub ||
               prefix == DeterministicWallet.tprv ||
               prefix == DeterministicWallet.upub ||
               prefix == DeterministicWallet.uprv ||
               prefix == DeterministicWallet.vpub ||
               prefix == DeterministicWallet.vprv ||
               // BIP48 multisig testnet prefixes
               prefix == Bip48MultisigPrefixes.Vpub ||
               prefix == Bip48MultisigPrefixes.Vprv ||
               prefix == Bip48MultisigPrefixes.Upub ||
               prefix == Bip48MultisigPrefixes.Uprv
    }

    // ==================== Convenience Methods ====================

    /**
     * Determines testnet status from multiple sources, checking in order:
     * 1. Derivation path (if provided)
     * 2. Xpub string prefix (if provided)
     *
     * @param derivationPath Optional derivation path to check
     * @param xpub Optional xpub string to check
     * @return true if any indicator suggests testnet, false otherwise
     */
    fun isTestnet(derivationPath: String? = null, xpub: String? = null): Boolean {
        // Check derivation path first (most reliable)
        if (!derivationPath.isNullOrBlank() && isTestnetPath(derivationPath)) {
            return true
        }

        // Check xpub prefix as fallback
        if (!xpub.isNullOrBlank() && isTestnetXpub(xpub)) {
            return true
        }

        return false
    }
}
