package com.gorunjinian.metrovault.core.util

/**
 * Converts a hex string to a ByteArray.
 * @throws IllegalArgumentException if the string length is odd or contains invalid hex chars
 * Example: "cafe" -> byteArrayOf(0xCA.toByte(), 0xFE.toByte())
 */
fun String.hexToByteArray(): ByteArray {
    require(length % 2 == 0) { "Hex string must have even length, got $length" }
    return chunked(2).map { it.toInt(16).toByte() }.toByteArray()
}
