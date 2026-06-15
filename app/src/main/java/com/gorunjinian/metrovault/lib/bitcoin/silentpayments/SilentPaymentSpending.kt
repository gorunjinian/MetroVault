package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey

/**
 * BIP-352 **receive-side** signing math (Story B): spending a silent-payment output we received.
 *
 * The watching wallet found the output and supplies its per-output tweak `t_k` (or
 * `t_k + label_tweak` mod n for labeled receives) via `PSBT_IN_SP_TWEAK`. The cold device holds
 * `b_spend` and computes the one-shot signing key `d = (b_spend + tweak) mod n`, then Schnorr-signs
 * the taproot key path with `d`. `d·G` equals the output's taproot key `P_k`, which the caller must
 * verify against the input's scriptPubKey before signing.
 */
object SilentPaymentSpending {
    /**
     * `d = (b_spend + tweak) mod n`, via secp256k1 scalar addition.
     *
     * @throws IllegalArgumentException if the resulting scalar is zero or ≥ the curve order.
     */
    fun deriveSpendingPrivateKey(spendPrivateKey: PrivateKey, tweak: ByteVector32): PrivateKey {
        val d = spendPrivateKey + PrivateKey(tweak)
        require(d.isValid()) { "derived silent payment spending key is invalid (zero or >= curve order)" }
        return d
    }
}
