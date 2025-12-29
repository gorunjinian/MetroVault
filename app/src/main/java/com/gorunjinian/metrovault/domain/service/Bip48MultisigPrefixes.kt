package com.gorunjinian.metrovault.domain.service

import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * BIP48 SLIP-0132 Multisig Extended Key Prefixes.
 *
 * These are different version bytes from single-sig prefixes and are used by
 * Sparrow, Electrum, and other wallets for multisig xpubs.
 *
 * The capital letter variants (Zpub vs zpub) encode different script types:
 * - Lowercase (zpub/ypub): BIP84/BIP49 single-sig
 * - Uppercase (Zpub/Ypub): BIP48 multisig
 *
 * Reference: https://github.com/satoshilabs/slips/blob/master/slip-0132.md
 */
object Bip48MultisigPrefixes {

    // P2WSH multisig mainnet (Zpub/Zprv) - BIP48 script type 2'
    const val Zprv: Int = 0x02aa7a99
    const val Zpub: Int = 0x02aa7ed3

    // P2SH-P2WSH multisig mainnet (Ypub/Yprv) - BIP48 script type 1'
    const val Yprv: Int = 0x0295b005
    const val Ypub: Int = 0x0295b43f

    // P2WSH multisig testnet (Vpub/Vprv) - BIP48 script type 2'
    const val Vprv: Int = 0x02575048
    const val Vpub: Int = 0x02575483

    // P2SH-P2WSH multisig testnet (Upub/Uprv) - BIP48 script type 1'
    const val Uprv: Int = 0x024285b5
    const val Upub: Int = 0x024289ef

    /**
     * All valid public key prefixes (single-sig + multisig).
     */
    val allValidXpubPrefixes = listOf(
        // Single-sig prefixes from DeterministicWallet
        DeterministicWallet.xpub, DeterministicWallet.ypub, DeterministicWallet.zpub,
        DeterministicWallet.tpub, DeterministicWallet.upub, DeterministicWallet.vpub,
        // BIP48 multisig prefixes
        Zpub, Ypub, Vpub, Upub
    )

    /**
     * Check if prefix is a testnet xpub prefix (single-sig or multisig).
     */
    fun isTestnetPrefix(prefix: Int): Boolean {
        return prefix == DeterministicWallet.tpub ||
               prefix == DeterministicWallet.upub ||
               prefix == DeterministicWallet.vpub ||
               prefix == Vpub ||
               prefix == Upub
    }

    /**
     * Check if prefix is a valid xpub prefix (single-sig or multisig).
     */
    fun isValidXpubPrefix(prefix: Int): Boolean {
        return prefix in allValidXpubPrefixes
    }
}
