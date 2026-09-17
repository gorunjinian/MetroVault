package com.gorunjinian.metrovault.data.model

import com.gorunjinian.vaultovich.ScriptType

/**
 * The address formats a single-seed wallet can derive from, keyed by BIP purpose. Covers the four
 * script types plus BIP-352 Silent Payments, which is a wallet kind rather than a script type (its
 * outputs are Taproot) but sits alongside them in the wizard's "Address Type" picker and on the
 * "Address Type & Network" screen. [scriptType] is null for [SILENT_PAYMENTS] for that reason.
 */
enum class AddressFormat(val purpose: Int, val displayName: String, val scriptType: ScriptType?) {
    TAPROOT(86, "Taproot", ScriptType.P2TR),
    NATIVE_SEGWIT(84, "Native SegWit", ScriptType.P2WPKH),
    NESTED_SEGWIT(49, "Nested SegWit", ScriptType.P2SH_P2WPKH),
    LEGACY(44, "Legacy", ScriptType.P2PKH),
    SILENT_PAYMENTS(352, "Silent Payments", null);

    val isSilentPayment: Boolean get() = this == SILENT_PAYMENTS

    /** Address prefix users will see, e.g. `bc1q` on mainnet or `tb1q` on testnet. */
    fun examplePrefix(testnet: Boolean): String = when (this) {
        TAPROOT -> if (testnet) "tb1p" else "bc1p"
        NATIVE_SEGWIT -> if (testnet) "tb1q" else "bc1q"
        NESTED_SEGWIT -> if (testnet) "2" else "3"
        LEGACY -> if (testnet) "m/n" else "1"
        SILENT_PAYMENTS -> if (testnet) "tsp1q" else "sp1q"
    }

    /** Human-readable label with prefix, e.g. "Native SegWit (tb1q…)". */
    fun label(testnet: Boolean): String = "$displayName (${examplePrefix(testnet)}…)"

    companion object {
        /**
         * Format for a derivation path by its purpose. Unknown purposes fall back to Native SegWit,
         * matching [DerivationPaths.getScriptType] and [DerivationPaths.withAccountNumber].
         */
        fun fromPath(path: String): AddressFormat {
            val purpose = DerivationPaths.getPurpose(path)
            return entries.firstOrNull { it.purpose == purpose } ?: NATIVE_SEGWIT
        }
    }
}
