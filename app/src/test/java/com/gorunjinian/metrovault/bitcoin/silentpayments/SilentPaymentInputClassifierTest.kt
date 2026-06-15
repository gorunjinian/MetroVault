package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Script
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentInputs
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentInputs.SpInputKind
import org.junit.Assert.assertEquals
import org.junit.Test

class SilentPaymentInputClassifierTest {
    private val pub = PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1").publicKey()

    private fun spk(script: List<com.gorunjinian.metrovault.lib.bitcoin.ScriptElt>) =
        Script.write(script).byteVector()

    @Test
    fun classifiesP2tr() {
        assertEquals(SpInputKind.P2TR, SilentPaymentInputs.classify(spk(Script.pay2tr(pub.xOnly()))))
    }

    @Test
    fun classifiesP2wpkh() {
        assertEquals(SpInputKind.P2WPKH, SilentPaymentInputs.classify(spk(Script.pay2wpkh(pub))))
    }

    @Test
    fun classifiesP2pkh() {
        assertEquals(SpInputKind.P2PKH, SilentPaymentInputs.classify(spk(Script.pay2pkh(pub))))
    }

    @Test
    fun classifiesP2shWrappedP2wpkh() {
        val redeem = spk(Script.pay2wpkh(pub))
        val scriptPubKey = spk(Script.pay2sh(Script.pay2wpkh(pub)))
        assertEquals(SpInputKind.P2SH_P2WPKH, SilentPaymentInputs.classify(scriptPubKey, redeemScript = redeem))
    }

    @Test
    fun bareP2shWithoutWrappedP2wpkhIsIneligible() {
        val scriptPubKey = spk(Script.pay2sh(Script.pay2wpkh(pub)))
        assertEquals(SpInputKind.INELIGIBLE, SilentPaymentInputs.classify(scriptPubKey, redeemScript = null))
    }

    @Test
    fun p2wshIsIneligible() {
        assertEquals(SpInputKind.INELIGIBLE, SilentPaymentInputs.classify(spk(Script.pay2wsh(Script.pay2wpkh(pub)))))
    }

    @Test
    fun numsScriptPathTaprootIsIneligible() {
        val scriptPubKey = spk(Script.pay2tr(pub.xOnly()))
        // Witness for a script-path spend: [script, control block]. Control block internal key = NUMS_H.
        val tapscript = byteArrayOf(0x51).byteVector() // OP_1, any leaf script
        val controlBlock = (byteArrayOf(0xc0.toByte()) + SilentPaymentInputs.NUMS_H.toByteArray()).byteVector()
        val witness = listOf(tapscript, controlBlock)
        assertEquals(SpInputKind.INELIGIBLE, SilentPaymentInputs.classify(scriptPubKey, witnessStack = witness))
    }

    @Test
    fun nonNumsScriptPathTaprootStaysEligible() {
        val scriptPubKey = spk(Script.pay2tr(pub.xOnly()))
        val tapscript = byteArrayOf(0x51).byteVector()
        // Internal key is the (non-NUMS) input pubkey x-coord.
        val controlBlock = (byteArrayOf(0xc0.toByte()) + pub.xOnly().value.toByteArray()).byteVector()
        val witness = listOf(tapscript, controlBlock)
        assertEquals(SpInputKind.P2TR, SilentPaymentInputs.classify(scriptPubKey, witnessStack = witness))
    }

    @Test
    fun annexIsStrippedBeforeNumsCheck() {
        val scriptPubKey = spk(Script.pay2tr(pub.xOnly()))
        val tapscript = byteArrayOf(0x51).byteVector()
        val controlBlock = (byteArrayOf(0xc0.toByte()) + SilentPaymentInputs.NUMS_H.toByteArray()).byteVector()
        val annex = (byteArrayOf(0x50) + ByteArray(8)).byteVector()
        val witness = listOf(tapscript, controlBlock, annex)
        assertEquals(SpInputKind.INELIGIBLE, SilentPaymentInputs.classify(scriptPubKey, witnessStack = witness))
    }

    @Test
    fun keyPathTaprootIsEligible() {
        val scriptPubKey = spk(Script.pay2tr(pub.xOnly()))
        // Single witness item (the signature) = key-path spend, not a NUMS script path.
        val witness = listOf(ByteArray(64).byteVector())
        assertEquals(SpInputKind.P2TR, SilentPaymentInputs.classify(scriptPubKey, witnessStack = witness))
    }
}
