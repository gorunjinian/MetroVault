package com.gorunjinian.metrovault.data.model

/**
 * Bitcoin address with metadata.
 * 
 * ScriptType and DerivationPaths have been extracted to separate files
 * in the same package for better organization.
 * 
 * @see ScriptType
 * @see DerivationPaths
 */
data class BitcoinAddress(
    val address: String,
    val derivationPath: String,
    val index: Int,
    val isChange: Boolean,
    val publicKey: String,
    val scriptType: ScriptType
)
