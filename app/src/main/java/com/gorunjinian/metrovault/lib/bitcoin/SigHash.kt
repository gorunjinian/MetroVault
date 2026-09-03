package com.gorunjinian.metrovault.lib.bitcoin

import kotlin.jvm.JvmStatic

object SigHash {
    const val SIGHASH_ALL: Int = 1
    const val SIGHASH_NONE: Int = 2
    const val SIGHASH_SINGLE: Int = 3
    const val SIGHASH_ANYONECANPAY: Int = 0x80
    const val SIGHASH_DEFAULT: Int = 0 //!< Taproot only; implied when sighash byte is missing, and equivalent to SIGHASH_ALL
    const val SIGHASH_OUTPUT_MASK: Int = 3
    const val SIGHASH_INPUT_MASK: Int = 0x80

    @JvmStatic
    fun isAnyoneCanPay(sighashType: Int): Boolean = (sighashType and SIGHASH_ANYONECANPAY) != 0

    @JvmStatic
    fun isHashSingle(sighashType: Int): Boolean = (sighashType and 0x1f) == SIGHASH_SINGLE

    @JvmStatic
    fun isHashNone(sighashType: Int): Boolean = (sighashType and 0x1f) == SIGHASH_NONE

    /**
     * BIP-341: the only hash_type values a taproot signature may commit to.
     *
     * This is deliberately an allow-list rather than a range check. `Transaction.hashForSigningSchnorr`
     * guards with `sighashType <= 0x03 || sighashType in 0x81..0x83`, which a *negative* value
     * satisfies — and the sighash type is parsed from `PSBT_IN_SIGHASH_TYPE` as a signed Int, so a
     * PSBT declaring 0xFFFFFFFF arrives as -1, passes that guard, and is then masked into
     * SIGHASH_SINGLE | SIGHASH_ANYONECANPAY. Signing a digest whose semantics were never computed or
     * shown to the user is exactly what this device exists to prevent.
     *
     * NB: only valid for taproot. Legacy and segwit-v0 have a different valid set (0x00 is *not*
     * valid there), so do not reuse this for those branches.
     */
    @JvmStatic
    fun isValidTaproot(sighashType: Int): Boolean =
        sighashType in SIGHASH_DEFAULT..SIGHASH_SINGLE || sighashType in 0x81..0x83

    /**
     * The sighash types a legacy or segwit-v0 (ECDSA) signature may commit to — Bitcoin Core's
     * `IsDefinedHashtypeSignature`: strip `SIGHASH_ANYONECANPAY`, and what remains must be in
     * `SIGHASH_ALL..SIGHASH_SINGLE`.
     *
     * Note `SIGHASH_DEFAULT` (0x00) is **not** valid here — it is taproot-only. That is why this is
     * a separate predicate from [isValidTaproot] rather than a shared range check.
     *
     * Two distinct things go wrong without this guard, because the digest commits to the full
     * 32-bit value (`writeUInt32`) while only the low byte is appended to the signature:
     *  - an undefined byte (0x00, 0x41, …) yields a signature no verifier will accept;
     *  - a value above one byte (0x101) signs over 0x00000101 while the verifier recomputes with
     *    0x00000001, so the signature is silently unverifiable.
     */
    @JvmStatic
    fun isValidEcdsa(sighashType: Int): Boolean =
        sighashType in SIGHASH_ALL..SIGHASH_SINGLE ||
            sighashType in (SIGHASH_ANYONECANPAY or SIGHASH_ALL)..(SIGHASH_ANYONECANPAY or SIGHASH_SINGLE)
}

object SigVersion {
    const val SIGVERSION_BASE: Int = 0
    const val SIGVERSION_WITNESS_V0: Int = 1
    const val SIGVERSION_TAPROOT: Int = 2
    const val SIGVERSION_TAPSCRIPT: Int = 3
}
