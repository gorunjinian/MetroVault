package com.gorunjinian.metrovault.domain.service.util

import com.gorunjinian.metrovault.lib.bitcoin.*

/**
 * Utility functions for working with Bitcoin Script elements.
 *
 * Centralizes OP code mappings used by both MultisigAddressService and PsbtService
 * to avoid code duplication.
 */
object ScriptUtils {

    /**
     * Converts an integer (0-16) to the corresponding OP_N script element.
     * Used when building multisig scripts: OP_m <pubkeys> OP_n OP_CHECKMULTISIG
     *
     * @param n Integer value (0-16)
     * @return The corresponding OP_N script element
     * @throws IllegalArgumentException if n is outside 0-16 range
     */
    fun intToOpNum(n: Int): ScriptElt {
        return when (n) {
            0 -> OP_0
            1 -> OP_1
            2 -> OP_2
            3 -> OP_3
            4 -> OP_4
            5 -> OP_5
            6 -> OP_6
            7 -> OP_7
            8 -> OP_8
            9 -> OP_9
            10 -> OP_10
            11 -> OP_11
            12 -> OP_12
            13 -> OP_13
            14 -> OP_14
            15 -> OP_15
            16 -> OP_16
            else -> throw IllegalArgumentException("intToOpNum only supports 0-16, got $n")
        }
    }

    /**
     * Converts an OP_N script element to its integer value.
     * Used when parsing multisig scripts to extract m and n values.
     *
     * @param op The script element (OP_0 through OP_16)
     * @return The integer value (0-16), or null if not a valid OP_N
     */
    fun opNumToInt(op: ScriptElt?): Int? {
        return when (op) {
            OP_0 -> 0
            OP_1 -> 1
            OP_2 -> 2
            OP_3 -> 3
            OP_4 -> 4
            OP_5 -> 5
            OP_6 -> 6
            OP_7 -> 7
            OP_8 -> 8
            OP_9 -> 9
            OP_10 -> 10
            OP_11 -> 11
            OP_12 -> 12
            OP_13 -> 13
            OP_14 -> 14
            OP_15 -> 15
            OP_16 -> 16
            else -> null
        }
    }
}
