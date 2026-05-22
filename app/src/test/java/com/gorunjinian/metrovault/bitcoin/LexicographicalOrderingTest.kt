package com.gorunjinian.metrovault.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.LexicographicalOrdering
import com.gorunjinian.metrovault.lib.bitcoin.LexicographicalOrdering.compare
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.Satoshi
import com.gorunjinian.metrovault.lib.bitcoin.Transaction
import com.gorunjinian.metrovault.lib.bitcoin.TxOut
import fr.acinq.secp256k1.Hex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LexicographicalOrderingTest {

    @Test
    fun sortBytes() {
        assertFalse(LexicographicalOrdering.isLessThan(ByteVector.empty, ByteVector.empty))
        assertFalse(LexicographicalOrdering.isLessThan(ByteVector("aaaa"), ByteVector("aaaa")))
        assertTrue(LexicographicalOrdering.isLessThan(ByteVector.empty, ByteVector("aa")))
        assertTrue(LexicographicalOrdering.isLessThan(ByteVector("aaaa"), ByteVector("aaaa00")))
        assertFalse(LexicographicalOrdering.isLessThan(ByteVector("aaaa00"), ByteVector("aaaa")))
    }

    @Test
    fun sortOutputsWithSimilarPublicKeyScript() {
        val txOut1 = TxOut(Satoshi(14_999), Hex.decode("12345678"))
        val txOut2 = TxOut(Satoshi(15_000), Hex.decode("123456"))
        val txOut3 = TxOut(Satoshi(15_000), Hex.decode("12345678"))
        val tx = LexicographicalOrdering.sort(Transaction(2, listOf(), listOf(txOut3, txOut2, txOut1), 0))
        assertEquals(Transaction(2, listOf(), listOf(txOut1, txOut2, txOut3), 0), tx)
    }

    @Test
    fun sortPublicKeys() {
        val pk1 = PublicKey.fromHex("0230d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
        val pk2 = PublicKey.fromHex("0230d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3d")
        val pk3 = PublicKey.fromHex("0330d54fd0dd420a6e5f8d3624f5f3482cae350f79d5f0753bf5beef9c2d91af3c")
        val pk4 = PublicKey.fromHex("03a1af804ac108a8a51782198c2d034b28bf90c8803f5a53f76276fa69a4eae77f")
        assertFalse(LexicographicalOrdering.isLessThan(pk1, pk1))
        assertTrue(LexicographicalOrdering.isLessThan(pk1, pk2))
        assertFalse(LexicographicalOrdering.isLessThan(pk3, pk2))
        assertTrue(LexicographicalOrdering.isLessThan(pk2, pk3))
        assertTrue(LexicographicalOrdering.isLessThan(pk3, pk4))

        val sorted = listOf(pk3, pk2, pk1, pk4).sortedWith { a, b -> compare(a, b) }
        assertEquals(listOf(pk1, pk2, pk3, pk4), sorted)
    }
}
