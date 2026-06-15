package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.SilentPaymentAddress
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Test

class SilentPaymentAddressTest {
    private val standardAddress =
        "sp1qqgste7k9hx0qftg6qmwlkqtwuy6cycyavzmzj85c6qdfhjdpdjtdgqjuexzk6murw56suy3e0rd2cgqvycxttddwsvgxe2usfpxumr70xc9pkqwv"
    private val standardScanPriv = "0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c"
    private val standardSpendPriv = "9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3"

    @Test
    fun decodesScanAndSpendKeys() {
        val sp = SilentPaymentAddress.decode(standardAddress)
        assertEquals(0, sp.version)
        assertEquals(PrivateKey.fromHex(standardScanPriv).publicKey().toHex(), sp.scanPubKey.toHex())
        assertEquals(PrivateKey.fromHex(standardSpendPriv).publicKey().toHex(), sp.spendPubKey.toHex())
    }

    @Test
    fun mainnetEncodeRoundTrip() {
        val sp = SilentPaymentAddress.decode(standardAddress)
        assertEquals(standardAddress, sp.encode(isTestnet = false))
    }

    @Test
    fun testnetEncodeRoundTrip() {
        val sp = SilentPaymentAddress.decode(standardAddress)
        val testnet = sp.encode(isTestnet = true)
        assertEquals("tsp", testnet.substringBefore('1'))
        val decoded = SilentPaymentAddress.decode(testnet)
        assertEquals(sp.scanPubKey.toHex(), decoded.scanPubKey.toHex())
        assertEquals(sp.spendPubKey.toHex(), decoded.spendPubKey.toHex())
    }

    @Test
    fun rejectsCorruptedChecksum() {
        // Flip the final character of a known-good address: the bech32m checksum no longer holds.
        val corrupted = standardAddress.dropLast(1) + if (standardAddress.last() == 'v') 'p' else 'v'
        assertThrows(IllegalArgumentException::class.java) { SilentPaymentAddress.decode(corrupted) }
    }

    @Test
    fun rejectsWrongHrp() {
        // Swap the HRP of a known-good address; the checksum is HRP-dependent so this is rejected.
        val wrongHrp = "tb1" + standardAddress.substringAfter('1')
        assertThrows(IllegalArgumentException::class.java) { SilentPaymentAddress.decode(wrongHrp) }
    }
}
