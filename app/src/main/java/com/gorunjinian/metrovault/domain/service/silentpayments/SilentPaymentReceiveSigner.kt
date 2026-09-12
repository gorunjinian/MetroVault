package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.SpSpendingError
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.vaultovich.ByteVector
import com.gorunjinian.vaultovich.Crypto
import com.gorunjinian.vaultovich.Input
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.PrivateKey
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.SigHash
import com.gorunjinian.vaultovich.TxOut
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentSpending
import com.gorunjinian.vaultovich.silentpayments.silentPaymentTweak
import com.gorunjinian.vaultovich.updated
import com.gorunjinian.vaultovich.utils.Either

/**
 * Story B receive-side signer: signs PSBT inputs that spend a silent-payment output we received.
 *
 * Each such input carries `PSBT_IN_SP_TWEAK` (`t_k`, or `t_k + label` mod n). The signer computes
 * `d = (b_spend + tweak) mod n`, **verifies `d·G` equals the input's taproot output key** (so a
 * wrong tweak fails before any signature is produced), then signs the taproot key path with `d`.
 *
 * Crucially, the silent-payment output key `P_k = d·G` is used *directly* as the taproot output
 * key — there is **no** BIP-341/BIP-86 taptweak — so we compute the key-path sighash and call
 * [Crypto.signSchnorr] with `taprootTweak = null` rather than going through the BIP-86 signing path.
 */
object SilentPaymentReceiveSigner {
    private const val TAG = "SilentPaymentReceiveSigner"

    /** True if any input carries a silent-payment tweak (i.e. spends a received SP output). */
    fun hasSilentPaymentTweaks(psbt: Psbt): Boolean = psbt.inputs.any { it.silentPaymentTweak != null }

    /**
     * Signs every input that carries `PSBT_IN_SP_TWEAK` using the wallet's spend private key.
     * Returns the PSBT with those inputs signed, or a typed error (the first one encountered).
     */
    fun signTweakedInputs(psbt: Psbt, spendPrivateKey: PrivateKey): Either<SpSpendingError, Psbt> {
        if (!hasSilentPaymentTweaks(psbt)) return Either.Left(SpSpendingError.NoTweakInputs)

        // Spent outputs for all inputs (taproot sighash commits to every input's amount + script).
        val maybeSpent = psbt.inputs.mapIndexed { idx, input -> spentOutput(psbt, idx, input) }
        val missingIdx = maybeSpent.indexOfFirst { it == null }
        if (missingIdx >= 0) return Either.Left(SpSpendingError.MissingUtxo(missingIdx))
        val spentOutputs = maybeSpent.filterNotNull()

        var result = psbt
        psbt.inputs.forEachIndexed { index, input ->
            val tweak = input.silentPaymentTweak ?: return@forEachIndexed
            val d = try {
                SilentPaymentSpending.deriveSpendingPrivateKey(spendPrivateKey, tweak)
            } catch (_: IllegalArgumentException) {
                return Either.Left(SpSpendingError.InvalidSpendKey(index))
            }

            val scriptPubKey = PsbtUtils.getInputScriptPubKey(input)
                ?: return Either.Left(SpSpendingError.MissingUtxo(index))
            val outputKey = Script.pay2trOutputKey(scriptPubKey)
                ?: return Either.Left(SpSpendingError.InputNotTaproot(index))
            // The output key is P_k = d·G directly (no taptweak); reject a tweak that doesn't derive it.
            if (outputKey != d.xOnlyPublicKey()) return Either.Left(SpSpendingError.TweakMismatch(index))
            if (input !is Input.WitnessInput.PartiallySignedWitnessInput) {
                return Either.Left(SpSpendingError.InputNotTaproot(index))
            }

            val sighashType = input.sighashType ?: SigHash.SIGHASH_DEFAULT
            // Same guard as the BIP-86 key-path signer: `hashForSigningTaprootKeyPath` accepts any
            // negative value (a declared 0xFFFFFFFF parses to -1) and masks it into
            // SIGHASH_SINGLE | SIGHASH_ANYONECANPAY, and throws outright for values like 0x41.
            if (!SigHash.isValidTaproot(sighashType)) {
                return Either.Left(SpSpendingError.UnsupportedSighashType(index, sighashType))
            }
            val sighash = result.global.tx.hashForSigningTaprootKeyPath(index, spentOutputs, sighashType)
            // No taproot tweak: sign with d as-is (secp256k1 handles BIP-340 even-Y internally).
            val sig = Crypto.signSchnorr(sighash, d, taprootTweak = null)
            val signature: ByteVector = if (sighashType != SigHash.SIGHASH_DEFAULT) sig.concat(sighashType.toByte()) else sig

            AppLog.d(TAG) { "Signed silent-payment receive input $index" }
            result = result.copy(inputs = result.inputs.updated(index, input.copy(taprootKeySignature = signature)))
        }
        return Either.Right(result)
    }

    private fun spentOutput(psbt: Psbt, index: Int, input: Input): TxOut? =
        input.witnessUtxo ?: input.nonWitnessUtxo?.txOut?.getOrNull(psbt.global.tx.txIn[index].outPoint.index.toInt())
}
