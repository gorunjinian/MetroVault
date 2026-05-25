package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.lib.bitcoin.PublicKey

/**
 * A silent-payment recipient declared in a PSBT output (`PSBT_OUT_SP_V0_INFO`).
 *
 * @param scanPubKey the recipient's `B_scan`.
 * @param spendPubKey the recipient's `B_spend` (already includes any label tweak for labelled addresses).
 * @param address the nominal `sp1q…` / `tsp1q…` address the user is paying.
 * @param kIndex the per-scan-key output index used when deriving this output's taproot key.
 */
data class SilentPaymentRecipient(
    val scanPubKey: PublicKey,
    val spendPubKey: PublicKey,
    val address: String,
    val kIndex: Int,
)

/**
 * The resolution of one silent-payment output: the nominal `sp1q…` recipient and the actual
 * on-chain `bc1p…` / `tb1p…` taproot address it derives to. Surfaced to the confirmation UI so the
 * user can consent to the address they're committing to before signing.
 */
data class SilentPaymentResolution(
    val outputIndex: Int,
    val recipient: SilentPaymentRecipient,
    val derivedOutputAddress: String,
)

/**
 * Typed failures when resolving a silent-payment send. Each maps to a clear, user-facing reason
 * the PSBT cannot be safely signed as an SP send.
 */
sealed class SilentPaymentError {
    abstract val message: String

    /** The PSBT declares SP recipients but no input is eligible to derive a shared secret. */
    data object NoEligibleInputs : SilentPaymentError() {
        override val message = "No silent-payment-eligible inputs in this transaction."
    }

    /** An input uses SIGHASH_ANYONECANPAY, which would let the input set change after signing. */
    data object SighashAnyoneCanPayForbidden : SilentPaymentError() {
        override val message = "Silent payments cannot be signed with SIGHASH_ANYONECANPAY."
    }

    /** An input spends a SegWit v>1 output, which is ineligible for silent payments. */
    data class UnsupportedSegwitVersion(val version: Int) : SilentPaymentError() {
        override val message = "An input spends an unsupported SegWit v$version output."
    }

    /** The sum of the eligible input private keys is zero (mod n). */
    data object PrivateKeySumIsZero : SilentPaymentError() {
        override val message = "The summed input private key is invalid for this silent payment."
    }

    /** The derived `input_hash` is an invalid scalar (zero or ≥ curve order). */
    data object InvalidInputHash : SilentPaymentError() {
        override val message = "The silent payment input hash is invalid."
    }

    /** A derived per-output tweak `t_k` is an invalid scalar. */
    data object InvalidTweak : SilentPaymentError() {
        override val message = "A derived silent payment output tweak is invalid."
    }

    /** More than K_MAX recipients share a single scan key. */
    data object GroupExceedsKMax : SilentPaymentError() {
        override val message = "Too many silent payment recipients share one scan key."
    }

    /** No private key could be resolved for an SP-eligible input. */
    data class MissingPrivateKey(val outpoint: String) : SilentPaymentError() {
        override val message = "Could not resolve the signing key for input $outpoint."
    }
}
