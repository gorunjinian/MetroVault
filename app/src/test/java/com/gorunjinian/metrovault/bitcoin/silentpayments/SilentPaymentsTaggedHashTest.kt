package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector
import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.Crypto
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.PublicKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import org.junit.Assert.assertEquals
import org.junit.Test

class SilentPaymentsTaggedHashTest {
    @Test
    fun taggedHashMatchesBip340Construction() {
        val tag = SilentPayments.BIP0352_INPUTS_TAG
        val x = ByteVector("000102030405060708090a0b0c0d0e0f").toByteArray()
        val hashedTag = Crypto.sha256(tag.encodeToByteArray())
        val expected = ByteVector32(Crypto.sha256(hashedTag + hashedTag + x))
        assertEquals(expected, Crypto.taggedHash(x, tag))
    }

    @Test
    fun outputKeyFromKnownSharedSecret() {
        // From drongo testTweakTaprootMixedY: shared secret point, B_spend, and the resulting
        // P_k x-only key for k=0. Locks in ser_P(sharedSecret) || ser_32(k) and B_spend + t_k*G.
        val sharedSecret = PublicKey.fromHex("030e7f5ca4bf109fc35c8c2d878f756c891ac04c456cc5f0b05fcec4d3b2b1beb2")
        val spendPub = PrivateKey.fromHex("9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3").publicKey()
        val pk = SilentPayments.outputKey(spendPub, sharedSecret, 0)
        assertEquals("77cab7dd12b10259ee82c6ea4b509774e33e7078e7138f568092241bf26b99f1", pk.value.toHex())
    }
}
