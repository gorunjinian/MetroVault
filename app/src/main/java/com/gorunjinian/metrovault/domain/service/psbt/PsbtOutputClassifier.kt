package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.PsbtOutputOwnership
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressCheckResult
import com.gorunjinian.vaultovich.OutputOwnership

/**
 * Decides which outputs of a PSBT pay the active wallet, for the transaction review screen.
 *
 * vaultovich's `Psbt.verifyOutput` goes first. It rederives the key an output's derivation entry
 * claims from the account xpub we hold and rebuilds the script, so its `Ours` is proof and costs
 * one derivation. It can only judge outputs that carry such an entry, though, and coordinators
 * leave them off outputs they do not recognize as the wallet's, so an output it does not prove
 * ours falls back to the gap-limit address scan, which reads nothing from the PSBT.
 *
 * A `Mismatch` the scan cannot place either becomes [PsbtOutputOwnership.ClaimMismatch]: an entry
 * claims the wallet, but the script pays someone else.
 */
internal object PsbtOutputClassifier {
    private const val TAG = "PsbtOutputClassifier"

    /**
     * @param outputAddresses the address shown for each output, in output order
     * @param verify `Psbt.verifyOutput` against the active wallet's account for one output index,
     *   or null when there is no account to check against (silent-payment wallets)
     * @param scan the gap-limit search for an address in the active wallet
     */
    fun classify(
        outputAddresses: List<String>,
        verify: ((Int) -> OutputOwnership)?,
        scan: (String) -> AddressCheckResult?,
    ): List<PsbtOutputOwnership> = outputAddresses.mapIndexed { index, address ->
        val verdict = verify?.let { runCatching { it(index) }.getOrNull() }
        if (verdict is OutputOwnership.Ours) {
            return@mapIndexed PsbtOutputOwnership.Ours(verdict.isChange)
        }
        val scanned = scan(address)
        when {
            scanned?.belongs == true -> PsbtOutputOwnership.Ours(scanned.isChange == true)
            verdict is OutputOwnership.Mismatch -> {
                // The reason is diagnostic text from vaultovich, never shown to the user
                AppLog.w(TAG) { "Output $index claims this wallet but does not derive from it: ${verdict.reason}" }
                PsbtOutputOwnership.ClaimMismatch
            }
            else -> PsbtOutputOwnership.External
        }
    }
}
