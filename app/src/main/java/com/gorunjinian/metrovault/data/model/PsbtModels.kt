package com.gorunjinian.metrovault.data.model

import com.gorunjinian.vaultovich.UpdateFailure

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
 *
 * Each refusal carries an index-free [detail]; [message] prefixes it with the one input, and
 * [describe] folds a whole list into one paragraph per distinct reason naming every input it
 * applies to, so a four-input transaction refused for one reason reads as one paragraph, not four.
 */
sealed class InputSigningRefusal {
    abstract val inputIndex: Int

    /** Plain-language explanation of the reason alone, with no reference to which input. */
    abstract val detail: String

    /** Plain-language explanation for this one input, shown to the user verbatim. */
    val message: String get() = "Input #$inputIndex: $detail"

    /** The output commits to a taproot script tree, which a BIP-86 key-path signature cannot satisfy. */
    data class TaprootScriptTree(
        override val inputIndex: Int,
        val merkleRootProven: Boolean,
    ) : InputSigningRefusal() {
        override val detail: String
            get() = if (merkleRootProven) {
                "This Taproot output commits to a script tree. This wallet's BIP-86 addresses never " +
                    "carry one, so the output was built by other software and MetroVault refused to " +
                    "sign it."
            } else {
                "The key of this Taproot output is not this wallet's BIP-86 key — it likely commits " +
                    "to a script tree. MetroVault refused rather than produce a signature that would " +
                    "not be valid."
            }
    }

    /** The PSBT declares our key for taproot script paths only, not the key path. */
    data class TaprootScriptPathKey(override val inputIndex: Int) : InputSigningRefusal() {
        override val detail: String
            get() = "The PSBT declares this wallet's key for a Taproot script path rather than the " +
                "key path. MetroVault does not support script-path signing."
    }

    /** BIP-341 does not allow this sighash type for taproot. */
    data class UnsupportedSighash(
        override val inputIndex: Int,
        val sighashType: Int,
    ) : InputSigningRefusal() {
        override val detail: String
            // Rendered unsigned: PSBT_IN_SIGHASH_TYPE is parsed as a signed Int, so the offending
            // value is often negative and "0x-1" would tell the user nothing.
            get() = "Sighash type 0x${sighashType.toUInt().toString(16)} is not valid for this input " +
                "type. Signing with it would produce a signature no wallet could verify."
    }

    /**
     * A segwit v0 input carries only PSBT_IN_WITNESS_UTXO. Without the full previous transaction
     * the amounts of the other inputs, and so the fee shown on screen, cannot be verified (the
     * BIP-143 fee attack, CVE-2020-14199), so vaultovich's default signing policy refuses it.
     */
    data class MissingPreviousTransaction(override val inputIndex: Int) : InputSigningRefusal() {
        override val detail: String
            get() = "Previous transaction missing, so the fee cannot be verified. Re-export the PSBT " +
                "with non-witness UTXOs included."
    }

    /** The sighash type is well-defined but outside what MetroVault is willing to produce. */
    data class SighashNotAllowed(
        override val inputIndex: Int,
        val sighashType: Int,
    ) : InputSigningRefusal() {
        override val detail: String
            get() = "Sighash type 0x${sighashType.toUInt().toString(16)} was requested. MetroVault " +
                "only signs with SIGHASH_ALL (SIGHASH_DEFAULT for Taproot), because any other type " +
                "lets the transaction be changed after it is signed."
    }

    /** SIGHASH_SINGLE with no output at the input's index: the legacy digest degenerates to the constant 1. */
    data class SighashSingleWithoutOutput(override val inputIndex: Int) : InputSigningRefusal() {
        override val detail: String
            get() = "SIGHASH_SINGLE is used but there is no output at this input's index. A signature " +
                "could be replayed, so MetroVault refused."
    }

    /** The script does not reference the key MetroVault resolved: a key-selection bug, not a user error. */
    data class KeyMismatch(override val inputIndex: Int) : InputSigningRefusal() {
        override val detail: String
            get() = "The script does not reference the key MetroVault selected for it, so no " +
                "signature was produced."
    }

    /** The transaction pays a silent-payment address but breaks a BIP-375 signing rule. */
    data class SilentPaymentRule(
        override val inputIndex: Int,
        val reason: String,
    ) : InputSigningRefusal() {
        override val detail: String get() = "Cannot be signed for a silent payment: $reason"
    }

    data class Other(
        override val inputIndex: Int,
        val reason: String,
    ) : InputSigningRefusal() {
        override val detail: String get() = "Could not be signed: $reason"
    }

    companion object {
        /**
         * One paragraph per distinct reason, each naming every input it applies to, in first-seen
         * order: "Inputs #0 to #3: Previous transaction missing, …".
         */
        fun describe(refusals: List<InputSigningRefusal>): String =
            refusals.groupBy { it.detail }.entries.joinToString("\n\n") { (detail, group) ->
                "${inputsLabel(group.map { it.inputIndex })}: $detail"
            }

        /** "Input #2", "Inputs #0 and #3", or "Inputs #0 to #3" for a run of three or more. */
        private fun inputsLabel(indices: List<Int>): String {
            val contiguous = indices.size >= 3 && indices.zipWithNext().all { (a, b) -> b == a + 1 }
            return when {
                indices.size == 1 -> "Input #${indices[0]}"
                contiguous -> "Inputs #${indices.first()} to #${indices.last()}"
                else -> "Inputs " + indices.dropLast(1).joinToString(", ") { "#$it" } + " and #${indices.last()}"
            }
        }

        /** Map a library-level [UpdateFailure] onto a user-facing refusal. */
        fun from(inputIndex: Int, failure: UpdateFailure): InputSigningRefusal = when (failure) {
            is UpdateFailure.CannotSignTaprootScriptTree ->
                TaprootScriptTree(inputIndex, merkleRootProven = failure.merkleRoot != null)
            is UpdateFailure.CannotSignTaprootScriptPathKey -> TaprootScriptPathKey(inputIndex)
            is UpdateFailure.UnsupportedSighashType -> UnsupportedSighash(inputIndex, failure.sighashType)
            is UpdateFailure.MissingNonWitnessUtxo -> MissingPreviousTransaction(inputIndex)
            is UpdateFailure.SighashTypeNotAllowed -> SighashNotAllowed(inputIndex, failure.sighashType)
            is UpdateFailure.SighashSingleWithoutMatchingOutput -> SighashSingleWithoutOutput(inputIndex)
            is UpdateFailure.KeyDoesNotMatchInput -> KeyMismatch(inputIndex)
            is UpdateFailure.SilentPaymentRuleViolation -> SilentPaymentRule(inputIndex, failure.reason)
            is UpdateFailure.CannotSignInput -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidWitnessUtxo -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidNonWitnessUtxo -> Other(inputIndex, failure.reason)
            is UpdateFailure.InvalidInput -> Other(inputIndex, failure.reason)
            else -> Other(inputIndex, failure.toString())
        }
    }
}
