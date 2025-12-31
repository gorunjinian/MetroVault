package com.gorunjinian.metrovault.lib.bitcoin

import fr.acinq.secp256k1.Hex
import kotlin.experimental.or
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

open class ByteVector(internal val bytes: ByteArray, internal val offset: Int, private val size: Int) {
    constructor(bytes: ByteArray) : this(bytes, 0, bytes.size)
    constructor(input: String) : this(Hex.decode(input))

    init {
        require(offset >= 0) { "offset ($offset) must be > 0" }
        require(size >= 0) { "size ($size) must be > 0" }
        require(offset + size <= bytes.size) { "offset ($offset) + size ($size) must be <= buffer size (${bytes.size})" }
    }

    fun size(): Int = size

    fun isEmpty(): Boolean = size == 0

    operator fun get(i: Int): Byte = bytes[offset + i]

    fun take(n: Int): ByteVector = ByteVector(bytes, offset, n)

    fun drop(n: Int): ByteVector = ByteVector(bytes, offset + n, size - n)

    fun slice(from: Int, to: Int): ByteVector = drop(from).take(to - from)

    open fun update(i: Int, b: Byte): ByteVector {
        val newbytes = toByteArray()
        newbytes[i] = b
        return ByteVector(newbytes)
    }

    operator fun plus(other: ByteVector): ByteVector = concat(other)

    operator fun plus(other: ByteArray): ByteVector = concat(other)

    fun or(other: ByteVector): ByteVector {
        require(size == other.size) { "cannot call or() on byte vectors of different sizes" }
        val data = toByteArray()
        for (i in data.indices) {
            data[i] = data[i] or other[i]
        }
        return ByteVector(data)
    }

    fun padLeft(length: Int): ByteVector {
        require(size <= length) { "byte vector larger than padding target" }
        if (length == size) return this
        return ByteVector(ByteArray(length - size) + toByteArray())
    }

    fun padRight(length: Int): ByteVector {
        require(size <= length) { "byte vector larger than padding target" }
        if (length == size) return this
        return ByteVector(toByteArray() + ByteArray(length - size))
    }

    fun concat(value: Byte): ByteVector = ByteVector(toByteArray() + value)

    fun concat(other: ByteArray): ByteVector = ByteVector(toByteArray() + other)

    fun concat(other: ByteVector): ByteVector = concat(other.toByteArray())

    fun concat(others: List<ByteVector>): ByteVector = others.fold(this) { current, next -> current.concat(next) }

    open fun reversed(): ByteVector = ByteVector(toByteArray().reversedArray())

    fun contentEquals(input: ByteArray, inputOffset: Int, inputSize: Int): Boolean {
        if (size != inputSize) return false
        for (i in 0 until size) {
            if (bytes[offset + i] != input[inputOffset + i]) return false
        }
        return true
    }

    fun contentEquals(input: ByteArray): Boolean = contentEquals(input, 0, input.size)

    fun <T> map(f: (ByteArray, Int, Int) -> T): T = f(bytes, offset, size)

    fun sha256(): ByteVector32 = map { bytes, offset, size -> ByteVector32(Crypto.sha256(bytes, offset, size)) }

    fun ripemd160(): ByteVector = map { bytes, offset, size -> ByteVector(Crypto.ripemd160(bytes, offset, size)) }

    fun toByteArray(): ByteArray = bytes.copyOfRange(offset, offset + size)

    fun toHex(): String = Hex.encode(bytes, offset, size)

    override fun toString(): String = toHex()

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is ByteVector) return false
        return contentEquals(other.bytes, other.offset, other.size)
    }

    override fun hashCode(): Int {
        var result = 1
        for (index in offset until (offset + size)) {
            result = 31 * result + bytes[index]
        }
        return result
    }

    companion object {
        @JvmField
        val empty: ByteVector = ByteVector(ByteArray(0))

        @JvmStatic
        fun fromHex(hex: String): ByteVector = ByteVector(Hex.decode(hex))
    }
}

class ByteVector32(bytes: ByteArray, offset: Int) : ByteVector(bytes, offset, 32) {
    constructor(bytes: ByteArray) : this(bytes, 0)
    constructor(input: String) : this(Hex.decode(input), 0)
    constructor(input: ByteVector) : this(input.bytes, input.offset)

    override fun update(i: Int, b: Byte): ByteVector32 = ByteVector32(super.update(i, b))

    override fun reversed(): ByteVector32 = ByteVector32(super.toByteArray().reversedArray())

    companion object {
        @JvmField
        val Zeroes: ByteVector32 = ByteVector32("0000000000000000000000000000000000000000000000000000000000000000")

        @JvmField
        val One: ByteVector32 = ByteVector32("0100000000000000000000000000000000000000000000000000000000000000")

    }
}

class ByteVector64(bytes: ByteArray, offset: Int) : ByteVector(bytes, offset, 64) {
    constructor(bytes: ByteArray) : this(bytes, 0)
    constructor(input: String) : this(Hex.decode(input), 0)
    constructor(input: ByteVector) : this(input.bytes, input.offset)

    override fun update(i: Int, b: Byte): ByteVector64 = ByteVector64(super.update(i, b))

    override fun reversed(): ByteVector64 = ByteVector64(super.toByteArray().reversedArray())
}

fun ByteArray.byteVector(): ByteVector = ByteVector(this)

fun ByteArray.byteVector32(): ByteVector32 = ByteVector32(this)
fun ByteArray.byteVector64(): ByteVector64 = ByteVector64(this)
