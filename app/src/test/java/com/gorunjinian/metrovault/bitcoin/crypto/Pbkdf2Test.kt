package com.gorunjinian.metrovault.bitcoin.crypto

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pbkdf2
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertEquals
import org.junit.Test

class Pbkdf2Test {

    // BIP-39 vector: mnemonic "abandon ... about" → seed (no passphrase)
    @Test
    fun bip39MnemonicToSeed() {
        val password = Hex.decode(
            "6162616e646f6e206162616e646f6e206162616e646f6e206162616e646f6e20" +
                "6162616e646f6e206162616e646f6e206162616e646f6e206162616e646f6e20" +
                "6162616e646f6e206162616e646f6e206162616e646f6e2061626f7574"
        )
        val salt = Hex.decode("6d6e656d6f6e6963")
        val result = Pbkdf2.withHmacSha512(password, salt, 2048, 64)
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Hex.encode(result)
        )
    }
}
