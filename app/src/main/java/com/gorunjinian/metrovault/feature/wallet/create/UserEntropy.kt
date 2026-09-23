package com.gorunjinian.metrovault.feature.wallet.create

/**
 * Encodes the user's coin tosses (0 = Heads, 1 = Tails) or dice rolls (1–6) for mixing
 * with system entropy: one byte per input, in the order entered.
 *
 * Nothing is packed. The bytes are only ever hashed together with SecureRandom output
 * (see `MnemonicService.generateMnemonicWithUserEntropy`), and SHA-256 does the
 * compressing, so every input reaches the hash — a trailing partial byte of coin tosses
 * or an odd final die roll included.
 */
fun encodeUserEntropy(inputs: List<Int>): ByteArray =
    ByteArray(inputs.size) { inputs[it].toByte() }
