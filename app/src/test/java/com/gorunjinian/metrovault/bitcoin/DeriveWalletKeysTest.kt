package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import org.junit.Assert.assertEquals
import org.junit.Test

class DeriveWalletKeysTest {
    private val mnemonics = "gun please vital unable phone catalog explain raise erosion zoo truly exist"
    private val seed = MnemonicCode.toSeed(mnemonics, "")
    private val master = DeterministicWallet.generate(seed)

    @Test
    fun restoreBip44Wallet() {
        val account = master.derivePrivateKey(KeyPath("m/44'/1'/0'"))
        val xpub = account.extendedPublicKey.encode(DeterministicWallet.tpub)
        assertEquals(
            "tpubDDamug2qVwe94yFJ38MM3ek2LiWiyjMmkQPhYMnHNZz5XHj7bj8xc7pFmyiYnCfqrSy62e1196qcpmKYhcUMcBTGMW4mEWf1v9H8wNtLZku",
            xpub
        )
        assertEquals(
            listOf(
                "mmpDgTP9FQbJCcdkkuXLbjbvqg3j33Zw3H",
                "mtXgQHM7Eawr6rjDWh7CrFtBQnbibviekL",
                "mw39H2JNixLuXLfTXqZr53M1n18ekPNi9U",
                "mnK3W3DMnkKMPT3Kbx6gvrmWxch6BhNHoo",
                "mpotVZLVr3fgbuBD2jzmwxVg7iATpq7YME"
            ),
            deriveAddresses(xpub, DerivationScheme.BIP44)
        )
    }

    @Test
    fun restoreBip49Wallet() {
        val account = master.derivePrivateKey(KeyPath("m/49'/1'/0'"))
        val xpub = account.extendedPublicKey.encode(DeterministicWallet.upub)
        assertEquals(
            "upub5DKk7kdrLoL3HqrfVdf3mLZJ59g6Bix8UtB6YJQNSKfE3E6YU2Vq7dH7E8ce87jUAac4nRag6Zd7c2cXs45Q4nJcLdrJyNWPxS5D9LFSpGL",
            xpub
        )
        assertEquals(
            listOf(
                "2NAV38YdZBS6s6b89QdmyPnjBxn6Jn3BkhQ",
                "2Mzxym6Rey5Mwnnxh6L134MaHFwTPQB4fdx",
                "2N8tTGMc57REfePZzPkWqEGaYKHsrVsW3LJ",
                "2Mxfuivcx4TdGroh6Q2GmCR5rQB46fjJUtn",
                "2N7uWEqMPCjzHynqSDaAnydZD6WfEpH9ekz"
            ),
            deriveAddresses(xpub, DerivationScheme.BIP49)
        )
    }

    @Test
    fun restoreBip84Wallet() {
        val account = master.derivePrivateKey(KeyPath("m/84'/1'/0'"))
        val xpub = account.extendedPublicKey.encode(DeterministicWallet.vpub)
        assertEquals(
            "vpub5YmxxDXhaEfLoqxn8xJExGMSQepxRbJDFqyc9FpDKyW8z966eDsgqbTHnJCvc698MhN3FDRt49DuPBgdRufopecaeyffJCUKXRKHoNn7BhX",
            xpub
        )
        assertEquals(
            listOf(
                "tb1ql63el50rtln6n4kxa76jrhuts3kxmk9wtz6hp0",
                "tb1qa2hyhca4y07xqcl9r9m63rtv4hgdh063hldn6r",
                "tb1q0lywyl3cdkuw29yuh6w0frqh4hnxdj0m4e78eq",
                "tb1q4dg72vn06mrjh3yyzpkws3w2z0whrys8g2a997",
                "tb1qx4g3glhflr42clkkla9ty0vmfcmme9a426mrc2"
            ),
            deriveAddresses(xpub, DerivationScheme.BIP84)
        )
    }

    private sealed class DerivationScheme {
        data object BIP44 : DerivationScheme()
        data object BIP49 : DerivationScheme()
        data object BIP84 : DerivationScheme()
    }

    private fun deriveAddresses(xpub: String, scheme: DerivationScheme): List<String> {
        val (prefix, account) = DeterministicWallet.ExtendedPublicKey.decode(xpub)
        return (0L..4L).map { i ->
            val pub: PublicKey = account.derivePublicKey(listOf(0L, i)).publicKey
            when {
                prefix == DeterministicWallet.tpub && scheme is DerivationScheme.BIP44 ->
                    Bitcoin.computeBIP44Address(pub, Block.Testnet3GenesisBlock.hash)
                prefix == DeterministicWallet.tpub && scheme is DerivationScheme.BIP49 ->
                    Bitcoin.computeBIP49Address(pub, Block.Testnet3GenesisBlock.hash)
                prefix == DeterministicWallet.upub && scheme is DerivationScheme.BIP49 ->
                    Bitcoin.computeBIP49Address(pub, Block.Testnet3GenesisBlock.hash)
                prefix == DeterministicWallet.vpub && scheme is DerivationScheme.BIP84 ->
                    Bitcoin.computeP2WpkhAddress(pub, Block.Testnet3GenesisBlock.hash)
                prefix == DeterministicWallet.xpub && scheme is DerivationScheme.BIP44 ->
                    Bitcoin.computeBIP44Address(pub, Block.LivenetGenesisBlock.hash)
                prefix == DeterministicWallet.xpub && scheme is DerivationScheme.BIP49 ->
                    Bitcoin.computeBIP49Address(pub, Block.LivenetGenesisBlock.hash)
                prefix == DeterministicWallet.ypub && scheme is DerivationScheme.BIP49 ->
                    Bitcoin.computeBIP49Address(pub, Block.LivenetGenesisBlock.hash)
                prefix == DeterministicWallet.zpub && scheme is DerivationScheme.BIP84 ->
                    Bitcoin.computeP2WpkhAddress(pub, Block.LivenetGenesisBlock.hash)
                else -> error("unsupported prefix=$prefix / scheme=$scheme")
            }
        }
    }
}
