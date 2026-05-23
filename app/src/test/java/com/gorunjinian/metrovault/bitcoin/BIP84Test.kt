package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Base58
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class BIP84Test {

    // https://github.com/bitcoin/bips/blob/master/bip-0084.mediawiki
    @Test
    fun bip84ReferenceVectors() {
        val seed = MnemonicCode.toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
            ""
        )
        val master = DeterministicWallet.generate(seed)
        assertEquals(
            "zprvAWgYBBk7JR8Gjrh4UJQ2uJdG1r3WNRRfURiABBE3RvMXYSrRJL62XuezvGdPvG6GFBZduosCc1YP5wixPox7zhZLfiUm8aunE96BBa4Kei5",
            master.encode(DeterministicWallet.zprv)
        )
        assertEquals(
            "zpub6jftahH18ngZxLmXaKw3GSZzZsszmt9WqedkyZdezFtWRFBZqsQH5hyUmb4pCEeZGmVfQuP5bedXTB8is6fTv19U1GQRyQUKQGUTzyHACMF",
            master.extendedPublicKey.encode(DeterministicWallet.zpub)
        )

        val accountKey = master.derivePrivateKey(KeyPath("m/84'/0'/0'"))
        assertEquals(
            "zprvAdG4iTXWBoARxkkzNpNh8r6Qag3irQB8PzEMkAFeTRXxHpbF9z4QgEvBRmfvqWvGp42t42nvgGpNgYSJA9iefm1yYNZKEm7z6qUWCroSQnE",
            accountKey.encode(DeterministicWallet.zprv)
        )
        assertEquals(
            "zpub6rFR7y4Q2AijBEqTUquhVz398htDFrtymD9xYYfG1m4wAcvPhXNfE3EfH1r1ADqtfSdVCToUG868RvUUkgDKf31mGDtKsAYz2oz2AGutZYs",
            accountKey.extendedPublicKey.encode(DeterministicWallet.zpub)
        )

        // 0/0
        val key = accountKey.derivePrivateKey(listOf(0L, 0L))
        assertEquals(master.derivePrivateKey(KeyPath("m/84'/0'/0'/0/0")).secretkeybytes, key.secretkeybytes)
        assertEquals("KyZpNDKnfs94vbrwhJneDi77V6jF64PWPF8x5cdJb8ifgg2DUc9d", key.privateKey.toBase58(Base58.Prefix.SecretKey))
        assertEquals(PublicKey.fromHex("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c"), key.publicKey)
        assertEquals(
            "bc1qcr8te4kr609gcawutmrza0j4xv80jy8z306fyu",
            Bitcoin.computeP2WpkhAddress(key.publicKey, Block.LivenetGenesisBlock.hash)
        )

        // 0/1
        val key1 = accountKey.derivePrivateKey(listOf(0L, 1L))
        assertEquals(master.derivePrivateKey(KeyPath("m/84'/0'/0'/0/1")).secretkeybytes, key1.secretkeybytes)
        assertEquals("Kxpf5b8p3qX56DKEe5NqWbNUP9MnqoRFzZwHRtsFqhzuvUJsYZCy", key1.privateKey.toBase58(Base58.Prefix.SecretKey))
        assertEquals(PublicKey.fromHex("03e775fd51f0dfb8cd865d9ff1cca2a158cf651fe997fdc9fee9c1d3b5e995ea77"), key1.publicKey)
        assertEquals(
            "bc1qnjg0jd8228aq7egyzacy8cys3knf9xvrerkf9g",
            Bitcoin.computeP2WpkhAddress(key1.publicKey, Block.LivenetGenesisBlock.hash)
        )

        // 1/0 (change)
        val key2 = accountKey.derivePrivateKey(listOf(1L, 0L))
        assertEquals(master.derivePrivateKey(KeyPath("m/84'/0'/0'/1/0")).secretkeybytes, key2.secretkeybytes)
        assertEquals("KxuoxufJL5csa1Wieb2kp29VNdn92Us8CoaUG3aGtPtcF3AzeXvF", key2.privateKey.toBase58(Base58.Prefix.SecretKey))
        assertEquals(PublicKey.fromHex("03025324888e429ab8e3dbaf1f7802648b9cd01e9b418485c5fa4c1b9b5700e1a6"), key2.publicKey)
        assertEquals(
            "bc1q8c6fshw2dlwun7ekn9qwf37cu2rn755upcp6el",
            Bitcoin.computeP2WpkhAddress(key2.publicKey, Block.LivenetGenesisBlock.hash)
        )
    }
}
