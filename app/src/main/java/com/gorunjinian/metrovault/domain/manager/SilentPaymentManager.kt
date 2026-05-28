package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.data.model.SilentPaymentKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet

/**
 * BIP-352 silent-payment wallet operations.
 *
 * Resolves the silent-payment address (`sp1q…`) — either from the stored public scan/spend keys in
 * [WalletMetadata] (works while the wallet is locked) or by deriving fresh from the master key —
 * derives the in-memory `(b_scan, b_spend)` keypair from the seed on demand, and answers whether
 * the scan-key export feature applies to a given wallet. The receive-side signing math lives in
 * [com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner]; this
 * manager is the query/orchestration surface used by the UI and the Wallet facade.
 */
class SilentPaymentManager {

    /** True if the wallet is flagged as a dedicated silent-payment wallet. */
    fun isSilentPaymentWallet(metadata: WalletMetadata?): Boolean =
        metadata?.isSilentPayment == true

    /**
     * True if the SP scan-key export can be offered for this wallet — single-sig with the master
     * key currently in memory. Multisig and stateless wallets are excluded (no single seed / no
     * exposed master key respectively).
     */
    fun canExportSilentPayment(walletState: WalletState?, metadata: WalletMetadata?): Boolean {
        if (metadata?.isMultisig == true) return false
        return walletState?.getMasterPrivateKey() != null
    }

    /** Derive the silent-payment keypair from a master key + account + network. */
    fun deriveKeys(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        account: Int,
        isTestnet: Boolean,
    ): SilentPaymentKeys = SilentPaymentWalletService.deriveKeys(masterPrivateKey, account, isTestnet)

    /**
     * The `sp1q…`/`tsp1q…` address for the given account.
     *
     * When [masterPrivateKey] is available, we always derive fresh — the cached pubkeys in
     * [metadata] are pinned to whatever account was last persisted, so deriving live is the only
     * way to track an unlocked wallet across account switches without a write-back race.
     *
     * Falls back to the cached pubkeys only when the master key is unavailable (locked-state
     * display): convenient, but stale if the user switched accounts pre-lock without a refresh.
     */
    fun resolveAddress(
        metadata: WalletMetadata?,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey?,
        account: Int,
        isTestnet: Boolean,
    ): String? {
        if (masterPrivateKey != null) {
            return runCatching {
                SilentPaymentWalletService.silentPaymentAddress(deriveKeys(masterPrivateKey, account, isTestnet), isTestnet)
            }.getOrNull()
        }
        if (metadata?.isSilentPayment == true &&
            metadata.silentPaymentScanPubKey.isNotEmpty() &&
            metadata.silentPaymentSpendPubKey.isNotEmpty()
        ) {
            return runCatching {
                SilentPaymentWalletService.silentPaymentAddress(
                    metadata.silentPaymentScanPubKey,
                    metadata.silentPaymentSpendPubKey,
                    isTestnet
                )
            }.getOrNull()
        }
        return null
    }

    /** The `spscan…`/`tspscan…` scan-key export string for one-time transfer to the watching wallet. */
    fun scanKeyExport(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        account: Int,
        isTestnet: Boolean,
    ): String = SilentPaymentWalletService.scanKeyExport(deriveKeys(masterPrivateKey, account, isTestnet), isTestnet)

    /** The BIP-352 `sp([fp/352h/coin_h/account_h]spscan…)#checksum` descriptor. */
    fun descriptor(
        fingerprintHex: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        account: Int,
        isTestnet: Boolean,
    ): String = SilentPaymentWalletService.silentPaymentDescriptor(
        fingerprintHex,
        deriveKeys(masterPrivateKey, account, isTestnet),
        account,
        isTestnet,
    )
}
