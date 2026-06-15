package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import com.gorunjinian.metrovault.lib.bitcoin.Int5
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey

/**
 * BIP-352 silent-payment address (`sp1q…` / `tsp1q…`), a bech32m encoding of
 * `ser_P(B_scan) (33) ‖ ser_P(B_spend) (33)` with a witness-version symbol.
 *
 * MetroVault is a signer: it **decodes** the recipient address carried in the PSBT
 * (`PSBT_OUT_SP_V0_INFO`) to show the user, and **encodes** for round-trip tests and the dimmed
 * "you're paying sp1q…" string in the confirmation UI. HRP follows MetroVault's `isTestnet` flag:
 * `sp` for mainnet, `tsp` for everything else.
 */
data class SilentPaymentAddress(
    val scanPubKey: PublicKey,
    val spendPubKey: PublicKey,
    val version: Int = 0
) {
    init {
        require(version in 0..30) { "unsupported silent payment address version $version" }
    }

    fun encode(isTestnet: Boolean): String {
        val hrp = if (isTestnet) HRP_TESTNET else HRP_MAINNET
        val payload = scanPubKey.value.toByteArray() + spendPubKey.value.toByteArray()
        val data = arrayOf<Int5>(version.toByte()) + Bech32.eight2five(payload)
        return Bech32.encode(hrp, data, Bech32.Encoding.Bech32m)
    }

    companion object {
        const val HRP_MAINNET: String = "sp"
        const val HRP_TESTNET: String = "tsp"

        /** Payload length in bytes for a v0 address: scan (33) ‖ spend (33). */
        private const val PAYLOAD_LENGTH = 66

        /**
         * Decode an `sp1q…` / `tsp1q…` address.
         *
         * - `v0` (symbol `q`): payload must be exactly 66 bytes.
         * - `v1`–`v30`: read the first 66 bytes, discard any forward-compatibility tail.
         * - `v31`: rejected.
         */
        fun decode(address: String): SilentPaymentAddress {
            val (hrp, data, encoding) = Bech32.decode(address)
            require(encoding == Bech32.Encoding.Bech32m) { "silent payment address must be bech32m" }
            require(hrp == HRP_MAINNET || hrp == HRP_TESTNET) { "invalid silent payment address hrp '$hrp'" }
            require(data.isNotEmpty()) { "empty silent payment address payload" }

            val version = data[0].toInt()
            require(version != 31) { "unsupported silent payment address version 31" }

            val payload = Bech32.five2eight(data, 1)
            if (version == 0) {
                require(payload.size == PAYLOAD_LENGTH) {
                    "v0 silent payment address payload must be $PAYLOAD_LENGTH bytes, got ${payload.size}"
                }
            } else {
                require(payload.size >= PAYLOAD_LENGTH) {
                    "silent payment address payload too short: ${payload.size} bytes"
                }
            }

            val scan = PublicKey(payload.copyOfRange(0, 33))
            val spend = PublicKey(payload.copyOfRange(33, PAYLOAD_LENGTH))
            return SilentPaymentAddress(scan, spend, version)
        }
    }
}
