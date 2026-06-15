package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPayments.EligibleInputKey
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SilentPaymentSafetyTest {
    @Test
    fun summedPrivateKeyOfZeroIsRejected() {
        val k = PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1")
        // a + (-a) == 0 mod n must fail.
        assertThrows(IllegalArgumentException::class.java) {
            SilentPayments.summedPrivateKey(listOf(EligibleInputKey(k, false), EligibleInputKey(-k, false)))
        }
    }

    @Test
    fun zeroIntermediateSumWithNonZeroFinalIsAccepted() {
        // BIP-352 v1.1.1 edge case: k + (-k) + m. Intermediate sum is zero, final sum is m.
        val k = PrivateKey.fromHex("eadc78165ff1f8ea94ad7cfdc54990738a4c53f6e0507b42154201b8e5dff3b1")
        val m = PrivateKey.fromHex("93f5ed907ad5b2bdbbdcb5d9116ebc0a4e1f92f910d5260237fa45a9408aad16")
        val summed = SilentPayments.summedPrivateKey(
            listOf(EligibleInputKey(k, false), EligibleInputKey(-k, false), EligibleInputKey(m, false))
        )
        assertEquals(m.value.toHex(), summed.value.toHex())
    }

    @Test
    fun emptyEligibleInputsIsRejected() {
        assertThrows(IllegalArgumentException::class.java) {
            SilentPayments.summedPrivateKey(emptyList())
        }
    }
}
