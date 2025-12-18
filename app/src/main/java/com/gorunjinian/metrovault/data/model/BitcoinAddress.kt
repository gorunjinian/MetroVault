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

    // === Dynamic account number builders ===
    
    /** Build Taproot path with custom account number */
    fun taproot(account: Int = 0) = "m/86'/$COIN_TYPE/$account'"
    
    /** Build Native SegWit path with custom account number */
    fun nativeSegwit(account: Int = 0) = "m/84'/$COIN_TYPE/$account'"
    
    /** Build Nested SegWit path with custom account number */
    fun nestedSegwit(account: Int = 0) = "m/49'/$COIN_TYPE/$account'"
    
    /** Build Legacy path with custom account number */
    fun legacy(account: Int = 0) = "m/44'/$COIN_TYPE/$account'"

    /** Extract account number from path, e.g. "m/84'/0'/5'" → 5 */
    fun getAccountNumber(path: String): Int {
        val regex = """m/\d+'/\d+'/(\d+)'""".toRegex()
        return regex.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Get purpose number from path, e.g. "m/84'/0'/5'" → 84 */
    fun getPurpose(path: String): Int {
        val regex = """m/(\d+)'""".toRegex()
        return regex.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 84
    }

    /** Build path with specific account number based on purpose of base path */
    fun withAccountNumber(basePath: String, account: Int): String {
        return when (getPurpose(basePath)) {
            86 -> taproot(account)
            84 -> nativeSegwit(account)
            49 -> nestedSegwit(account)
            44 -> legacy(account)
            else -> nativeSegwit(account)
        }
    }
}
