package com.gorunjinian.metrovault.lib.bitcoin.crypto

import kotlin.experimental.xor

fun Digest.hmac(key: ByteArray, data: ByteArray, blockSize: Int): ByteArray {
    val key1 = if (key.size > blockSize) hash(key) else key
    val key2 = if (key1.size < blockSize) key1 + ByteArray(blockSize - key1.size) else key1

    fun xor(a: ByteArray, b: ByteArray): ByteArray {
        require(a.size == b.size)
        val output = ByteArray(a.size)
        for (i in a.indices) output[i] = a[i] xor b[i]
        return output
    }

    val opad = xor(key2, ByteArray(blockSize) { 0x5c.toByte() })
    val ipad = xor(key2, ByteArray(blockSize) { 0x36.toByte() })
    return hash(opad + hash(ipad + data))
}
