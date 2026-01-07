package com.gorunjinian.metrovault.lib.bitcoin.io

interface Input {
    val availableBytes: Int
    fun read(): Int
    fun read(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): Int
}

/** Read bytes from the input. Return null if the input is too small. */
fun Input.readNBytes(n: Int): ByteArray? = if (availableBytes < n) null else ByteArray(n).also { read(it, 0, n) }
