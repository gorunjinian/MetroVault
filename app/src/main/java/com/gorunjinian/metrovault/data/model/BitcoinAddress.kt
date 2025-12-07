package com.gorunjinian.metrovault.data.model

/**
 * Bitcoin address with metadata
 */
data class BitcoinAddress(
    val address: String,
    val derivationPath: String,
    val index: Int,
    val isChange: Boolean,
    val publicKey: String,
    val scriptType: ScriptType
)

/**
 * Address script types
 */
enum class ScriptType {
    P2PKH,          // Legacy (1...)
    P2SH_P2WPKH,    // Nested SegWit (3...)
    P2WPKH,         // Native SegWit (bc1...)
    P2TR            // Taproot (bc1p...)
}

/**
 * Derivation path constants following BIP43/44/49/84/86
 */
object DerivationPaths {
    private const val COIN_TYPE = "0'"

    // BIP86 - Taproot (bc1p...)
    const val TAPROOT = "m/86'/$COIN_TYPE/0'"

    // BIP84 - Native SegWit (bc1...)
    const val NATIVE_SEGWIT = "m/84'/$COIN_TYPE/0'"

    // BIP49 - Nested SegWit (3...)
    const val NESTED_SEGWIT = "m/49'/$COIN_TYPE/0'"

    // BIP44 - Legacy (1...)
    const val LEGACY = "m/44'/$COIN_TYPE/0'"
}
