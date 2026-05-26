package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentSender
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
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
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * End-to-end (M3): a PSBT v2 paying to a silent-payment recipient is resolved (the derived P2TR
 * script is spliced into the scriptless output), signed, and re-serialized as v2 — proving the
 * v2 read/write adapter and the existing (version-agnostic) signer cooperate.
 */
class SilentPaymentV2SignTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

    private val seed = MnemonicCode.toSeed(
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about".split(" "), ""
    )
    private val master = DeterministicWallet.generate(seed)
    private val accountKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'"))
    private val fingerprint = BitcoinUtils.computeFingerprintLong(master.publicKey)
    private val recipient = SilentPaymentAddress.decode(standardAddress)
    private val op0 = OutPoint(TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101")), 0L)

    @Test
    fun resolveSignAndReserializeV2() {
        val inputKey = master.derivePrivateKey(KeyPath("m/84'/1'/0'/0/0"))
        val pub = inputKey.publicKey

        // P2WPKH input belonging to this wallet (declared via BIP-32 derivation).
        val witnessUtxo = TxOut(Satoshi(100_000), Script.pay2wpkh(pub))
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            witnessUtxo, null, null, emptyMap(),
            mapOf(pub to KeyPathWithMaster(fingerprint, KeyPath("m/84'/1'/0'/0/0"))),
            null, null, emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, emptyList()
        )

        // Scriptless silent-payment recipient output (PSBT_OUT_SP_V0_INFO only), as Sparrow emits.
        val spInfo = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), listOf(DataEntry(ByteVector("09"), spInfo)))

        val tx = Transaction(
            2,
            listOf(TxIn(op0, ByteVector.empty, 0xfffffffeL)),
            listOf(TxOut(Satoshi(90_000), ByteVector.empty)), // SP output: no script yet
            0
        )
        val psbt = Psbt(Global(2, tx, emptyList(), emptyList(), fallbackLocktime = 0L), listOf(input), listOf(output))

        // 1) Resolve: derive the real taproot output and splice its script in.
        val resolved = (SilentPaymentSender.resolve(psbt, master, accountKey, ScriptType.P2WPKH, isTestnet = true) as Either.Right).value
        assertTrue("SP output script must be filled after resolution", Script.isPay2tr(resolved.global.tx.txOut[0].publicKeyScript))

        // 2) Sign the input (must happen after resolution, since the P2WPKH sighash commits to the output script).
        val signResult = resolved.sign(inputKey.privateKey, 0)
        assertTrue("signing must succeed, got $signResult", signResult is Either.Right)
        val signed = (signResult as Either.Right).value.psbt

        // 3) Re-serialize as v2 and read back: still v2, input signed, SP output script + field preserved.
        val reread = (Psbt.read(Psbt.write(signed).toByteArray()) as Either.Right).value
        assertEquals(2L, reread.global.version)
        assertTrue("input must carry a partial signature", reread.inputs[0].partialSigs.isNotEmpty())
        assertTrue("derived P2TR script must survive the round-trip", Script.isPay2tr(reread.global.tx.txOut[0].publicKeyScript))
        assertTrue(
            "PSBT_OUT_SP_V0_INFO must be preserved",
            reread.outputs[0].unknown.any { it.key.size() == 1 && it.key[0] == 0x09.toByte() }
        )
        assertEquals(resolved.global.tx.txOut[0].publicKeyScript, reread.global.tx.txOut[0].publicKeyScript)
    }
}
