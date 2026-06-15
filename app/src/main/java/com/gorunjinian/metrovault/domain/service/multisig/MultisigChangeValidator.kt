package com.gorunjinian.metrovault.domain.service.multisig

import android.util.Base64
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either

/**
 * Validates that a multisig PSBT contains no deceptive "change" outputs before signing.
 *
 * The attack: a malicious coordinator sends a PSBT whose output carries BIP-32 derivation hints
 * referencing one of *our* cosigner fingerprints — claiming "this output returns to you, it's your
 * change/receive" — while the actual scriptPubKey is an address the attacker controls. A signer
 * that trusts the hint would hide the output from the amount-sent total and the user approves a
 * silent theft.
 *
 * Defense: for every output that *claims* to be ours, derive the expected scriptPubKey from the
 * registered descriptor at the **declared** `(change, index)` and require an exact byte match
 * against the output's real scriptPubKey. Any mismatch is treated as tampering — signing is refused.
 * This uses the PSBT-declared index directly (no gap-limit scan).
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
     * @param isTestnet whether the wallet is on testnet
     */
    fun validate(psbtBase64: String, config: MultisigConfig, isTestnet: Boolean): Result {
        // If we can't parse, don't block here — the signing step uses the same parser and will fail
        // for real reasons. No signature is produced from an unparseable PSBT.
        val psbt = parse(psbtBase64) ?: return Result.Valid
        val ourFingerprints = config.cosigners.map { it.fingerprint.lowercase() }.toSet()
        val txOuts = psbt.global.tx.txOut

        psbt.outputs.forEachIndexed { i, output ->
            if (i >= txOuts.size) return@forEachIndexed

            // Collect (fingerprint, full derivation path) claims from both ecdsa and taproot maps.
            val claims = buildList {
                output.derivationPaths.values.forEach { add(it.masterKeyFingerprint to it.keyPath.path) }
                output.taprootDerivationPaths.values.forEach { add(it.masterKeyFingerprint to it.keyPath.path) }
            }

            // Does any claim reference one of our cosigner fingerprints? If not, this output does not
            // claim to be ours and is none of our business (it'll show as an external recipient).
            val ourClaim = claims.firstOrNull { (fp, _) -> "%08x".format(fp) in ourFingerprints }
                ?: return@forEachIndexed

            val path = ourClaim.second
            // Claims ours but the path can't yield a (change, index) → fail closed.
            if (path.size < 2) return Result.Mismatch(i)

            val change = path[path.size - 2]
            val index = path[path.size - 1]
            // Standard receive (0) / change (1); index must be a non-hardened value that fits an Int.
            if (change != 0L && change != 1L) return Result.Mismatch(i)
            if (index < 0L || index > Int.MAX_VALUE.toLong()) return Result.Mismatch(i)

            val expectedSpk = multisigAddressService.generateMultisigScriptPubKey(
                config, index.toInt(), isChange = change == 1L, isTestnet
            ) ?: return Result.Mismatch(i) // claims ours but we can't derive it → fail closed

            val actualSpk = txOuts[i].publicKeyScript.toByteArray()
            if (!expectedSpk.contentEquals(actualSpk)) {
                AppLog.e(TAG) {
                    "Change validation failed at output $i (claims change/$change index $index but does not match descriptor)"
                }
                return Result.Mismatch(i)
            }
        }
        return Result.Valid
    }

    private fun parse(psbtBase64: String): Psbt? {
        return try {
            val bytes = Base64.decode(psbtBase64, Base64.NO_WRAP)
            when (val r = Psbt.read(bytes)) {
                is Either.Right -> r.value
                is Either.Left -> {
                    // Retry with stripped global xpubs (same fallback the signer uses).
                    val stripped = PsbtUtils.stripGlobalXpubs(bytes) ?: return null
                    when (val retry = Psbt.read(stripped)) {
                        is Either.Right -> retry.value
                        is Either.Left -> null
                    }
                }
            }
        } catch (e: Exception) {
            AppLog.e(TAG) { "Failed to parse PSBT for change validation: ${e.message}" }
            null
        }
    }

    companion object {
        private const val TAG = "MultisigChangeValidator"
    }
}
