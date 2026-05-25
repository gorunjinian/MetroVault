package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.SilentPaymentError
import com.gorunjinian.metrovault.domain.service.psbt.PsbtKeyResolver
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.SigHash
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentInputs
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentInputs.SpInputKind
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.EligibleInputKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.RecipientKeys
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentInfo
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either

/**
 * Story A orchestrator: resolves a PSBT that pays to one or more `sp1q…` recipients.
 *
 * The watching wallet declares each recipient inside the PSBT (`PSBT_OUT_SP_V0_INFO`) and puts a
 * dummy P2TR placeholder in `global.tx.txOut`. This service derives the real taproot output from
 * the spent input private keys (BIP-352), rewrites the placeholder scripts in place, and returns
 * the resolved PSBT — ready for the existing [com.gorunjinian.metrovault.domain.service.psbt.PsbtSigner]
 * to sign. It is invoked from `WalletSigningService` *before* signing, because the output scripts
 * are committed to in every input's sighash.
 *
 * MetroVault is always the sole signer, so all eligible inputs must resolve to keys we control;
 * if one doesn't, we fail rather than produce an output the recipient can't find.
 */
internal object SilentPaymentSender {
    private const val TAG = "SilentPaymentSender"

    /** True if the PSBT declares at least one silent-payment recipient output. */
    fun hasSilentPaymentRecipients(psbt: Psbt): Boolean =
        psbt.outputs.any { it.silentPaymentInfo != null }

    /**
     * Resolve all silent-payment outputs in [psbt], returning the PSBT with their placeholder
     * scripts replaced by the derived P2TR scripts. If the PSBT has no SP recipients, returns it
     * unchanged. The per-output SP fields are left intact (preserved as unknown entries) so a
     * downstream wallet can keep the nominal→derived mapping.
     */
    fun resolve(
        psbt: Psbt,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean,
    ): Either<SilentPaymentError, Psbt> {
        // 1. Find SP recipient outputs (txOut index -> declared recipient keys).
        val spOutputs = psbt.outputs.mapIndexedNotNull { idx, output ->
            output.silentPaymentInfo?.let { idx to it }
        }
        if (spOutputs.isEmpty()) return Either.Right(psbt)

        AppLog.d(TAG) { "Resolving ${spOutputs.size} silent-payment output(s)" }

        // 2. SIGHASH_ANYONECANPAY is unsafe for SP (input set must be fixed before signing).
        if (psbt.inputs.any { input ->
                input.sighashType?.let { (it and SigHash.SIGHASH_ANYONECANPAY) != 0 } == true
            }) {
            return Either.Left(SilentPaymentError.SighashAnyoneCanPayForbidden)
        }

        // 3. Reject inputs spending SegWit v>1 outputs (BIP-352 ineligible).
        for (input in psbt.inputs) {
            val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: continue
            val witnessVersion = Script.getWitnessVersion(scriptPubKey)
            if (witnessVersion != null && witnessVersion > 1) {
                return Either.Left(SilentPaymentError.UnsupportedSegwitVersion(witnessVersion))
            }
        }

        // 4-6. Classify inputs, resolve their private keys, collect the eligible ones.
        val walletFingerprint = BitcoinUtils.computeFingerprintLong(masterPrivateKey.publicKey)
        val addressLookup by lazy { PsbtKeyResolver.buildAddressLookup(accountPrivateKey, scriptType) }
        val eligibleKeys = mutableListOf<EligibleInputKey>()

        psbt.inputs.forEachIndexed { index, input ->
            val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: return@forEachIndexed
            val redeemScript = input.redeemScript?.let { Script.write(it).byteVector() }
            val kind = SilentPaymentInputs.classify(scriptPubKey, redeemScript, input.scriptWitness?.stack)
            if (kind == SpInputKind.INELIGIBLE) return@forEachIndexed

            val resolved = PsbtKeyResolver.resolveFromDerivationPaths(input, masterPrivateKey, walletFingerprint, isTestnet)
                ?: PsbtKeyResolver.resolveFromAddressLookup(input, accountPrivateKey, addressLookup)?.first
            if (resolved == null) {
                val outpoint = psbt.global.tx.txIn[index].outPoint.toString()
                AppLog.w(TAG) { "No signing key for SP-eligible input $index" }
                return Either.Left(SilentPaymentError.MissingPrivateKey(outpoint))
            }
            eligibleKeys.add(EligibleInputKey(resolved.privateKey, isTaproot = kind == SpInputKind.P2TR))
        }

        if (eligibleKeys.isEmpty()) return Either.Left(SilentPaymentError.NoEligibleInputs)

        // 7-10. Derive a taproot output key per recipient (input_hash, shared secrets, t_k, P_k).
        val allOutpoints = psbt.global.tx.txIn.map { it.outPoint }
        val recipients = spOutputs.map { (_, info) -> RecipientKeys(info.scanPubKey, info.spendPubKey) }
        val derived = try {
            SilentPayments.deriveOutputs(eligibleKeys, allOutpoints, recipients)
        } catch (e: SilentPayments.SilentPaymentMathException) {
            return Either.Left(mapMathError(e))
        }

        // 11. Rewrite the placeholder scripts for each SP output index in place.
        val newTxOut = psbt.global.tx.txOut.toMutableList()
        for (output in derived) {
            val txOutIndex = spOutputs[output.recipientIndex].first
            val p2trScript = Script.write(Script.pay2tr(output.outputKey)).byteVector()
            newTxOut[txOutIndex] = newTxOut[txOutIndex].copy(publicKeyScript = p2trScript)
        }

        val resolvedTx = psbt.global.tx.copy(txOut = newTxOut)
        return Either.Right(psbt.copy(global = psbt.global.copy(tx = resolvedTx)))
    }

    private fun mapMathError(e: SilentPayments.SilentPaymentMathException): SilentPaymentError = when (e.reason) {
        SilentPayments.FailureReason.NO_ELIGIBLE_INPUTS,
        SilentPayments.FailureReason.NO_RECIPIENTS -> SilentPaymentError.NoEligibleInputs
        SilentPayments.FailureReason.PRIVATE_KEY_SUM_ZERO -> SilentPaymentError.PrivateKeySumIsZero
        SilentPayments.FailureReason.INVALID_INPUT_HASH -> SilentPaymentError.InvalidInputHash
        SilentPayments.FailureReason.INVALID_TWEAK -> SilentPaymentError.InvalidTweak
        SilentPayments.FailureReason.GROUP_EXCEEDS_KMAX -> SilentPaymentError.GroupExceedsKMax
    }
}
