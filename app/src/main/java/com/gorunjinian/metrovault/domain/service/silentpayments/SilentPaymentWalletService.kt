package com.gorunjinian.metrovault.domain.service.silentpayments

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.SilentPaymentKeys
import com.gorunjinian.metrovault.lib.bitcoin.Descriptor
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.ScanAddressCodec
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress

/**
 * Orchestration for an owned single-sig silent-payment wallet.
 *
 * The wallet's identity is the BIP-352 `(b_scan, b_spend)` keypair derived from the seed at
 * `m/352'/coin'/account'/{1,0}'/0`. The keys are derived on demand from the master private key
 * (already held by `WalletState`) — nothing extra is persisted; only the public scan/spend keys are
 * stored in `WalletMetadata` for offline display of the `sp1q…` address.
 */
object SilentPaymentWalletService {
    /** Derive the silent-payment keypair from the master key for the given account / network. */
    fun deriveKeys(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        account: Int = 0,
        isTestnet: Boolean = false,
    ): SilentPaymentKeys {
        val scan = masterPrivateKey.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentScan(account, isTestnet))).privateKey
        val spend = masterPrivateKey.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentSpend(account, isTestnet))).privateKey
        return SilentPaymentKeys(scan, scan.publicKey(), spend, spend.publicKey())
    }

    /** The `sp1q…` / `tsp1q…` address from the stored public scan/spend key hexes (no seed needed). */
    fun silentPaymentAddress(scanPubKeyHex: String, spendPubKeyHex: String, isTestnet: Boolean): String =
        SilentPaymentAddress(PublicKey.fromHex(scanPubKeyHex), PublicKey.fromHex(spendPubKeyHex)).encode(isTestnet)

    /** The `sp1q…` / `tsp1q…` address for a freshly derived keypair. */
    fun silentPaymentAddress(keys: SilentPaymentKeys, isTestnet: Boolean): String =
        SilentPaymentAddress(keys.scanPublicKey, keys.spendPublicKey).encode(isTestnet)

    /**
     * The `spscan…` / `tspscan…` scan-key export string for one-time transfer to the watching wallet.
     * Carries the scan **private** key and the spend **public** key only — never the spend private key.
     */
    fun scanKeyExport(keys: SilentPaymentKeys, isTestnet: Boolean): String =
        ScanAddressCodec.encode(keys.scanPrivateKey, keys.spendPublicKey, isTestnet)

    /**
     * The BIP-352 output descriptor:
     *   `sp([fingerprint/352h/coin_h/account_h]spscan…)#checksum`
     *
     * Wraps [scanKeyExport] in an account-level key origin and appends the 8-character descriptor
     * checksum. Watching wallets (e.g. Sparrow) import this as a single string to register a silent
     * payment account.
     */
    fun silentPaymentDescriptor(
        fingerprintHex: String,
        keys: SilentPaymentKeys,
        account: Int,
        isTestnet: Boolean,
    ): String {
        val coin = if (isTestnet) 1 else 0
        val origin = "[${fingerprintHex.lowercase()}/352h/${coin}h/${account}h]"
        val spscan = ScanAddressCodec.encode(keys.scanPrivateKey, keys.spendPublicKey, isTestnet)
        val body = "sp($origin$spscan)"
        return "$body#${Descriptor.checksum(body)}"
    }
}
