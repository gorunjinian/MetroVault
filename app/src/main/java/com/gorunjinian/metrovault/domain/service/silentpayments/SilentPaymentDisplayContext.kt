package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * Wallet keys needed to resolve silent-payment recipient outputs for display: the actual
 * `bc1p…` taproot output is derived from the spent input keys, so showing it on the confirmation
 * screen requires the active wallet's keys. When absent (multisig, or keys unavailable), SP
 * outputs are shown with their nominal `sp1q…` address only.
 */
data class SilentPaymentDisplayContext(
    val masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val scriptType: ScriptType,
)
