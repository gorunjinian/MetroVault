package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.ScriptTree
import com.gorunjinian.metrovault.lib.bitcoin.XonlyPublicKey
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import org.junit.Assert.assertEquals
import org.junit.Test

class BIP86Test {

    // https://github.com/bitcoin/bips/blob/master/bip-0086.mediawiki
    @Test
    fun bip86ReferenceVectors() {
        val seed = MnemonicCode.toSeed(
            "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "),
            ""
        )
        val master = DeterministicWallet.generate(seed)
        assertEquals(
            "xprv9s21ZrQH143K3GJpoapnV8SFfukcVBSfeCficPSGfubmSFDxo1kuHnLisriDvSnRRuL2Qrg5ggqHKNVpxR86QEC8w35uxmGoggxtQTPvfUu",
            master.encode(DeterministicWallet.xprv)
        )
        assertEquals(
            "xpub661MyMwAqRbcFkPHucMnrGNzDwb6teAX1RbKQmqtEF8kK3Z7LZ59qafCjB9eCRLiTVG3uxBxgKvRgbubRhqSKXnGGb1aoaqLrpMBDrVxga8",
            master.extendedPublicKey.encode(DeterministicWallet.xpub)
        )

        val accountKey = master.derivePrivateKey(KeyPath("m/86'/0'/0'"))
        assertEquals(
            "xprv9xgqHN7yz9MwCkxsBPN5qetuNdQSUttZNKw1dcYTV4mkaAFiBVGQziHs3NRSWMkCzvgjEe3n9xV8oYywvM8at9yRqyaZVz6TYYhX98VjsUk",
            accountKey.encode(DeterministicWallet.xprv)
        )
        assertEquals(
            "xpub6BgBgsespWvERF3LHQu6CnqdvfEvtMcQjYrcRzx53QJjSxarj2afYWcLteoGVky7D3UKDP9QyrLprQ3VCECoY49yfdDEHGCtMMj92pReUsQ",
            accountKey.extendedPublicKey.encode(DeterministicWallet.xpub)
        )

        // 0/0
        run {
            val key = accountKey.derivePrivateKey(listOf(0L, 0L))
            assertEquals(master.derivePrivateKey(KeyPath("m/86'/0'/0'/0/0")).secretkeybytes, key.secretkeybytes)
            val internalKey = XonlyPublicKey(key.publicKey)
            assertEquals(ByteVector32("cc8a4bc64d897bddc5fbc2f670f7a8ba0b386779106cf1223c6fc5d7cd6fc115"), internalKey.value)
            val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
            assertEquals(ByteVector32("a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c"), outputKey.value)
            val script = Script.pay2tr(internalKey, null as ScriptTree?)
            assertEquals(
                ByteVector("5120a60869f0dbcf1dc659c9cecbaf8050135ea9e8cdc487053f1dc6880949dc684c"),
                Script.write(script).byteVector()
            )
            assertEquals(
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, script).right
            )
            assertEquals(
                "bc1p5cyxnuxmeuwuvkwfem96lqzszd02n6xdcjrs20cac6yqjjwudpxqkedrcr",
                internalKey.p2trAddress(Block.LivenetGenesisBlock.hash)
            )
        }

        // 0/1
        run {
            val key = accountKey.derivePrivateKey(listOf(0L, 1L))
            assertEquals(master.derivePrivateKey(KeyPath("m/86'/0'/0'/0/1")).secretkeybytes, key.secretkeybytes)
            val internalKey = XonlyPublicKey(key.publicKey)
            assertEquals(ByteVector32("83dfe85a3151d2517290da461fe2815591ef69f2b18a2ce63f01697a8b313145"), internalKey.value)
            val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
            assertEquals(ByteVector32("a82f29944d65b86ae6b5e5cc75e294ead6c59391a1edc5e016e3498c67fc7bbb"), outputKey.value)
            val script = Script.pay2tr(internalKey, null as ScriptTree?)
            assertEquals(
                ByteVector("5120a82f29944d65b86ae6b5e5cc75e294ead6c59391a1edc5e016e3498c67fc7bbb"),
                Script.write(script).byteVector()
            )
            assertEquals(
                "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
                Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, script).right
            )
            assertEquals(
                "bc1p4qhjn9zdvkux4e44uhx8tc55attvtyu358kutcqkudyccelu0was9fqzwh",
                Bitcoin.computeBIP86Address(internalKey, Block.LivenetGenesisBlock.hash)
            )
        }

        // 1/0 (change)
        run {
            val key = accountKey.derivePrivateKey(listOf(1L, 0L))
            assertEquals(master.derivePrivateKey(KeyPath("m/86'/0'/0'/1/0")).secretkeybytes, key.secretkeybytes)
            val internalKey = XonlyPublicKey(key.publicKey)
            assertEquals(ByteVector32("399f1b2f4393f29a18c937859c5dd8a77350103157eb880f02e8c08214277cef"), internalKey.value)
            val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
            assertEquals(ByteVector32("882d74e5d0572d5a816cef0041a96b6c1de832f6f9676d9605c44d5e9a97d3dc"), outputKey.value)
            val script = Script.pay2tr(internalKey, null as ScriptTree?)
            assertEquals(
                ByteVector("5120882d74e5d0572d5a816cef0041a96b6c1de832f6f9676d9605c44d5e9a97d3dc"),
                Script.write(script).byteVector()
            )
            assertEquals(
                "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7",
                Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, script).right
            )
            assertEquals(
                "bc1p3qkhfews2uk44qtvauqyr2ttdsw7svhkl9nkm9s9c3x4ax5h60wqwruhk7",
                internalKey.p2trAddress(Block.LivenetGenesisBlock.hash)
            )
        }
    }

    @Test
    fun computeTaprootAddressTestnet() {
        val (_, master) = DeterministicWallet.ExtendedPrivateKey.decode(
            "tprv8ZgxMBicQKsPeQQADibg4WF7mEasy3piWZUHyThAzJCPNgMHDVYhTCVfev3jFbDhcYm4GimeFMbbi9z1d9rfY1aL5wfJ9mNebQ4thJ62EJb"
        )
        val key = master.derivePrivateKey("86'/1'/0'/0/1")
        val internalKey = XonlyPublicKey(key.publicKey)
        val outputKey = internalKey.outputKey(Crypto.TaprootTweak.NoScriptTweak).first
        assertEquals(
            "tb1phlhs7afhqzkgv0n537xs939s687826vn8l24ldkrckvwsnlj3d7qj6u57c",
            Bech32.encodeWitnessAddress("tb", 1, outputKey.value.toByteArray())
        )
        assertEquals(
            "tb1phlhs7afhqzkgv0n537xs939s687826vn8l24ldkrckvwsnlj3d7qj6u57c",
            internalKey.p2trAddress(Block.Testnet3GenesisBlock.hash)
        )
    }

    @Test
    fun computeManyTaprootAddresses() {
        // Created by bitcoin core wallet with descriptor:
        //   tr(tprv8ZgxMBicQKsPdyyuveRPhVYogdPXBDqRiUXDo5TcLKe3f9YfonipqbgJD7pCXdovZTfTyj6SjZ928SkPunnDTiXV7Y2HSsG9XAGki6n8dRF/86h/1h/0h/0/*)
        val expected = listOf(
            "tb1pufpxa6zyvkdrz52qhtt9r5hl7pts7r3a5anndeupt0yqys8s8s6q662987",
            "tb1pa978mqd3rfj0k33ef4u7nrc7qh3s08wy9fd3sfl770c9fqc5mh5qzgpymf",
            "tb1pm3xkwh3av3mlsr25mvk320lq94xpjzkv9l3u8x5w8ppwz5nfpqgqpjaehc",
            "tb1pdyzhpg5yletzl07yks0eqwgxkddf23cy6vj64wd42tpc0xnglsvs7xd0zd",
            "tb1pks4qar2hlhvpzcuw6tj77x3xunt9jcgnm4lk6eu5d765vxv94sfsjw8qp2",
            "tb1pdtjstl80rtl7lwuhktfw86sv0g65079sh6gsaa9qg6lcrph6xmsqtcn70y",
            "tb1p6tlajumgdvlhfm6m8h3v5zche4dvfk3ey60vpux5wqeaysm6apuqm987j9",
            "tb1pn0w8e85ml8chl2vda83euynvrxtelpww8m2mnzf3ugpc7w6zl3lqdd7afu",
            "tb1px03j0r6kru2nrwtq04m8v54q8t3shkfk7vgh704as5kfd0fanalqygu53c",
            "tb1ps0qzx37ckd0cx209qa65q4kxha7kcr8vx63hgv6wk2y7jwqxq6lsnz4wue"
        )
        val (_, master) = DeterministicWallet.ExtendedPrivateKey.decode(
            "tprv8ZgxMBicQKsPdyyuveRPhVYogdPXBDqRiUXDo5TcLKe3f9YfonipqbgJD7pCXdovZTfTyj6SjZ928SkPunnDTiXV7Y2HSsG9XAGki6n8dRF"
        )
        for (i in 0 until 10) {
            val key = master.derivePrivateKey("86'/1'/0'/0/$i")
            assertEquals(expected[i], XonlyPublicKey(key.publicKey).p2trAddress(Block.Testnet3GenesisBlock.hash))
        }
    }
}
