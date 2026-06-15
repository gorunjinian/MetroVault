package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import com.gorunjinian.metrovault.lib.bitcoin.Int5
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey

/**
 * Bech32m codec for the BIP-352 scan-key export string (`spscan…` / `tspscan…`).
 *
 * Story B: the user exports **only** the scan key to their watching wallet so it can detect
 * incoming silent payments; the spend key never leaves the device. The payload is 65 bytes:
 * `ser_256(b_scan) (32)` ‖ `ser_P(B_spend) (33)` — the scan **private** key plus the spend
 * **public** key. Mirrors drongo `SilentPaymentScanAddress.toKeyString()`.
 *
 * The `spspend…` / `tspspend…` variant (which carries the spend *private* key) is deliberately
 * **rejected** by [decode]: importing a spend key would break the cold-signer model.
 */
object ScanAddressCodec {
    const val HRP_SCAN_MAINNET: String = "spscan"
    const val HRP_SCAN_TESTNET: String = "tspscan"
    const val HRP_SPEND_MAINNET: String = "spspend"
    const val HRP_SPEND_TESTNET: String = "tspspend"

    /** 65 bytes: scan private key (32) ‖ spend public key (33). */
    private const val PAYLOAD_LENGTH = 65

    fun encode(scanPrivateKey: PrivateKey, spendPublicKey: PublicKey, isTestnet: Boolean): String {
        val hrp = if (isTestnet) HRP_SCAN_TESTNET else HRP_SCAN_MAINNET
        val payload = scanPrivateKey.value.toByteArray() + spendPublicKey.value.toByteArray()
        val data = arrayOf<Int5>(0) + Bech32.eight2five(payload)
        return Bech32.encode(hrp, data, Bech32.Encoding.Bech32m)
    }

    /**
     * Decode a scan-key string into `(b_scan, B_spend)`. Used for round-trip tests; MetroVault never
     * imports a scan key from outside the device.
     *
     * @throws IllegalArgumentException for the `spspend…`/`tspspend…` HRP (a spend-key export, which
     *   we refuse), a non-`spscan`/`tspscan` HRP, a non-bech32m encoding, an unsupported version, or
     *   a malformed payload length.
     */
    fun decode(encoded: String): Pair<PrivateKey, PublicKey> {
        val (hrp, data, encoding) = Bech32.decode(encoded)
        require(encoding == Bech32.Encoding.Bech32m) { "scan key must be bech32m" }
        require(hrp != HRP_SPEND_MAINNET && hrp != HRP_SPEND_TESTNET) {
            "refusing to import a spend key ($hrp); only the scan key (spscan/tspscan) may be transferred"
        }
        require(hrp == HRP_SCAN_MAINNET || hrp == HRP_SCAN_TESTNET) { "invalid scan key hrp '$hrp'" }
        require(data.isNotEmpty()) { "empty scan key payload" }
        require(data[0].toInt() == 0) { "unsupported scan key version ${data[0].toInt()}" }

        val payload = Bech32.five2eight(data, 1)
        require(payload.size == PAYLOAD_LENGTH) { "scan key payload must be $PAYLOAD_LENGTH bytes, got ${payload.size}" }
        val scan = PrivateKey(payload.copyOfRange(0, 32))
        val spend = PublicKey(payload.copyOfRange(32, PAYLOAD_LENGTH))
        return scan to spend
    }

    /** True if the HRP belongs to a scan-key export. */
    fun isTestnet(hrp: String): Boolean = hrp == HRP_SCAN_TESTNET || hrp == HRP_SPEND_TESTNET
}
