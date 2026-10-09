package com.gorunjinian.metrovault.feature.transaction.components

import android.annotation.SuppressLint
import com.gorunjinian.metrovault.data.model.PsbtOutput
import com.gorunjinian.metrovault.data.model.PsbtOutputOwnership

/**
 * Format satoshis to the specified unit with comma separators for thousands
 * @param satoshis The amount in satoshis
 * @param showInSats If true, display as sats with comma separators; if false, display as BTC
 * @return Formatted string with appropriate unit suffix
 */
@SuppressLint("DefaultLocale")
fun formatAmount(satoshis: Long, showInSats: Boolean): String {
    return if (showInSats) {
        // Format as sats with comma separators
        "%,d sats".format(satoshis)
    } else {
        // Format as BTC with proper decimal places
        val btc = satoshis / 100_000_000.0
        val formatted = String.format("%.8f", btc).trimEnd('0').trimEnd('.')
        "$formatted BTC"
    }
}

/**
 * Helper data class to track output type during display
 *
 * @property claimMismatch the PSBT claims this output for the wallet but its script does not
 *   derive from the wallet's keys; it is counted as sent and flagged
 */
data class OutputWithType(
    val output: PsbtOutput,
    val isOurAddress: Boolean,
    val isChangeAddress: Boolean?,
    val claimMismatch: Boolean = false
) {
    companion object {
        fun of(output: PsbtOutput, ownership: PsbtOutputOwnership): OutputWithType = when (ownership) {
            is PsbtOutputOwnership.Ours -> OutputWithType(output, isOurAddress = true, isChangeAddress = ownership.isChange)
            PsbtOutputOwnership.External -> OutputWithType(output, isOurAddress = false, isChangeAddress = false)
            PsbtOutputOwnership.ClaimMismatch ->
                OutputWithType(output, isOurAddress = false, isChangeAddress = false, claimMismatch = true)
        }
    }
}
