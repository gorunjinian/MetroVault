package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.domain.service.psbt.PsbtAnalyzer
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentDisplayContext
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.lib.bitcoin.Bitcoin
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.KeyPathWithMaster
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxId
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.EligibleInputKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.RecipientKeys
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Exercises the PsbtAnalyzer display glue (Option A) directly on a parsed Psbt — the public
 * `getPsbtDetails(base64, …)` entrypoint can't run on the JVM because it decodes via
 * android.util.Base64, which isn't available in unit tests.
 */
class SilentPaymentAnalyzerDisplayTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val accountKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'"))
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val recipient = SilentPaymentAddress.decode(standardAddress)
    private val chainHash = BitcoinUtils.getChainHash(isTestnet = true)

    private val op0 = OutPoint(TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")), 0L)
    private val op1 = OutPoint(TxId(ByteVector32("0202020202020202020202020202020202020202020202020202020202020202")), 1L)

    private fun walletInput(index: Int): Input {
        val pub = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/$index")).publicKey
        val spk = Script.write(Script.pay2wpkh(pub)).byteVector()
        return Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(50_000), spk), null, null, emptyMap(),
            mapOf(pub to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/$index"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )
    }

    private fun spPsbt(): Psbt {
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val tx = Transaction(
            2,
            listOf(TxIn(op0, ByteVector.empty, 0xffffffffL), TxIn(op1, ByteVector.empty, 0xffffffffL)),
            listOf(TxOut(Satoshi(90_000), Script.pay2tr(recipient.spendPubKey.xOnly()))),
            0
        )
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo)))
        return Psbt(Global(0, tx, emptyList(), emptyList()), listOf(walletInput(0), walletInput(1)), listOf(output))
    }

    private fun expectedDerivedAddress(): String {
        val eligible = listOf(
            EligibleInputKey(master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/0")).privateKey, false),
            EligibleInputKey(master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/1")).privateKey, false)
        )
        val derived = SilentPayments.deriveOutputs(
            eligible, listOf(op0, op1), listOf(RecipientKeys(recipient.scanPubKey, recipient.spendPubKey))
        )
        val script = Script.parse(Script.write(Script.pay2tr(derived.first().outputKey)))
        return (Bitcoin.addressFromPublicKeyScript(chainHash, script) as com.gorunjinian.metrovault.lib.bitcoin.utils.Either.Right).value
    }

    @Test
    fun withContextResolvesNominalAndDerivedAddresses() {
        val context = SilentPaymentDisplayContext(master, accountKey, ScriptType.P2WPKH)
        val result = PsbtAnalyzer.resolveSilentPaymentsForDisplay(spPsbt(), isTestnet = true, chainHash, context)

        // Nominal sp1q… (re-encoded for testnet → tsp1q…) is surfaced per output index.
        assertEquals(recipient.encode(isTestnet = true), result.nominalByIndex[0])

        // One resolution, mapping the nominal address to the derived taproot address.
        assertEquals(1, result.resolutions.size)
        val resolution = result.resolutions.first()
        assertEquals(0, resolution.outputIndex)
        assertEquals(recipient.encode(isTestnet = true), resolution.recipient.address)
        assertEquals(expectedDerivedAddress(), resolution.derivedOutputAddress)

        // The effective tx output script was rewritten to the derived P2TR.
        val derivedAddress = com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
            .extractAddressFromOutput(result.effectiveTx.txOut[0], chainHash)
        assertEquals(expectedDerivedAddress(), derivedAddress)
    }

    @Test
    fun withoutContextShowsNominalOnly() {
        val result = PsbtAnalyzer.resolveSilentPaymentsForDisplay(spPsbt(), isTestnet = true, chainHash, context = null)
        assertEquals(recipient.encode(isTestnet = true), result.nominalByIndex[0])
        assertTrue(result.resolutions.isEmpty())
        // Without keys we cannot derive the real output; the placeholder script is left in place.
        assertEquals(spPsbt().global.tx.txOut[0].publicKeyScript, result.effectiveTx.txOut[0].publicKeyScript)
    }
}
