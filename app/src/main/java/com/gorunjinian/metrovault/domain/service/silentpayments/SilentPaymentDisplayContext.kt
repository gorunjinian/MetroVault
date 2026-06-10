package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey

/**
 * Wallet keys needed to resolve silent-payment recipient outputs for display: the actual
 * `bc1p…` taproot output is derived from the spent input keys, so showing it on the confirmation
 * screen requires the active wallet's keys. When absent (multisig, or keys unavailable), SP
 * outputs are shown with their nominal `sp1q…` address only.
 *
 * @param spendPrivateKey the BIP-352 spend key (`b_spend`) when the active wallet is a
 * silent-payment wallet, so inputs spending received SP outputs (`PSBT_IN_SP_TWEAK`) resolve too.
 */
data class SilentPaymentDisplayContext(
    val masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
    val scriptType: ScriptType,
    val spendPrivateKey: PrivateKey? = null,
)
