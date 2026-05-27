package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.ScanAddressCodec
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Derives a BIP-352 `(b_scan, b_spend)` keypair from a seed at `m/352'/coin'/account'/{1,0}'/0` and
 * encodes the `sp1q…` address, checking against drongo's `SilentPaymentScanAddressTest` vectors.
 * Pins the derivation paths and the seed → address pipeline.
 */
class SilentPaymentDerivationTest {
    private fun deriveAndEncode(mnemonic: String, account: Int, testnet: Boolean): Triple<String, String, String> {
        val seed = MnemonicCode.toSeed(mnemonic.split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val scan = master.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentScan(account, testnet))).privateKey
        val spend = master.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentSpend(account, testnet))).privateKey
        val address = SilentPaymentAddress(scan.publicKey(), spend.publicKey()).encode(testnet)
        return Triple(scan.value.toHex(), spend.publicKey().toHex(), address)
    }

    @Test
    fun derivesScanSpendAndAddressFromSeed() {
        val (scanPriv, spendPub, address) = deriveAndEncode(
            "life life life life life life life life life life life life", account = 0, testnet = true
        )
        assertEquals("36dc57ced5f4a76059947802f094ea40d0c11c74d444a1e7d3ea5e74b8d83d45", scanPriv)
        assertEquals("03f92466aee84707997b5c596ac52a5867d5d9a4deef1338a9157218cdda9331ee", spendPub)
        assertEquals(
            "tsp1qq0grgkzt7uwfst33pyge7k9mrkag0r9vrklc695n0pw7kwwc7qddqqley3n2a6z8q7vhkhzedtzj5kr86hv6fhh0zvu2j9tjrrxa4ye3acuv6f3q",
            address
        )
    }

    @Test
    fun derivesAddressFromSecondSeed() {
        val (_, _, address) = deriveAndEncode(
            "resist cube wrap sleep catalog shadow door scale stage rail script observe", account = 0, testnet = true
        )
        assertEquals(
            "tsp1qqgksl44sjwjkedsmrfmf2xqsnyt2njtjp5plk2kzjlnd9el2n76awqe5j974lvkf2utv7nrg0eaug55z86n6n3v4e9alnftdzgqk6pqmm5dphvxn",
            address
        )
    }

    @Test
    fun scanKeyExportRoundTripFromDerivedKeys() {
        val seed = MnemonicCode.toSeed("life life life life life life life life life life life life".split(" "), "")
        val master = DeterministicWallet.generate(seed)
        val scan = master.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentScan(0, testnet = true))).privateKey
        val spend = master.derivePrivateKey(KeyPath(DerivationPaths.silentPaymentSpend(0, testnet = true))).privateKey
        val export = ScanAddressCodec.encode(scan, spend.publicKey(), isTestnet = true)
        val (decodedScan, decodedSpend) = ScanAddressCodec.decode(export)
        assertEquals(scan.value.toHex(), decodedScan.value.toHex())
        assertEquals(spend.publicKey().toHex(), decodedSpend.toHex())
    }
}
