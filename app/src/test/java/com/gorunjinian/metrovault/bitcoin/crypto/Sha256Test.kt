package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Digest
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Sha256Test {
    private val vectors = listOf(
        "" to "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
        "abc" to "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
        "a".repeat(1_000_000) to "cdc76e5c9914fb9281a1c7e284d73e67f1809a48a497200e046d39ccc7112cd0"
    )

    @Test
    fun referenceVectors() {
        vectors.forEach { (input, expected) ->
            val actual = Digest.sha256().hash(input.encodeToByteArray())
            assertArrayEquals(Hex.decode(expected), actual)
        }
    }
}
