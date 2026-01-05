package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.SigningResult
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Service responsible for PSBT (Partially Signed Bitcoin Transaction) operations.
 * 
 * This is the public facade for all PSBT functionality. It delegates to specialized
 * internal modules for each concern:
 * - [PsbtSigner] - Signing operations
 * - [PsbtFinalizer] - Transaction finalization
 * - [PsbtAnalyzer] - Analysis and details extraction
 * - [PsbtSizeCalculator] - Transaction size calculations
 * - [PsbtUtils] - Shared utilities
 */
@Suppress("KDocUnresolvedReference")
class PsbtService {

    /**
     * Signs a PSBT using BIP-174 compliant approach with path-agnostic fallback:
     * 1. First tries to use derivation path metadata from the PSBT (fast, reliable)
     * 2. If fingerprint matches but pubkey doesn't, tries standard alternative paths
     * 3. Falls back to address scanning if no metadata available
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @param masterPrivateKey Master private key for full path derivation (BIP-174)
     * @param accountPrivateKey Account-level private key for fallback address scanning
     * @param scriptType Script type for address generation in fallback
     * @param isTestnet Whether this is a testnet wallet
     * @return SigningResult with signed PSBT and info about alternative paths used, or null on failure
     */
    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): SigningResult? = PsbtSigner.signPsbt(
        psbtBase64, masterPrivateKey, accountPrivateKey, scriptType, isTestnet
    )

    /**
     * Checks if a PSBT is fully signed.
     */
    @Suppress("unused") // Public API for future use/testing
    fun isPsbtFullySigned(psbtBase64: String): Boolean =
        PsbtAnalyzer.isPsbtFullySigned(psbtBase64)

    /**
     * Checks if a PSBT can be finalized (has all required signatures).
     * A PSBT is finalizable if all inputs meet one of these criteria:
     * - Single-sig: exactly 1 partial signature (or taproot key sig)
     * - Multi-sig: at least m partial signatures for m-of-n multisig
     * - Already finalized
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @return true if the PSBT has all required signatures and can be finalized
     */
    fun canFinalize(psbtBase64: String): Boolean =
        PsbtAnalyzer.canFinalize(psbtBase64)

    /**
     * @deprecated Use canFinalize() instead which supports both single-sig and multi-sig
     */
    @Deprecated("Use canFinalize() instead", ReplaceWith("canFinalize(psbtBase64)"))
    fun canFinalizeSingleSig(psbtBase64: String): Boolean = canFinalize(psbtBase64)

    /**
     * Result of PSBT finalization.
     */
    sealed class FinalizePsbtResult {
        data class Success(val txHex: String) : FinalizePsbtResult()
        data class Failure(val message: String) : FinalizePsbtResult()
    }

    /**
     * Finalizes a PSBT and extracts the raw transaction.
     * This assumes canFinalize() has returned true.
     *
     * For each input:
     * - P2WPKH: Creates witness with [signature, pubkey]
     * - P2TR: Creates witness with [schnorr_signature]
     * - P2PKH: Creates scriptSig with [signature] [pubkey]
     * - P2SH-P2WPKH: Creates witness + scriptSig with redeem script
     * - P2WSH (multisig): Creates witness with [empty, sig1, sig2, ..., sigM, witnessScript]
     * - P2SH-P2WSH (multisig): Creates witness with [empty, sig1, sig2, ..., sigM, witnessScript]
     * - P2SH (bare multisig): Creates scriptSig with [OP_0, sig1, sig2, ..., sigM, redeemScript]
     *
     * @param psbtBase64 Base64 encoded signed PSBT
     * @return FinalizePsbtResult with raw transaction hex on success
     */
    fun finalizePsbt(psbtBase64: String): FinalizePsbtResult {
        return when (val result = PsbtFinalizer.finalizePsbt(psbtBase64)) {
            is PsbtFinalizer.FinalizePsbtResult.Success -> FinalizePsbtResult.Success(result.txHex)
            is PsbtFinalizer.FinalizePsbtResult.Failure -> FinalizePsbtResult.Failure(result.message)
        }
    }

    /**
     * Extracts details from a PSBT for display purposes.
     */
    fun getPsbtDetails(psbtBase64: String, isTestnet: Boolean = false): PsbtDetails? =
        PsbtAnalyzer.getPsbtDetails(psbtBase64, isTestnet)
}
