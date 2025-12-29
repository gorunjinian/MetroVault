package com.gorunjinian.metrovault.feature.transaction.components

import android.annotation.SuppressLint
import com.gorunjinian.metrovault.data.model.PsbtOutput

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
 */
data class OutputWithType(
    val output: PsbtOutput,
    val isOurAddress: Boolean,
    val isChangeAddress: Boolean?
)
