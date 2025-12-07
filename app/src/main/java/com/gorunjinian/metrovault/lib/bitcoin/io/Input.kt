package com.gorunjinian.metrovault.lib.bitcoin.io

interface Input {
    public val availableBytes: Int
    public fun read(): Int
    public fun read(b: ByteArray, offset: Int = 0, length: Int = b.size - offset): Int
}

/** Read bytes from the input. Return null if the input is too small. */
public fun Input.readNBytes(n: Int): ByteArray? = if (availableBytes < n) null else ByteArray(n).also { read(it, 0, n) }
