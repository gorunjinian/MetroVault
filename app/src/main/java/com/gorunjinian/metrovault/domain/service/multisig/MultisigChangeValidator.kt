package com.gorunjinian.metrovault.domain.service.multisig

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.vaultovich.OutputOwnership
import com.gorunjinian.vaultovich.Psbt
import com.gorunjinian.vaultovich.verifyOutput

/**
 * Validates that a multisig PSBT contains no deceptive "change" outputs before signing.
 *
 * The attack: a malicious coordinator sends a PSBT whose output carries BIP-32 derivation hints
 * referencing one of *our* cosigner fingerprints — claiming "this output returns to you, it's your
 * change/receive" — while the actual scriptPubKey is an address the attacker controls. A signer
 * that trusts the hint would hide the output from the amount-sent total and the user approves a
 * silent theft.
 *
 * Defense: vaultovich's `Psbt.verifyOutput` checks every output against the registered wallet. Each
 * claimed path must sit under that cosigner's account path, every cosigner must be derived at the
 * same `branch/index`, and the full `k-of-n` script rebuilt from the xpubs we hold must equal the
 * output's real scriptPubKey. Any [OutputOwnership.Mismatch] is treated as tampering and signing is
 * refused. The PSBT-declared index is used directly (no gap-limit scan).
 *
 * Outputs with no our-fingerprint hints are intentionally left alone: they correctly appear as
 * external recipients (money visibly leaving) and are not the concern here.
 */
class MultisigChangeValidator(
    private val multisigAddressService: MultisigAddressService
) {
    sealed interface Result {
        /** No deceptive output found. */
        object Valid : Result
        /** Output at [outputIndex] claims to be ours but does not derive from the registered descriptor. */
        data class Mismatch(val outputIndex: Int) : Result
    }

    /**
     * @param psbtBase64 the base64-encoded PSBT to validate
     * @param config the registered multisig descriptor
     */
    fun validate(psbtBase64: String, config: MultisigConfig): Result {
        // If we can't parse, don't block here — the signing step uses the same parser and will fail
        // for real reasons. No signature is produced from an unparseable PSBT.
        val psbt = PsbtUtils.parsePsbt(psbtBase64) ?: return Result.Valid
        return validate(psbt, config)
    }

    fun validate(psbt: Psbt, config: MultisigConfig): Result {
        val account = multisigAddressService.toMultisigAccount(config)
        val ourFingerprints = config.cosigners.mapNotNull { it.fingerprint.toLongOrNull(16) }.toSet()

        for (i in psbt.outputs.indices) {
            if (i >= psbt.global.tx.txOut.size) break

            if (account == null) {
                // The registered descriptor can't be read, so nothing can be verified. An output
                // that claims to be ours fails closed; the rest are external payments.
                val claimsOurs = psbt.outputs[i].derivationPaths.values.any { it.masterKeyFingerprint in ourFingerprints }
                if (claimsOurs) return Result.Mismatch(i)
                continue
            }

            val ownership = psbt.verifyOutput(i, account)
            if (ownership is OutputOwnership.Mismatch) {
                AppLog.e(TAG) { "Change validation failed at output $i: ${ownership.reason}" }
                return Result.Mismatch(i)
            }
        }
        return Result.Valid
    }

    companion object {
        private const val TAG = "MultisigChangeValidator"
    }
}
