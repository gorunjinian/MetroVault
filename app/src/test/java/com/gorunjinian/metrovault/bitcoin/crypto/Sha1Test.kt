package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Digest
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Sha1Test {
    private val vectors = listOf(
        "" to "da39a3ee5e6b4b0d3255bfef95601890afd80709",
        "abc" to "a9993e364706816aba3e25717850c26c9cd0d89d"
    )

    @Test
    fun referenceVectors() {
        vectors.forEach { (input, expected) ->
            val actual = Digest.sha1().hash(input.encodeToByteArray())
            assertArrayEquals(Hex.decode(expected), actual)
        }
    }
}
