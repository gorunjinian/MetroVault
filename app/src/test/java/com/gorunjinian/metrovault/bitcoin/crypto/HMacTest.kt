package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertEquals
import org.junit.Test

class HMacTest {

    @Test
    fun hmacSha512BitcoinSeed() {
        // BIP-32 reference: HMAC-SHA512 with key "Bitcoin seed" over the seed bytes 000102…0f
        val data = Hex.decode("000102030405060708090a0b0c0d0e0f")
        val key = Hex.decode("426974636f696e2073656564")
        val mac = Crypto.hmac512(key, data)
        assertEquals(
            "e8f32e723decf4051aefac8e2c93c9c5b214313817cdb01a1494b917c8436b35" +
                "873dff81c02f525623fd1fe5167eac3a55a049de3d314bb42ee227ffed37d508",
            Hex.encode(mac)
        )
    }
}
