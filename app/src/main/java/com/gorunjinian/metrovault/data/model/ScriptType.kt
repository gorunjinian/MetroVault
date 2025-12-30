package com.gorunjinian.metrovault.data.model

/**
 * Address script types for Bitcoin transactions.
 */
enum class ScriptType {
    P2PKH,          // Legacy (1...)
    P2SH_P2WPKH,    // Nested SegWit (3...)
    P2WPKH,         // Native SegWit (bc1...)
    P2TR            // Taproot (bc1p...)
}
