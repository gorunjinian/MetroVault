package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.OutPoint
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.TxId
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.EligibleInputKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.RecipientKeys
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Drives the sender-side BIP-352 math through [SilentPayments.deriveOutputs] using the official
 * reference vectors (vendored from drongo's `SilentPaymentUtilsTest.java`, which mirrors the
 * BIP-352 `send_and_receive_test_vectors.json`). This is the load-bearing regression net for the
 * crypto: each expected output is the exact taproot X-only key the recipient will scan for.
 */
class SilentPaymentSenderTest {
    // sp1q… address whose (scan, spend) are STANDARD_SCAN_PRIV / STANDARD_SPEND_PRIV.
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"

    private val txidA = "f4184fc596403b9d638783cf57adfe4c75c605f6356fbc91338530e9831e9e16"
    private val txidB = "a1075db55d416d3ca199f55b6084e2115b9345e16c5cf302fc80e9d5fbf5d48d"

    private fun outpoint(txidHex: String, vout: Long) = OutPoint(TxId(ByteVector32(txidHex)), vout)

    private fun recipient(address: String): RecipientKeys {
        val sp = SilentPaymentAddress.decode(address)
        return RecipientKeys(sp.scanPubKey, sp.spendPubKey)
    }

    private fun derive(
        keys: List<EligibleInputKey>,
        outpoints: List<OutPoint>,
        recipients: List<RecipientKeys>
    ): Map<Int, String> = SilentPayments.deriveOutputs(keys, outpoints, recipients)
        .associate { it.recipientIndex to it.outputKey.value.toHex() }

    @Test
    fun simpleSendTwoP2wpkhInputs() {
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false),
            EligibleInputKey(PrivateKey.fromHex("93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16"), false)
        )
        val outputs = derive(keys, listOf(outpoint(txidA, 0), outpoint(txidB, 0)), listOf(recipient(standardAddress)))
        assertEquals("3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1", outputs[0])
    }

    @Test
    fun inputOrderDoesNotMatter() {
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16"), false),
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false)
        )
        // Inputs swapped relative to simpleSendTwoP2wpkhInputs — result must be identical.
        val outputs = derive(keys, listOf(outpoint(txidB, 0), outpoint(txidA, 0)), listOf(recipient(standardAddress)))
        assertEquals("3e9fce73d4e77a4809908e3c3a2e54ee147b9312dc5044a193d1fc85de46e3c1", outputs[0])
    }

    @Test
    fun smallestOutpointComparesSerializedBytesNotIndex() {
        // Same txid, vout 1 vs vout 256. Serialized (little-endian) outpoint ordering makes
        // vout=256 the smallest, which display-order-then-index ordering would get wrong.
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false),
            EligibleInputKey(PrivateKey.fromHex("93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16"), false)
        )
        val outputs = derive(keys, listOf(outpoint(txidA, 1), outpoint(txidA, 256)), listOf(recipient(standardAddress)))
        assertEquals("a85ef8701394b517a4b35217c4bd37ac01ebeed4b008f8d0879f9e09ba95319c", outputs[0])
    }

    @Test
    fun singleRecipientWithIdenticalInputPrivKeys() {
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false),
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false)
        )
        val outputs = derive(keys, listOf(outpoint(txidA, 0), outpoint(txidB, 0)), listOf(recipient(standardAddress)))
        assertEquals("548ae55c8eec1e736e8d3e520f011f1f42a56d166116ad210b3937599f87f566", outputs[0])
    }

    @Test
    fun twoOutputsToSameRecipientIterateK() {
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), false),
            EligibleInputKey(PrivateKey.fromHex("0378e95685b74565fa56751b84a32dfd18545d10d691641b8372e32164fad66a"), false)
        )
        val outputs = derive(
            keys,
            listOf(outpoint(txidA, 0), outpoint(txidB, 0)),
            listOf(recipient(standardAddress), recipient(standardAddress))
        )
        assertEquals("f207162b1a7abc51c42017bef055e9ec1efc3d3567cb720357e2b84325db33ac", outputs[0])
        assertEquals("e976a58fbd38aeb4e6093d4df02e9c1de0c4513ae0c588cef68cda5b2f8834ca", outputs[1])
    }

    @Test
    fun taprootInputsMixedYNegation() {
        // Taproot inputs: the odd-Y key must be negated before summing (BIP-340).
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), true),
            EligibleInputKey(PrivateKey.fromHex("1d37787c2b7116ee983e9f9c13269df29091b391c04db94239e0d2bc2182c3bf"), true)
        )
        val outputs = derive(keys, listOf(outpoint(txidA, 0), outpoint(txidB, 0)), listOf(recipient(standardAddress)))
        assertEquals("77cab7dd12b10259ee82c6ea4b509774e33e7078e7138f568092241bf26b99f1", outputs[0])
    }

    @Test
    fun mixedTaprootAndNonTaprootInputs() {
        val keys = listOf(
            EligibleInputKey(PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1"), true),
            EligibleInputKey(PrivateKey.fromHex("8d4751f6e8a3586880fb66c19ae277969bd5aa06f61c4ee2f1e2486efdf666d3"), false)
        )
        val outputs = derive(keys, listOf(outpoint(txidA, 0), outpoint(txidB, 0)), listOf(recipient(standardAddress)))
        assertEquals("30523cca96b2a9ae3c98beb5e60f7d190ec5bc79b2d11a0b2d4d09a608c448f0", outputs[0])
    }
}
