package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput
import com.gorunjinian.metrovault.lib.bitcoin.io.Input
import com.gorunjinian.metrovault.lib.bitcoin.io.Output
import fr.acinq.secp256k1.Hex
import kotlin.jvm.JvmStatic

abstract class BtcSerializer<T> {
    /**
     * write a message to a stream
     *
     * @param message   message
     * @param out output stream
     */
    abstract fun write(message: T, out: Output, protocolVersion: Long)

    fun write(message: T, out: Output): Unit = write(message, out, Protocol.PROTOCOL_VERSION)

    /**
     * write a message to a byte array
     *
     * @param message message
     * @return a serialized message
     */
    fun write(message: T, protocolVersion: Long): ByteArray {
        val out = ByteArrayOutput()
        write(message, out, protocolVersion)
        return out.toByteArray()
    }

    open fun write(message: T): ByteArray = write(message, Protocol.PROTOCOL_VERSION)

    /**
     * read a message from a stream
     *
     * @param input input stream
     * @return a deserialized message
     */
    abstract fun read(input: Input, protocolVersion: Long): T

    fun read(input: Input): T = read(input, Protocol.PROTOCOL_VERSION)

    /**
     * read a message from a byte array
     *
     * @param input serialized message
     * @return a deserialized message
     */
    fun read(input: ByteArray, protocolVersion: Long): T = read(ByteArrayInput(input), protocolVersion)

    open fun read(input: ByteArray): T = read(input, Protocol.PROTOCOL_VERSION)

    /**
     * read a message from a hex string
     *
     * @param input message binary data in hex format
     * @return a deserialized message of type T
     */
    fun read(input: String, protocolVersion: Long): T = read(Hex.decode(input), protocolVersion)

    open fun read(input: String): T = read(input, Protocol.PROTOCOL_VERSION)

    open fun validate(message: T) {}

    companion object {
        @JvmStatic
        fun uint8(input: Input): UByte {
            require(input.availableBytes >= 1)
            return input.read().toUByte()
        }

        @JvmStatic
        fun writeUInt8(input: UByte, out: Output): Unit = out.write(input.toInt() and 0xff)

        @JvmStatic
        fun uint16(input: Input): UShort = Pack.int16LE(input).toUShort()

        @JvmStatic
        fun writeUInt16(input: UShort, out: Output): Unit = Pack.writeInt16LE(input.toShort(), out)

        @JvmStatic
        fun uint32(input: Input): UInt = Pack.int32LE(input).toUInt()

        @JvmStatic
        fun writeUInt32(input: UInt, out: Output): Unit = Pack.writeInt32LE(input.toInt(), out)

        @JvmStatic
        fun writeUInt32(input: UInt): ByteArray = Pack.writeInt32LE(input.toInt())

        @JvmStatic
        fun uint64(input: Input): ULong = Pack.int64LE(input).toULong()

        @JvmStatic
        fun writeUInt64(input: ULong, out: Output): Unit = Pack.writeInt64LE(input.toLong(), out)

        @JvmStatic
        fun varint(input: Input): ULong {
            val first = input.read()
            return when {
                first < 0xFD -> first.toULong()
                first == 0xFD -> uint16(input).toULong()
                first == 0xFE -> uint32(input).toULong()
                first == 0xFF -> uint64(input)
                else -> {
                    throw IllegalArgumentException("invalid first byte $first for varint type")
                }
            }
        }

        @JvmStatic
        fun writeVarint(input: Int, out: Output): Unit = writeVarint(input.toULong(), out)

        @JvmStatic
        fun writeVarint(input: ULong, out: Output) {
            when {
                input < 253uL -> writeUInt8(input.toUByte(), out)
                input <= 65535uL -> {
                    writeUInt8(0xFDu, out)
                    writeUInt16(input.toUShort(), out)
                }
                input <= 4294967295uL -> {
                    writeUInt8(0xFEu, out)
                    writeUInt32(input.toUInt(), out)
                }
                else -> {
                    writeUInt8(0xFFu, out)
                    writeUInt64(input, out)
                }
            }
        }

        @JvmStatic
        fun bytes(input: Input, size: Long): ByteArray = bytes(input, size.toInt())

        @JvmStatic
        fun bytes(input: Input, size: Int): ByteArray {
            // NB: we make that check before allocating a byte array, otherwise an attacker can exhaust our heap space.
            require(size <= input.availableBytes) { "cannot read $size bytes from a stream that has ${input.availableBytes} bytes left" }
            val blob = ByteArray(size)
            if (size > 0) {
                input.read(blob, 0, size)
            }
            return blob
        }

        @JvmStatic
        fun writeBytes(input: ByteArray, out: Output): Unit = out.write(input)

        @JvmStatic
        fun writeBytes(input: ByteVector, out: Output): Unit = writeBytes(input.toByteArray(), out)

        @JvmStatic
        fun writeBytes(input: ByteVector32, out: Output): Unit = writeBytes(input.toByteArray(), out)

        @JvmStatic
        fun hash(input: Input): ByteArray = bytes(input, 32) // a hash is always 256 bits

        @JvmStatic
        fun script(input: Input): ByteArray {
            val length = varint(input) // read size
            return bytes(input, length.toInt()) // read bytes
        }

        @JvmStatic
        fun writeScript(input: ByteArray, out: Output) {
            writeVarint(input.size, out)
            writeBytes(input, out)
        }

        @JvmStatic
        fun writeScript(input: ByteVector, out: Output) {
            writeScript(input.toByteArray(), out)
        }

        fun <T> readCollection(
            input: Input,
            reader: BtcSerializer<T>,
            maxElement: Int?,
            protocolVersion: Long
        ): List<T> = readCollection(input, reader::read, maxElement, protocolVersion)

        fun <T> readCollection(
            input: Input,
            reader: (Input, Long) -> T,
            maxElement: Int?,
            protocolVersion: Long
        ): List<T> {
            val count = varint(input).toInt()
            if (maxElement != null) require(count <= maxElement) { "invalid length" }
            val items = mutableListOf<T>()
            repeat(count) {
                items += reader(input, protocolVersion)
            }
            return items.toList()
        }

        fun <T> readCollection(input: Input, reader: BtcSerializer<T>, protocolVersion: Long): List<T> =
            readCollection(input, reader, null, protocolVersion)

        fun <T> writeCollection(
            seq: List<T>,
            output: Output,
            writer: BtcSerializer<T>,
            protocolVersion: Long
        ): Unit = writeCollection(seq, output, writer::write, protocolVersion)

        fun <T> writeCollection(
            seq: List<T>,
            output: Output,
            writer: (T, Output, Long) -> Unit,
            protocolVersion: Long
        ) {
            writeVarint(seq.size, output)
            seq.forEach { writer.invoke(it, output, protocolVersion) }
        }
    }
}

interface BtcSerializable<T> {
    fun serializer(): BtcSerializer<T>
}
