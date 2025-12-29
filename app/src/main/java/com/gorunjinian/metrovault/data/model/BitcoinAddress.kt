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
 * Supports both mainnet (coin_type = 0') and testnet (coin_type = 1')
 */
object DerivationPaths {
    // Coin type constants per BIP-44
    private const val COIN_TYPE_MAINNET = "0'"
    private const val COIN_TYPE_TESTNET = "1'"

    // === Mainnet paths (coin_type = 0') ===
    
    // BIP86 - Taproot (bc1p...)
    const val TAPROOT = "m/86'/$COIN_TYPE_MAINNET/0'"

    // BIP84 - Native SegWit (bc1q...)
    const val NATIVE_SEGWIT = "m/84'/$COIN_TYPE_MAINNET/0'"

    // BIP49 - Nested SegWit (3...)
    const val NESTED_SEGWIT = "m/49'/$COIN_TYPE_MAINNET/0'"

    // BIP44 - Legacy (1...)
    const val LEGACY = "m/44'/$COIN_TYPE_MAINNET/0'"

    // === Testnet paths (coin_type = 1') ===
    
    // BIP86 - Taproot (tb1p...)
    const val TAPROOT_TESTNET = "m/86'/$COIN_TYPE_TESTNET/0'"

    // BIP84 - Native SegWit (tb1q...)
    const val NATIVE_SEGWIT_TESTNET = "m/84'/$COIN_TYPE_TESTNET/0'"

    // BIP49 - Nested SegWit (2...)
    const val NESTED_SEGWIT_TESTNET = "m/49'/$COIN_TYPE_TESTNET/0'"

    // BIP44 - Legacy (m/n...)
    const val LEGACY_TESTNET = "m/44'/$COIN_TYPE_TESTNET/0'"

    // === Dynamic account number builders ===
    
    /** Build Taproot path with custom account number and optional testnet */
    fun taproot(account: Int = 0, testnet: Boolean = false): String {
        val coinType = if (testnet) COIN_TYPE_TESTNET else COIN_TYPE_MAINNET
        return "m/86'/$coinType/$account'"
    }
    
    /** Build Native SegWit path with custom account number and optional testnet */
    fun nativeSegwit(account: Int = 0, testnet: Boolean = false): String {
        val coinType = if (testnet) COIN_TYPE_TESTNET else COIN_TYPE_MAINNET
        return "m/84'/$coinType/$account'"
    }
    
    /** Build Nested SegWit path with custom account number and optional testnet */
    fun nestedSegwit(account: Int = 0, testnet: Boolean = false): String {
        val coinType = if (testnet) COIN_TYPE_TESTNET else COIN_TYPE_MAINNET
        return "m/49'/$coinType/$account'"
    }
    
    /** Build Legacy path with custom account number and optional testnet */
    fun legacy(account: Int = 0, testnet: Boolean = false): String {
        val coinType = if (testnet) COIN_TYPE_TESTNET else COIN_TYPE_MAINNET
        return "m/44'/$coinType/$account'"
    }

    /**
     * BIP48 script type constants:
     * - 1' = P2SH-P2WSH (Ypub/Upub)
     * - 2' = P2WSH native segwit (Zpub/Vpub)
     */
    enum class Bip48ScriptType(val value: String) {
        P2SH_P2WSH("1'"),
        P2WSH("2'")
    }

    /**
     * Build BIP48 multisig path: m/48'/coin'/account'/script_type'
     *
     * @param account Account number (default 0)
     * @param testnet Whether to use testnet coin type
     * @param scriptType BIP48 script type (P2WSH or P2SH-P2WSH)
     * @return Derivation path string
     */
    fun bip48(account: Int = 0, testnet: Boolean = false, scriptType: Bip48ScriptType = Bip48ScriptType.P2WSH): String {
        val coinType = if (testnet) COIN_TYPE_TESTNET else COIN_TYPE_MAINNET
        return "m/48'/$coinType/$account'/${scriptType.value}"
    }

    // === Path parsing utilities ===

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

    /** Get coin type from path, e.g. "m/84'/1'/0'" → 1 (testnet) */
    fun getCoinType(path: String): Int {
        val regex = """m/\d+'/(\d+)'""".toRegex()
        return regex.find(path)?.groupValues?.get(1)?.toIntOrNull() ?: 0
    }

    /** Check if a derivation path is for testnet (coin_type = 1') */
    fun isTestnet(path: String): Boolean = getCoinType(path) == 1

    /** Build path with specific account number, preserving the coin type from base path */
    fun withAccountNumber(basePath: String, account: Int): String {
        val testnet = isTestnet(basePath)
        return when (getPurpose(basePath)) {
            86 -> taproot(account, testnet)
            84 -> nativeSegwit(account, testnet)
            49 -> nestedSegwit(account, testnet)
            44 -> legacy(account, testnet)
            else -> nativeSegwit(account, testnet)
        }
    }

    /** Get the script type for a derivation path based on its purpose number */
    fun getScriptType(path: String): ScriptType {
        return when (getPurpose(path)) {
            86 -> ScriptType.P2TR
            84 -> ScriptType.P2WPKH
            49 -> ScriptType.P2SH_P2WPKH
            44 -> ScriptType.P2PKH
            else -> ScriptType.P2WPKH  // Default to native segwit
        }
    }
}
