package com.gorunjinian.metrovault.lib.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.byteVector32
import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack

/**
 * BIP-374 PSBT field plumbing for silent payments.
 *
 * Field codes and wire formats are pinned to drongo's `PSBT.java` / `PSBTInput.java` /
 * `PSBTOutput.java` so we are wire-compatible with Sparrow.
 *
 * MetroVault only ever **reads and preserves** these fields — the watching wallet (Sparrow or
 * equivalent) authors them; we consume them, derive/sign, and round-trip them untouched. Because
 * the [Psbt][com.gorunjinian.metrovault.lib.bitcoin.Psbt] reader routes any key whose type byte is
 * not in its known set into `unknown`, and the writer re-emits `unknown` verbatim in read order,
 * these fields already round-trip byte-identically without touching the parser. The accessors below
 * give callers typed, validated access to them without authoring any new serialization path.
 */
object Bip374Fields {
    // ---- Per-output fields (consumed by Story A: sending to an SP recipient) ----

    /** `PSBT_OUT_SP_V0_INFO`: 66 bytes — `ser_P(B_scan) (33)` ‖ `ser_P(B_spend) (33)`. */
    const val PSBT_OUT_SP_V0_INFO: Byte = 0x09

    /** `PSBT_OUT_SP_V0_LABEL`: 4 bytes — little-endian uint32 label index `m`. */
    const val PSBT_OUT_SP_V0_LABEL: Byte = 0x0a

    // ---- Per-input fields (consumed by Story B: spending a received SP output) ----

    /** `PSBT_IN_SP_TWEAK`: 32 bytes — scalar `t_k` (or `t_k + label_tweak` mod n for labelled receives). */
    const val PSBT_IN_SP_TWEAK: Byte = 0x20

    /**
     * `PSBT_IN_SP_SPEND_BIP32_DERIVATION`: keyed by spend pubkey, value = BIP-32 derivation.
     * Deferred (receive-spending). Preserved as an unknown entry; no typed accessor yet.
     */
    const val PSBT_IN_SP_SPEND_BIP32_DERIVATION: Byte = 0x1f

    private const val SP_INFO_LENGTH = 66

    /**
     * Parse a `PSBT_OUT_SP_V0_INFO` value into a v0 [SilentPaymentAddress] (scan + spend pubkeys).
     * @throws IllegalArgumentException if the value is not 66 bytes or the pubkeys are invalid.
     */
    fun parseSilentPaymentInfo(value: ByteVector): SilentPaymentAddress {
        require(value.size() == SP_INFO_LENGTH) {
            "PSBT_OUT_SP_V0_INFO must be $SP_INFO_LENGTH bytes, got ${value.size()}"
        }
        val bytes = value.toByteArray()
        val scan = PublicKey(bytes.copyOfRange(0, 33))
        val spend = PublicKey(bytes.copyOfRange(33, SP_INFO_LENGTH))
        require(scan.isValid() && spend.isValid()) { "PSBT_OUT_SP_V0_INFO contains an invalid public key" }
        return SilentPaymentAddress(scan, spend, version = 0)
    }

    /** Parse a `PSBT_OUT_SP_V0_LABEL` value: a little-endian uint32, returned as an unsigned [Long]. */
    fun parseSilentPaymentLabel(value: ByteVector): Long {
        require(value.size() == 4) { "PSBT_OUT_SP_V0_LABEL must be 4 bytes, got ${value.size()}" }
        return Pack.int32LE(value.toByteArray()).toUInt().toLong()
    }

    /** Parse a `PSBT_IN_SP_TWEAK` value: a 32-byte scalar. */
    fun parseSilentPaymentTweak(value: ByteVector): ByteVector32 {
        require(value.size() == 32) { "PSBT_IN_SP_TWEAK must be 32 bytes, got ${value.size()}" }
        return value.toByteArray().byteVector32()
    }
}

/**
 * The silent-payment recipient declared on this output (`PSBT_OUT_SP_V0_INFO`), or `null` if the
 * output carries no SP recipient field. Parsed and validated on access.
 */
val Output.silentPaymentInfo: SilentPaymentAddress?
    get() = unknown.firstOrNull { it.key.size() == 1 && it.key[0] == Bip374Fields.PSBT_OUT_SP_V0_INFO }
        ?.let { Bip374Fields.parseSilentPaymentInfo(it.value) }

/**
 * The labelled-address index (`PSBT_OUT_SP_V0_LABEL`, little-endian uint32) for this output, or
 * `null` if absent. We never compute a label tweak from this — the recipient's `B_m` already
 * arrives pre-computed inside [silentPaymentInfo]; we only preserve and surface the index.
 */
val Output.silentPaymentLabel: Long?
    get() = unknown.firstOrNull { it.key.size() == 1 && it.key[0] == Bip374Fields.PSBT_OUT_SP_V0_LABEL }
        ?.let { Bip374Fields.parseSilentPaymentLabel(it.value) }

/**
 * The per-input silent-payment tweak (`PSBT_IN_SP_TWEAK`, the scalar `t_k` or `t_k + label_tweak`),
 * supplied by the watching wallet when spending a received SP output, or `null` if absent.
 */
val Input.silentPaymentTweak: ByteVector32?
    get() = unknown.firstOrNull { it.key.size() == 1 && it.key[0] == Bip374Fields.PSBT_IN_SP_TWEAK }
        ?.let { Bip374Fields.parseSilentPaymentTweak(it.value) }
