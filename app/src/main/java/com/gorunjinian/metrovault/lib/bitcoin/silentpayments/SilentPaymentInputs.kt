package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Script

/**
 * Classifies a spent input as one of the BIP-352 shared-secret-eligible kinds, or `INELIGIBLE`.
 *
 * Pure classifier over the raw spending pieces (the scriptPubKey of the output being spent, plus
 * the redeem script / witness when relevant). We always have the private key from BIP-32
 * derivation, so unlike a receiver we never extract a pubkey out of the scriptSig/witness — we
 * only need to know the *kind* of input (and whether it's taproot, for even-Y negation) and
 * whether it must be skipped entirely (NUMS-`H` script-path taproot spends).
 */
object SilentPaymentInputs {
    /**
     * BIP-341 NUMS point: a P2TR output spent via the script path whose internal key is this
     * value must be skipped from the shared-secret sum (BIP-352). Vendored verbatim from drongo
     * `SilentPaymentUtils.java`.
     */
    val NUMS_H: ByteVector32 = ByteVector32("50929b74c1a04954b78b4b6035e97a5e078a5a0f28ec96d547bfee9ace803ac0")

    enum class SpInputKind { P2TR, P2WPKH, P2SH_P2WPKH, P2PKH, INELIGIBLE }

    /**
     * Classify a spent input.
     *
     * @param scriptPubKey the scriptPubKey of the output being spent (from the witnessUtxo / utxo).
     * @param redeemScript the P2SH redeem script, if the input is P2SH-wrapped.
     * @param witnessStack the witness stack of the spending input, if known. Only used to detect
     *   NUMS-`H` taproot script-path spends; pass `null` when signing an as-yet-unsigned input
     *   (a key-path spend, the common send case).
     */
    fun classify(
        scriptPubKey: ByteVector,
        redeemScript: ByteVector? = null,
        witnessStack: List<ByteVector>? = null
    ): SpInputKind = when {
        Script.isPay2tr(scriptPubKey) ->
            if (witnessStack != null && isNumsScriptPathSpend(witnessStack)) SpInputKind.INELIGIBLE
            else SpInputKind.P2TR

        Script.isPay2wpkh(scriptPubKey.toByteArray()) -> SpInputKind.P2WPKH

        Script.isPay2pkh(scriptPubKey.toByteArray()) -> SpInputKind.P2PKH

        Script.isPay2sh(scriptPubKey.toByteArray()) &&
            redeemScript != null && Script.isPay2wpkh(redeemScript.toByteArray()) -> SpInputKind.P2SH_P2WPKH

        else -> SpInputKind.INELIGIBLE
    }

    /**
     * True if this taproot witness is a script-path spend whose control-block internal key is the
     * NUMS point. The annex (a final witness item starting with `0x50`, when the stack has more
     * than one item) is stripped first, per drongo `SilentPaymentUtils.java:70-72`.
     */
    private fun isNumsScriptPathSpend(witnessStack: List<ByteVector>): Boolean {
        var stack = witnessStack
        if (stack.size > 1 && stack.last().size() > 0 && stack.last()[0] == 0x50.toByte()) {
            stack = stack.dropLast(1)
        }
        if (stack.size <= 1) return false
        val controlBlock = stack.last().toByteArray()
        if (controlBlock.size < 33) return false
        val internalKey = controlBlock.copyOfRange(1, 33)
        return internalKey.contentEquals(NUMS_H.toByteArray())
    }
}
