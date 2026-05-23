package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Digest
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertArrayEquals
import org.junit.Test

class Sha512Test {

    @Test
    fun emptyString() {
        assertArrayEquals(
            Hex.decode(
                "cf83e1357eefb8bdf1542850d66d8007d620e4050b5715dc83f4a921d36ce9ce" +
                    "47d0d13c5d85f2b0ff8318d2877eec2f63b931bd47417a81a538327af927da3e"
            ),
            Digest.sha512().hash("".encodeToByteArray())
        )
    }

    @Test
    fun abc() {
        assertArrayEquals(
            Hex.decode(
                "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a" +
                    "2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f"
            ),
            Digest.sha512().hash("abc".encodeToByteArray())
        )
    }

    @Test
    fun longBlock1() {
        assertArrayEquals(
            Hex.decode(
                "204a8fc6dda82f0a0ced7beb8e08a41657c16ef468b228a8279be331a703c335" +
                    "96fd15c13b1b07f9aa1d3bea57789ca031ad85c7a71dd70354ec631238ca3445"
            ),
            Digest.sha512().hash("abcdbcdecdefdefgefghfghighijhijkijkljklmklmnlmnomnopnopq".encodeToByteArray())
        )
    }

    @Test
    fun longBlock2() {
        assertArrayEquals(
            Hex.decode(
                "8e959b75dae313da8cf4f72814fc143f8f7779c6eb9f7fa17299aeadb6889018" +
                    "501d289e4900f7e4331b99dec4b5433ac7d329eeb6dd26545e96e55b874be909"
            ),
            Digest.sha512().hash(
                "abcdefghbcdefghicdefghijdefghijkefghijklfghijklmghijklmnhijklmnoijklmnopjklmnopqklmnopqrlmnopqrsmnopqrstnopqrstu"
                    .encodeToByteArray()
            )
        )
    }

    @Test
    fun millionAs() {
        assertArrayEquals(
            Hex.decode(
                "e718483d0ce769644e2e42c7bc15b4638e1f98b13b2044285632a803afa973eb" +
                    "de0ff244877ea60a4cb0432ce577c31beb009c5c2c49aa2e4eadb217ad8cc09b"
            ),
            Digest.sha512().hash(ByteArray(1_000_000) { 0x61 })
        )
    }
}
