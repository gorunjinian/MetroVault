package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.silentPaymentInfo
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Base64

/**
 * Parses a real PSBT v2 (BIP-370) silent-payment send exported from Sparrow, confirming the
 * synthesize-tx reader (Step 0 / M1) reconstructs the transaction and surfaces the SP recipient.
 */
class PsbtV2Test {
    private fun fixtureBytes(): ByteArray {
        val b64 = this::class.java.getResourceAsStream("/bip-0370/sparrow-sp-send-v2.psbt.base64")!!
            .readBytes().toString(Charsets.UTF_8).trim()
        return Base64.getDecoder().decode(b64)
    }

    private fun parse(): Psbt {
        val result = Psbt.read(fixtureBytes())
        assertTrue("expected Right, got $result", result is Either.Right)
        return (result as Either.Right).value
    }

    @Test
    fun v2WriteRoundTripIsByteIdentical() {
        // Read the real Sparrow v2 PSBT, re-serialize, and require byte-for-byte equality.
        val original = fixtureBytes()
        val rewritten = Psbt.write(parse()).toByteArray()
        org.junit.Assert.assertArrayEquals(original, rewritten)
    }

    @Test
    fun parsesSparrowV2SilentPaymentPsbt() {
        val psbt = parse()
        assertEquals(2L, psbt.global.version)
        assertEquals(2L, psbt.global.tx.version)
        assertEquals(1, psbt.inputs.size)
        assertEquals(2, psbt.outputs.size)
        assertEquals(1, psbt.global.tx.txIn.size)
        assertEquals(2, psbt.global.tx.txOut.size)
        // Fallback locktime 0x000e82bb (little-endian bb820e00 on the wire).
        assertEquals(0x000e82bbL, psbt.global.tx.lockTime)
        assertEquals(0x000e82bbL, psbt.global.fallbackLocktime)
    }

    @Test
    fun inputOutpointMatchesNonWitnessUtxo() {
        val psbt = parse()
        val input = psbt.inputs[0]
        val outPoint = psbt.global.tx.txIn[0].outPoint
        // The synthesized outpoint's txid must equal the supplied previous (non-witness) tx's id,
        // which pins the PSBT_IN_PREVIOUS_TXID little-endian byte-order handling.
        assertNotNull(input.nonWitnessUtxo)
        assertEquals(input.nonWitnessUtxo!!.txid, outPoint.txid)
        assertTrue(outPoint.index < input.nonWitnessUtxo!!.txOut.size)
    }

    @Test
    fun silentPaymentRecipientOutputIsSurfaced() {
        val psbt = parse()
        // Exactly one output is an SP recipient (carries PSBT_OUT_SP_V0_INFO, no scriptPubKey yet).
        val spOutputs = psbt.outputs.mapIndexedNotNull { i, o -> o.silentPaymentInfo?.let { i to it } }
        assertEquals(1, spOutputs.size)
        val (idx, info) = spOutputs[0]
        assertNotNull(info.scanPubKey)
        assertNotNull(info.spendPubKey)
        // The SP output has no script yet (to be derived by the signer); the other output does.
        assertTrue("SP output script should be empty pre-resolution", psbt.global.tx.txOut[idx].publicKeyScript.isEmpty())
        val nonSpIndex = if (idx == 0) 1 else 0
        assertTrue("non-SP output should have a script", psbt.global.tx.txOut[nonSpIndex].publicKeyScript.size() > 0)
    }

    @Test
    fun nonSpOutputDecodesToValidScript() {
        val psbt = parse()
        // The change output is a standard P2WPKH; confirm it parses as a script.
        val nonSp = psbt.outputs.indexOfFirst { it.silentPaymentInfo == null }
        val script = psbt.global.tx.txOut[nonSp].publicKeyScript
        assertTrue(Script.isPay2wpkh(script.toByteArray()))
    }

    @Test
    fun v1IsRejected() {
        // Flip the global version field's value to 1 is non-trivial here; instead assert that a
        // crafted v1 magic+version is rejected. We reuse the v2 fixture's structure conceptually:
        // a standalone check that the reader rejects version 1.
        // (Construct a minimal buffer: magic + a 0xfb version=1 global key + separator.)
        val buf = mutableListOf<Byte>()
        buf.addAll(listOf(0x70, 0x73, 0x62, 0x74, 0xff).map { it.toByte() })
        buf.add(0x01); buf.add(0xfb.toByte())              // key len 1, keytype 0xfb
        buf.add(0x04); buf.addAll(listOf(0x01, 0x00, 0x00, 0x00).map { it.toByte() }) // value len 4, version=1 LE
        buf.add(0x00)                                       // global separator
        val result = Psbt.read(buf.toByteArray())
        assertTrue(result is Either.Left)
    }

    @Test
    fun absentSpInfoOnChangeOutput() {
        val psbt = parse()
        val nonSp = psbt.outputs.first { it.silentPaymentInfo == null }
        assertNull(nonSp.silentPaymentInfo)
    }
}
