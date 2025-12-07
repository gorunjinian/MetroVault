package com.gorunjinian.metrovault.lib.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.io.Input
import com.gorunjinian.metrovault.lib.bitcoin.io.Output
import com.gorunjinian.metrovault.lib.bitcoin.io.readNBytes
import kotlin.jvm.JvmStatic

object Pack {
    @JvmStatic
    fun int16BE(bs: ByteArray, off: Int = 0): Short {
        require(bs.size - off >= Short.SIZE_BYTES)
        var n: Int = bs[off].toInt() and 0xff shl 8
        n = n or (bs[off + 1].toInt() and 0xff)
        return n.toShort()
    }

    @JvmStatic
    fun int16BE(input: Input): Short = int16BE(input.readNBytes(Short.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun int16LE(bs: ByteArray, off: Int = 0): Short {
        require(bs.size - off >= Short.SIZE_BYTES)
        var n: Int = bs[off].toInt() and 0xff
        n = n or (bs[off + 1].toInt() and 0xff).shl(8)
        return n.toShort()
    }

    @JvmStatic
    fun int16LE(input: Input): Short = int16LE(input.readNBytes(Short.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun writeInt16BE(n: Short, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Short.SIZE_BYTES)
        bs[off] = (n.toInt() ushr 8).toByte()
        bs[off + 1] = n.toByte()
    }

    @JvmStatic
    fun writeInt16BE(n: Short): ByteArray = ByteArray(Short.SIZE_BYTES).also { writeInt16BE(n, it) }

    @JvmStatic
    fun writeInt16BE(n: Short, output: Output) {
        output.write(writeInt16BE(n))
    }

    @JvmStatic
    fun writeInt16LE(n: Short, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Short.SIZE_BYTES)
        bs[off] = n.toByte()
        bs[off + 1] = (n.toInt() ushr 8).toByte()
    }

    @JvmStatic
    fun writeInt16LE(n: Short): ByteArray = ByteArray(Short.SIZE_BYTES).also { writeInt16LE(n, it) }

    @JvmStatic
    fun writeInt16LE(n: Short, output: Output) {
        output.write(writeInt16LE(n))
    }

    @JvmStatic
    fun int32BE(bs: ByteArray, off: Int = 0): Int {
        require(bs.size - off >= Int.SIZE_BYTES)
        var n: Int = bs[off].toInt() shl 24
        n = n or (bs[off + 1].toInt() and 0xff).shl(16)
        n = n or (bs[off + 2].toInt() and 0xff).shl(8)
        n = n or (bs[off + 3].toInt() and 0xff)
        return n
    }

    @JvmStatic
    fun int32BE(input: Input): Int = int32BE(input.readNBytes(Int.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun int32LE(bs: ByteArray, off: Int = 0): Int {
        require(bs.size - off >= Int.SIZE_BYTES)
        var n: Int = bs[off].toInt() and 0xff
        n = n or (bs[off + 1].toInt() and 0xff).shl(8)
        n = n or (bs[off + 2].toInt() and 0xff).shl(16)
        n = n or bs[off + 3].toInt().shl(24)
        return n
    }

    @JvmStatic
    fun int32LE(input: Input): Int = int32LE(input.readNBytes(Int.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun writeInt32BE(n: Int, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Int.SIZE_BYTES)
        bs[off] = (n ushr 24).toByte()
        bs[off + 1] = (n ushr 16).toByte()
        bs[off + 2] = (n ushr 8).toByte()
        bs[off + 3] = n.toByte()
    }

    @JvmStatic
    fun writeInt32BE(n: Int): ByteArray = ByteArray(Int.SIZE_BYTES).also { writeInt32BE(n, it) }

    @JvmStatic
    fun writeInt32BE(n: Int, output: Output) {
        output.write(writeInt32BE(n))
    }

    @JvmStatic
    fun writeInt32LE(n: Int, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Int.SIZE_BYTES)
        bs[off] = n.toByte()
        bs[off + 1] = (n ushr 8).toByte()
        bs[off + 2] = (n ushr 16).toByte()
        bs[off + 3] = (n ushr 24).toByte()
    }

    @JvmStatic
    fun writeInt32LE(n: Int): ByteArray = ByteArray(Int.SIZE_BYTES).also { writeInt32LE(n, it) }

    @JvmStatic
    fun writeInt32LE(n: Int, output: Output) {
        output.write(writeInt32LE(n))
    }

    @JvmStatic
    fun int64BE(bs: ByteArray, off: Int = 0): Long {
        require(bs.size - off >= Long.SIZE_BYTES)
        val hi = int32BE(bs, off)
        val lo = int32BE(bs, off + 4)
        return (hi.toLong() and 0xffffffffL) shl 32 or (lo.toLong() and 0xffffffffL)
    }

    @JvmStatic
    fun int64BE(input: Input): Long = int64BE(input.readNBytes(Long.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun int64LE(bs: ByteArray, off: Int = 0): Long {
        require(bs.size - off >= Long.SIZE_BYTES)
        val lo = int32LE(bs, off)
        val hi = int32LE(bs, off + 4)
        return (hi.toLong() and 0xffffffffL) shl 32 or (lo.toLong() and 0xffffffffL)
    }

    @JvmStatic
    fun int64LE(input: Input): Long = int64LE(input.readNBytes(Long.SIZE_BYTES) ?: throw IllegalArgumentException("not enough bytes to read from"))

    @JvmStatic
    fun writeInt64BE(n: Long, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Long.SIZE_BYTES)
        writeInt32BE((n ushr 32).toInt(), bs, off)
        writeInt32BE((n and 0xffffffffL).toInt(), bs, off + 4)
    }

    @JvmStatic
    fun writeInt64BE(n: Long): ByteArray = ByteArray(Long.SIZE_BYTES).also { writeInt64BE(n, it) }

    @JvmStatic
    fun writeInt64BE(n: Long, output: Output) {
        output.write(writeInt64BE(n))
    }

    @JvmStatic
    fun writeInt64LE(n: Long, bs: ByteArray, off: Int = 0) {
        require(bs.size - off >= Long.SIZE_BYTES)
        writeInt32LE((n and 0xffffffffL).toInt(), bs, off)
        writeInt32LE((n ushr 32).toInt(), bs, off + 4)
    }

    @JvmStatic
    fun writeInt64LE(n: Long): ByteArray = ByteArray(Long.SIZE_BYTES).also { writeInt64LE(n, it) }

    @JvmStatic
    fun writeInt64LE(n: Long, output: Output) {
        output.write(writeInt64LE(n))
    }
}
