package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Digest
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Test

// Vectors from https://homes.esat.kuleuven.be/~bosselae/ripemd160.html
class Ripemd160Test {

    private val vectors = listOf(
        "" to "9c1185a5c5e9fc54612808977ee8f548b2258d31",
        "abc" to "8eb208f7e05d987a9b044a8e98c6b087f15a0bfc",
        "message digest" to "5d0689ef49d2fae572b881b123a85ffa21595f36",
        "abcdefghijklmnopqrstuvwxyz" to "f71c27109c692c1b56bbdceb5b9d2865b3708dbc",
        "abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq" to "12a053384a9c0c88e405a06c27dcf49ada62eb2b",
        "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmno" to "e0b62c9952c259e438bbbf82f643203f94e57550",
        "ABCDEFGHIJKLMNOPQRSTUVWXYZabcdefghijklmnopqrstuvwxyz0123456789" to "b0e20b6e3116640286ed3a87a5713079b21f5189"
    )

    @Test
    fun referenceVectors() {
        vectors.forEach { (input, expected) ->
            val actual = Digest.ripemd160().hash(input.encodeToByteArray())
            assertArrayEquals(Hex.decode(expected), actual)
        }
    }

    @Test
    fun repeatedDigits() {
        val input = "1234567890".repeat(8).encodeToByteArray()
        assertArrayEquals(
            Hex.decode("9b752e45573d4b39f4dbd3323cab82bf63326bfb"),
            Digest.ripemd160().hash(input)
        )
    }

    @Test
    fun millionAs() {
        assertArrayEquals(
            Hex.decode("52783243c1697bdbe16d37f97f68f08325dc1528"),
            Digest.ripemd160().hash(ByteArray(1_000_000) { 0x61 })
        )
    }
}
