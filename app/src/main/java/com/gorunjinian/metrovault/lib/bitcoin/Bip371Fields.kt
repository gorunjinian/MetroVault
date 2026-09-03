package com.gorunjinian.metrovault.lib.bitcoin

/**
 * BIP-371 taproot PSBT fields that the [Psbt] reader does not model directly.
 *
 * The reader routes any key whose type byte is not in its known set into `unknown`, and the writer
 * re-emits `unknown` verbatim, so these fields already survive a read/write round trip untouched.
 * Reading them through an extension property — the same approach
 * [Bip374Fields][com.gorunjinian.metrovault.lib.bitcoin.silentpayments.Bip374Fields] takes — keeps
 * that property intact. Promoting one of these into the reader's known set *without* adding a
 * matching writer branch would silently drop it from every signed PSBT, so prefer this.
 */
object Bip371Fields {
    /**
     * `PSBT_IN_TAP_MERKLE_ROOT`: empty key, value = the 32-byte root of the taproot script tree the
     * spent output commits to. Its presence means the output is *not* a plain BIP-86 key-path
     * output, so a no-script-tweak key-path signature cannot satisfy it.
     */
    const val PSBT_IN_TAP_MERKLE_ROOT: Byte = 0x18
}

/**
 * The taproot script-tree merkle root declared for this input (`PSBT_IN_TAP_MERKLE_ROOT`), or `null`
 * when absent or malformed.
 *
 * This is advisory only: it is never trusted to decide *whether* we can sign. The scriptPubKey's
 * output key is the sole authority for that (see [Psbt.sign]); this value only lets us tell the user
 * **why** an input was refused.
 */
val Input.taprootMerkleRoot: ByteVector32?
    get() = unknown.firstOrNull { it.key.size() == 1 && it.key[0] == Bip371Fields.PSBT_IN_TAP_MERKLE_ROOT }
        ?.takeIf { it.value.size() == 32 }?.value?.toByteArray()?.byteVector32()
