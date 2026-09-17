package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.vaultovich.ScriptType
import com.gorunjinian.metrovault.data.model.SilentPaymentError
import com.gorunjinian.metrovault.domain.service.psbt.PsbtKeyResolver
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.vaultovich.DataEntry
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.PrivateKey
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.Script
import com.gorunjinian.vaultovich.SigHash
import com.gorunjinian.vaultovich.byteVector
import com.gorunjinian.vaultovich.silentpayments.Bip374Fields
import com.gorunjinian.vaultovich.silentpayments.DleqProof
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentInputs
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentSpending
import com.gorunjinian.vaultovich.silentpayments.silentPaymentTweak
import com.gorunjinian.vaultovich.silentpayments.SilentPaymentInputs.SpInputKind
import com.gorunjinian.vaultovich.silentpayments.SilentPayments
import com.gorunjinian.vaultovich.silentpayments.SilentPayments.EligibleInputKey
import com.gorunjinian.vaultovich.silentpayments.SilentPayments.RecipientKeys
import com.gorunjinian.vaultovich.silentpayments.silentPaymentInfo
import com.gorunjinian.vaultovich.utils.Either
import java.security.SecureRandom

/**
 * Story A orchestrator: resolves a PSBT that pays to one or more `sp1q…` recipients.
 *
 * The watching wallet declares each recipient inside the PSBT (`PSBT_OUT_SP_V0_INFO`) and puts a
 * dummy P2TR placeholder in `global.tx.txOut`. This service derives the real taproot output from
 * the spent input private keys (BIP-352), rewrites the placeholder scripts in place, attaches the
 * BIP-375 global ECDH share + DLEQ proof per scan key (so the watching wallet can verify the
 * derived scripts before broadcasting), and returns the resolved PSBT — ready for the existing
 * [com.gorunjinian.metrovault.domain.service.psbt.PsbtSigner]
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
     *
     * @param spendPrivateKey the wallet's BIP-352 spend key (`b_spend`), required to resolve inputs
     * that spend a *received* SP output (carrying `PSBT_IN_SP_TWEAK`): their one-shot key
     * `d = b_spend + tweak` exists on no BIP-32 branch, so it must be derived from the tweak. Null
     * for non-SP wallets — a tweaked input then fails with [SilentPaymentError.MissingPrivateKey].
     */
    fun resolve(
        psbt: Psbt,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean,
        spendPrivateKey: PrivateKey? = null,
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
            val witnessStack = input.scriptWitness?.stack
            val kind = SilentPaymentInputs.classify(scriptPubKey, redeemScript, witnessStack)
            if (kind == SpInputKind.INELIGIBLE) return@forEachIndexed
            val outPoint = psbt.global.tx.txIn[index].outPoint
            val outpoint = outPoint.toString()

            // The key BIP-352 sums for this input. For a P2TR input that is the private key of the
            // taproot *output* key, never the BIP-86 internal key: a sum over the internal key
            // derives outputs the receiver can never find, i.e. an unrecoverable payment.
            val tweak = input.silentPaymentTweak
            val inputKey = if (tweak != null) {
                // An input spending a *received* SP output: its key d = b_spend + tweak exists on no
                // BIP-32 branch, so derive it from PSBT_IN_SP_TWEAK and verify it against the input's
                // taproot output key (P_k = d·G directly, no taptweak) — same check the receive signer
                // makes before signing.
                if (spendPrivateKey == null) {
                    AppLog.w(TAG) { "Input $index spends a received SP output but no spend key is available" }
                    return Either.Left(SilentPaymentError.MissingPrivateKey(outpoint))
                }
                val d = try {
                    SilentPaymentSpending.deriveSpendingPrivateKey(spendPrivateKey, tweak)
                } catch (_: IllegalArgumentException) {
                    return Either.Left(SilentPaymentError.TweakMismatch(outpoint))
                }
                val outputKey = Script.pay2trOutputKey(scriptPubKey)
                if (outputKey == null || outputKey != d.xOnlyPublicKey()) {
                    AppLog.w(TAG) { "SP tweak for input $index does not derive its output key" }
                    return Either.Left(SilentPaymentError.TweakMismatch(outpoint))
                }
                d
            } else {
                val resolved = PsbtKeyResolver.resolveFromDerivationPaths(input, masterPrivateKey, walletFingerprint, isTestnet)
                    ?: PsbtKeyResolver.resolveFromAddressLookup(input, accountPrivateKey, addressLookup)?.first
                if (resolved == null) {
                    AppLog.w(TAG) { "No signing key for SP-eligible input $index" }
                    return Either.Left(SilentPaymentError.MissingPrivateKey(outpoint))
                }
                // The resolver matches taproot inputs on their internal key; BIP-352 wants d = k + t.
                if (kind == SpInputKind.P2TR) SilentPayments.taprootOutputPrivateKey(resolved.privateKey) else resolved.privateKey
            }

            // Prove the key against the script it spends before it enters the sum. The library also
            // drops the inputs BIP-352 receivers skip (key hashes over the uncompressed encoding).
            val spent = SilentPayments.SpentInput(outPoint, inputKey, scriptPubKey, redeemScript, witnessStack)
            val eligible = try {
                SilentPayments.eligibleKey(spent)
            } catch (e: SilentPayments.SilentPaymentMathException) {
                AppLog.w(TAG) { "Input $index: ${e.message}" }
                return Either.Left(mapMathError(e, outpoint))
            }
            if (eligible != null) eligibleKeys.add(eligible)
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

        // 12. BIP-375 proofs: attach a global ECDH share (`a·B_scan`) and DLEQ proof per scan key
        // so the watching wallet can verify the derived scripts — Sparrow refuses to extract or
        // broadcast the final transaction without them. Global (not per-input) fields suffice
        // because MetroVault is the sole signer. v2-only: BIP-375 forbids these fields in v0.
        // Re-resolution replaces any previously attached share/proof rather than duplicating it.
        var newUnknown = psbt.global.unknown
        if (psbt.global.version >= 2) {
            val summedKey = SilentPayments.summedPrivateKey(eligibleKeys)
            val random = SecureRandom()
            val proofEntries = mutableListOf<DataEntry>()
            for (scanKey in recipients.map { it.scanPubKey }.distinct()) {
                val ecdhShare = scanKey * summedKey
                val auxRand = ByteArray(32).also(random::nextBytes)
                val proof = DleqProof.generate(summedKey, scanKey, auxRand)
                if (proof == null) {
                    AppLog.w(TAG) { "DLEQ proof generation failed for a scan key" }
                    return Either.Left(SilentPaymentError.ProofGenerationFailed)
                }
                proofEntries.add(Bip374Fields.globalEcdhShareEntry(scanKey, ecdhShare))
                proofEntries.add(Bip374Fields.globalDleqProofEntry(scanKey, proof))
            }
            newUnknown = psbt.global.unknown.filterNot(Bip374Fields::isGlobalSilentPaymentProofEntry) + proofEntries
        }

        val resolvedTx = psbt.global.tx.copy(txOut = newTxOut)
        return Either.Right(psbt.copy(global = psbt.global.copy(tx = resolvedTx, unknown = newUnknown)))
    }

    private fun mapMathError(
        e: SilentPayments.SilentPaymentMathException,
        outpoint: String? = null,
    ): SilentPaymentError = when (e.reason) {
        SilentPayments.FailureReason.NO_ELIGIBLE_INPUTS,
        SilentPayments.FailureReason.NO_RECIPIENTS -> SilentPaymentError.NoEligibleInputs
        SilentPayments.FailureReason.PRIVATE_KEY_SUM_ZERO -> SilentPaymentError.PrivateKeySumIsZero
        SilentPayments.FailureReason.INVALID_INPUT_HASH -> SilentPaymentError.InvalidInputHash
        SilentPayments.FailureReason.INVALID_TWEAK -> SilentPaymentError.InvalidTweak
        SilentPayments.FailureReason.GROUP_EXCEEDS_KMAX -> SilentPaymentError.GroupExceedsKMax
        SilentPayments.FailureReason.KEY_DOES_NOT_MATCH_INPUT -> SilentPaymentError.KeyDoesNotMatchInput(outpoint)
    }
}
