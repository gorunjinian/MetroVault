package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.lib.bitcoin.UpdateFailure

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
    val isReadyToBroadcast: Boolean = false,  // True if all inputs have sufficient signatures
    // BIP-352 silent payments: the nominal sp1q… → derived bc1p… mapping for SP recipient
    // outputs, populated when the active wallet's keys were available to resolve them.
    val silentPaymentResolutions: List<SilentPaymentResolution> = emptyList()
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
 *
 * @property address the on-chain address shown to the user. For a silent-payment recipient this is
 *   the derived `bc1p…` taproot address (once resolved); the user is committing to this on-chain.
 * @property silentPaymentNominal the nominal `sp1q…` address the user intends to pay, when this
 *   output is a silent-payment recipient; `null` for ordinary outputs.
 */
data class PsbtOutput(
    val address: String,
    val value: Long,
    val silentPaymentNominal: String? = null
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
 * @property refusals Inputs that matched this wallet but which MetroVault declined to
 *   sign. Non-empty here means the PSBT is only *partially* signed — the user must be
 *   warned, since a partially-signed PSBT looks like a success otherwise.
 */
data class SigningResult(
    val signedPsbt: String,
    val usedAlternativePath: Boolean,
    val alternativePathsUsed: List<String> = emptyList(),
    val usedAddressLookupFallback: Boolean = false,
    val addressLookupInputIndices: List<Int> = emptyList(),
    val refusals: List<InputSigningRefusal> = emptyList(),
)

/**
 * Why MetroVault declined to sign an input it had already matched to this wallet.
 *
 * A refusal is recorded **only** when key resolution succeeded and signing was then declined. If an
 * input simply isn't ours, `PsbtKeyResolver` returns null and `Psbt.sign` is never reached, so no
 * refusal exists. That makes the presence of a refusal the signal that separates "this isn't your
 * transaction" from "this is yours, but MetroVault won't sign it" — very different things to tell
 * the holder of a cold-storage device, and previously indistinguishable.
 */
sealed class InputSigningRefusal {
    abstract val inputIndex: Int

    /** Plain-language explanation, shown to the user verbatim. */
    abstract val message: String

    /** The output commits to a taproot script tree, which a BIP-86 key-path signature cannot satisfy. */
    data class TaprootScriptTree(
        override val inputIndex: Int,
        val merkleRootProven: Boolean,
    ) : InputSigningRefusal() {
        override val message: String
            get() = if (merkleRootProven) {
                "Input #$inputIndex spends a Taproot output that commits to a script tree. " +
                    "This wallet's BIP-86 addresses never carry a script tree, so that output was " +
                    "built by other software and MetroVault refused to sign it."
            } else {
                "Input #$inputIndex spends a Taproot output whose key is not this wallet's BIP-86 key " +
                    "— it likely commits to a script tree. MetroVault refused rather than produce a " +
                    "signature that would not be valid."
            }
    }

    /** The PSBT declares our key for taproot script paths only, not the key path. */
    data class TaprootScriptPathKey(override val inputIndex: Int) : InputSigningRefusal() {
        override val message: String
            get() = "Input #$inputIndex declares this wallet's key for a Taproot script path rather " +
                "than the key path. MetroVault does not support script-path signing."
    }

    /** BIP-341 does not allow this sighash type for taproot. */
    data class UnsupportedSighash(
        override val inputIndex: Int,
        val sighashType: Int,
    ) : InputSigningRefusal() {
        override val message: String
            // Rendered unsigned: PSBT_IN_SIGHASH_TYPE is parsed as a signed Int, so the offending
            // value is often negative and "0x-1" would tell the user nothing.
            get() = "Input #$inputIndex requests sighash type 0x${sighashType.toUInt().toString(16)}, " +
                "which is not valid for this input type. Signing it would produce a signature no " +
                "wallet could verify."
    }

    data class Other(
        override val inputIndex: Int,
        val reason: String,
    ) : InputSigningRefusal() {
        override val message: String get() = "Input #$inputIndex could not be signed: $reason"
    }

    companion object {
        /** Map a library-level [UpdateFailure] onto a user-facing refusal. */
        fun from(inputIndex: Int, failure: UpdateFailure): InputSigningRefusal = when (failure) {
            is UpdateFailure.CannotSignTaprootScriptTree ->
                TaprootScriptTree(inputIndex, merkleRootProven = failure.merkleRoot != null)
            is UpdateFailure.CannotSignTaprootScriptPathKey -> TaprootScriptPathKey(inputIndex)
            is UpdateFailure.UnsupportedSighashType -> UnsupportedSighash(inputIndex, failure.sighashType)
            is UpdateFailure.CannotSignInput -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidWitnessUtxo -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidNonWitnessUtxo -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidInput -> Other(inputIndex, failure.reason)
            else -> Other(inputIndex, failure.toString())
        }
    }
}
