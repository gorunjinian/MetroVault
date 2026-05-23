package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Base58
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BIP49Test {

    // https://github.com/bitcoin/bips/blob/master/bip-0049.mediawiki
    @Test
    fun bip49ReferenceVectors() {
        val seed = MnemonicCode.toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
            ""
        )
        val master = DeterministicWallet.generate(seed)
        assertEquals(
            "tprv8ZgxMBicQKsPe5YMU9gHen4Ez3ApihUfykaqUorj9t6FDqy3nP6eoXiAo2ssvpAjoLroQxHqr3R5nE3a5dU3DHTjTgJDd7zrbniJr6nrCzd",
            master.encode(DeterministicWallet.tprv)
        )

        val accountKey = master.derivePrivateKey(KeyPath("m/49'/1'/0'"))
        assertEquals(
            "tprv8gRrNu65W2Msef2BdBSUgFdRTGzC8EwVXnV7UGS3faeXtuMVtGfEdidVeGbThs4ELEoayCAzZQ4uUji9DUiAs7erdVskqju7hrBcDvDsdbY",
            accountKey.encode(DeterministicWallet.tprv)
        )

        val key = accountKey.derivePrivateKey(listOf(0L, 0L))
        assertEquals(master.derivePrivateKey(KeyPath("m/49'/1'/0'/0/0")).secretkeybytes, key.secretkeybytes)
        assertEquals("cULrpoZGXiuC19Uhvykx7NugygA3k86b3hmdCeyvHYQZSxojGyXJ", key.privateKey.toBase58(Base58.Prefix.SecretKeyTestnet))
        assertEquals(PrivateKey.fromHex("c9bdb49cfbaedca21c4b1f3a7803c34636b1d7dc55a717132443fc3f4c5867e801"), key.privateKey)
        assertEquals(PublicKey.fromHex("03a1af804ac108a8a51782198c2d034b28bf90c8803f5a53f76276fa69a4eae77f"), key.publicKey)
        assertEquals(
            "2Mww8dCYPUpKHofjgcXcBCEGmniw9CoaiD2",
            Bitcoin.computeBIP49Address(key.publicKey, Block.Testnet3GenesisBlock.hash)
        )
    }
}
