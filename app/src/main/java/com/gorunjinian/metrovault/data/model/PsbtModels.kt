package com.gorunjinian.metrovault.data.model

/**
 * Details extracted from a PSBT for display purposes.
 */
data class PsbtDetails(
    val inputs: List<PsbtInput>,
    val outputs: List<PsbtOutput>,
    val fee: Long?,
    val virtualSize: Int,  // Transaction size in virtual bytes (vBytes)
    val isMultisig: Boolean = false,  // Whether this is a multisig transaction
    val requiredSignatures: Int = 1,  // m in m-of-n multisig (or 1 for single-sig)
    val totalSigners: Int = 1,        // n in m-of-n multisig (or 1 for single-sig)  
    val currentSignatures: Int = 0,   // Current number of signatures across all inputs
    val isReadyToBroadcast: Boolean = false  // True if all inputs have sufficient signatures
)

/**
 * PSBT input details.
 */
data class PsbtInput(
    val address: String,
    val prevTxHash: String,
    val prevTxIndex: Int,
    val value: Long,
    val signatureCount: Int = 0,  // Number of signatures for this input
    val isMultisig: Boolean = false  // Whether this input is multisig
)

/**
 * PSBT output details.
 */
data class PsbtOutput(
    val address: String,
    val value: Long
)

/**
 * Result of PSBT signing operation.
 * Contains the signed PSBT and information about any alternative paths used.
 *
 * @property usedAddressLookupFallback true if Stage 3 (script-derivation fallback
 *   via buildAddressLookup) fired for any input. When true, the incoming PSBT
 *   did not correctly declare the input as belonging to this wallet via
 *   PSBT_IN_BIP32_DERIVATION — MetroVault matched it by deriving addresses
 *   from the loaded seed. Surfaces a user warning in the confirmation UI.
 * @property addressLookupInputIndices The input indices that were signed via
 *   Stage 3 fallback. Empty when `usedAddressLookupFallback` is false.
 */
data class SigningResult(
    val signedPsbt: String,
    val usedAlternativePath: Boolean,
    val alternativePathsUsed: List<String> = emptyList(),
    val usedAddressLookupFallback: Boolean = false,
    val addressLookupInputIndices: List<Int> = emptyList(),
)
