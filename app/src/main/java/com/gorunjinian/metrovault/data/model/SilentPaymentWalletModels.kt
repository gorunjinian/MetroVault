package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey

/**
 * A BIP-352 silent-payment keypair derived from the seed. Short-lived and in-memory only;
 * the private keys are derived on demand at sign/export time and must not be persisted. The spend
 * private key never leaves the device.
 */
data class SilentPaymentKeys(
    val scanPrivateKey: PrivateKey,
    val scanPublicKey: PublicKey,
    val spendPrivateKey: PrivateKey,
    val spendPublicKey: PublicKey,
)

/** Typed failures when signing a spend of a received silent-payment output (receive side). */
sealed class SpSpendingError {
    abstract val message: String

    /** The PSBT carries `PSBT_IN_SP_TWEAK` but the active wallet is not a silent-payment wallet. */
    data object NotSilentPaymentWallet : SpSpendingError() {
        override val message = "This PSBT spends a silent-payment output but the active wallet is not a silent-payment wallet."
    }

    /** No input carried a silent-payment tweak, so there was nothing to sign on the SP path. */
    data object NoTweakInputs : SpSpendingError() {
        override val message = "No silent-payment inputs to sign."
    }

    /** The input is missing its UTXO, so the spent scriptPubKey/amount is unavailable. */
    data class MissingUtxo(val index: Int) : SpSpendingError() {
        override val message = "Input $index is missing its UTXO."
    }

    /** The input being spent via a silent-payment tweak is not a P2TR output. */
    data class InputNotTaproot(val index: Int) : SpSpendingError() {
        override val message = "Input $index carries a silent-payment tweak but is not a taproot output."
    }

    /** `d·G` does not match the input's taproot output key — the watching wallet supplied a wrong tweak. */
    data class TweakMismatch(val index: Int) : SpSpendingError() {
        override val message = "The silent-payment tweak for input $index does not derive its output key."
    }

    /** The derived spending key `d = b_spend + tweak` is an invalid scalar (zero or ≥ n). */
    data class InvalidSpendKey(val index: Int) : SpSpendingError() {
        override val message = "The derived spending key for input $index is invalid."
    }
}
