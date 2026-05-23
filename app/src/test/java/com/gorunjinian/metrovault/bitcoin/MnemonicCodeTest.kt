package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode.toMnemonics
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode.toSeed
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class MnemonicCodeTest {

    @Test
    fun bip39CanonicalAbandonAboutSeed() {
        val mnemonics = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val seed = MnemonicCode.toSeed(mnemonics, "")
        assertEquals(
            "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc1" +
                "9a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            Hex.encode(seed)
        )
    }

    @Test
    fun bip39SampleVectorWithTrezorPassphrase() {
        // From the canonical BIP-39 vectors (passphrase=TREZOR), single sample.
        val entropy = Hex.decode("00000000000000000000000000000000")
        val expectedMnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
        val expectedSeedHex = "c55257c360c07c72029aebc1b53c05ed0362ada38ead3e3e9efa3708e53495531f09a6987599d18264c1e1c92f2cf141630c7a3c4ab7c81b2f001698e7463b04"
        val mnemonic = toMnemonics(entropy).joinToString(" ")
        assertEquals(expectedMnemonic, mnemonic)
        val seed = toSeed(mnemonic, "TREZOR")
        assertEquals(expectedSeedHex, Hex.encode(seed))
    }

    @Test
    fun validRoundTripForRandomEntropy() {
        val random = Random
        for (length in listOf(16, 20, 24, 28, 32)) {
            repeat(5) {
                val entropy = ByteArray(length).also { random.nextBytes(it) }
                val mnemonics = toMnemonics(entropy)
                MnemonicCode.validate(mnemonics)
            }
        }
    }

    @Test
    fun invalidMnemonicsAreRejected() {
        val invalidMnemonics = listOf(
            "",
            // One word missing
            "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow",
            // One extra word
            "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow fog fog",
            // Wrong word (still in wordlist, but bad checksum)
            "gravity machine north sort system female filter attitude volume fold club stay feature office ecology stable narrow fig"
        )
        for (mnemonic in invalidMnemonics) {
            var threw = false
            try {
                MnemonicCode.validate(mnemonic)
            } catch (_: Throwable) {
                threw = true
            }
            assertTrue("expected '$mnemonic' to fail validation", threw)
        }
    }
}
