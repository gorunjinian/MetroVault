package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.DataEntry
import com.gorunjinian.metrovault.lib.bitcoin.Global
import com.gorunjinian.metrovault.lib.bitcoin.Input
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.Output
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxId
import com.gorunjinian.metrovault.lib.bitcoin.TxIn
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentInfo
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentLabel
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentTweak
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class SilentPaymentPsbtFieldTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"
    private val tweakHex = "0202020202020202020202020202020202020202020202020202020202020202"

    /** A minimal SP-bearing PSBT: one input carrying PSBT_IN_SP_TWEAK, one P2TR output carrying
     *  PSBT_OUT_SP_V0_INFO + PSBT_OUT_SP_V0_LABEL, all stored as preserved unknown entries. */
    private fun buildSpPsbt(labelIndex: Int = 7): Psbt {
        val recipient = SilentPaymentAddress.decode(standardAddress)
        val spInfoValue = ByteVector(recipient.scanPubKey.value.toByteArray() + recipient.spendPubKey.value.toByteArray())
        val labelValue = ByteVector(Pack.writeInt32LE(labelIndex))
        val tweakValue = ByteVector(tweakHex)

        val outputEntries = listOf(
            DataEntry(ByteVector("09"), spInfoValue),
            DataEntry(ByteVector("0a"), labelValue)
        )
        val inputEntries = listOf(DataEntry(ByteVector("20"), tweakValue))

        val txid = TxId(ByteVector32("0101010101010101010101010101010101010101010101010101010101010101"))
        val txIn = TxIn(OutPoint(txid, 0L), ByteVector.empty, 0xffffffffL)
        val dummyP2tr = TxOut(Satoshi(10_000), Script.pay2tr(recipient.spendPubKey.xOnly()))
        val tx = Transaction(2, listOf(txIn), listOf(dummyP2tr), 0)

        val inputUtxo = TxOut(Satoshi(20_000), Script.pay2wpkh(recipient.spendPubKey))
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            inputUtxo, null, null, emptyMap(), emptyMap(), null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, inputEntries
        )
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), outputEntries)

        return Psbt(Global(0, tx, emptyList(), emptyList()), listOf(input), listOf(output))
    }

    @Test
    fun roundTripIsByteIdentical() {
        val original = Psbt.write(buildSpPsbt())
        val reparsed = (Psbt.read(original) as Either.Right).value
        assertEquals(original, Psbt.write(reparsed))
    }

    @Test
    fun accessorsReadParsedSpFields() {
        val reparsed = (Psbt.read(Psbt.write(buildSpPsbt(labelIndex = 7))) as Either.Right).value
        val recipient = SilentPaymentAddress.decode(standardAddress)

        val info = reparsed.outputs[0].silentPaymentInfo
        assertEquals(recipient.scanPubKey.toHex(), info!!.scanPubKey.toHex())
        assertEquals(recipient.spendPubKey.toHex(), info.spendPubKey.toHex())
        assertEquals(7L, reparsed.outputs[0].silentPaymentLabel)
        assertEquals(tweakHex, reparsed.inputs[0].silentPaymentTweak!!.toHex())
    }

    @Test
    fun labelIsLittleEndianUint32() {
        // 0x0a0b0c0d little-endian on the wire must decode to the integer 0x0d0c0b0a.
        val reparsed = (Psbt.read(Psbt.write(buildSpPsbt(labelIndex = 0x0d0c0b0a))) as Either.Right).value
        assertEquals(0x0d0c0b0aL, reparsed.outputs[0].silentPaymentLabel)
    }

    @Test
    fun absentSpFieldsReturnNull() {
        // A plain output / input with no SP unknown entries.
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), emptyList())
        assertNull(output.silentPaymentInfo)
        assertNull(output.silentPaymentLabel)
    }

    @Test
    fun malformedSpInfoLengthIsRejectedOnAccess() {
        val badInfo = listOf(DataEntry(ByteVector("09"), ByteVector("00112233")))
        val output = Output.UnspecifiedOutput(emptyMap(), null, emptyMap(), badInfo)
        assertThrows(IllegalArgumentException::class.java) { output.silentPaymentInfo }
    }

    @Test
    fun malformedTweakLengthIsRejectedOnAccess() {
        val badTweak = listOf(DataEntry(ByteVector("20"), ByteVector("0202")))
        val input = Input.WitnessInput.PartiallySignedWitnessInput(
            TxOut(Satoshi(20_000), Script.pay2wpkh(SilentPaymentAddress.decode(standardAddress).spendPubKey)),
            null, null, emptyMap(), emptyMap(), null, null,
            emptySet(), emptySet(), emptySet(), emptySet(), null, emptyMap(), null, badTweak
        )
        assertThrows(IllegalArgumentException::class.java) { input.silentPaymentTweak }
    }
}
