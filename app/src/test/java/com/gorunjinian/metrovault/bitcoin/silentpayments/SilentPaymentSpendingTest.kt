package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.ByteVector32
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentSpending
import org.junit.Assert.assertEquals
import org.junit.Test

class SilentPaymentSpendingTest {
    private val bSpend = PrivateKey.fromHex("9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3")
    private val tweak = ByteVector32("0202020202020202020202020202020202020202020202020202020202020202")

    @Test
    fun derivesSpendingKeyAsScalarSum() {
        val d = SilentPaymentSpending.deriveSpendingPrivateKey(bSpend, tweak)
        // d == (b_spend + tweak) mod n
        assertEquals((bSpend + PrivateKey(tweak)).value.toHex(), d.value.toHex())
    }

    @Test
    fun derivedKeyMatchesOutputKey() {
        // d·G must equal B_spend + t_k·G = P_k (the taproot output key the input commits to).
        val d = SilentPaymentSpending.deriveSpendingPrivateKey(bSpend, tweak)
        val expectedPk = bSpend.publicKey() + PrivateKey(tweak).publicKey()
        assertEquals(expectedPk.toHex(), d.publicKey().toHex())
    }
}
