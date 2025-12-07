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
}

object SigVersion {
    const val SIGVERSION_BASE: Int = 0
    const val SIGVERSION_WITNESS_V0: Int = 1
    const val SIGVERSION_TAPROOT: Int = 2
    const val SIGVERSION_TAPSCRIPT: Int = 3
}
