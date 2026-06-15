package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.lib.bitcoin.Bech32
import com.gorunjinian.metrovault.lib.bitcoin.Int5
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.ScanAddressCodec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class ScanAddressCodecTest {
    private val scanPriv = PrivateKey.fromHex("0f694e068028a717f8af6b9411f9a133dd3565258714cc226594b34db90c1f2c")
    private val spendPub = PrivateKey.fromHex("9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3").publicKey()

    @Test
    fun mainnetRoundTrip() {
        val encoded = ScanAddressCodec.encode(scanPriv, spendPub, isTestnet = false)
        assertTrue("expected spscan1q… prefix, got $encoded", encoded.startsWith("spscan1q"))
        val (scan, spend) = ScanAddressCodec.decode(encoded)
        assertEquals(scanPriv.value.toHex(), scan.value.toHex())
        assertEquals(spendPub.toHex(), spend.toHex())
    }

    @Test
    fun testnetRoundTrip() {
        val encoded = ScanAddressCodec.encode(scanPriv, spendPub, isTestnet = true)
        assertTrue("expected tspscan1q… prefix, got $encoded", encoded.startsWith("tspscan1q"))
        val (scan, spend) = ScanAddressCodec.decode(encoded)
        assertEquals(scanPriv.value.toHex(), scan.value.toHex())
        assertEquals(spendPub.toHex(), spend.toHex())
    }

    @Test
    fun rejectsSpendKeyExport() {
        // A spspend… string (scan priv ‖ spend priv, 64 bytes) must be refused — importing a spend
        // key would break the cold-signer model.
        val spendPriv = PrivateKey.fromHex("9d6ad855ce3417ef84e836892e5a56392bfba05fa5d97ccea30e266f540e08b3")
        val payload = scanPriv.value.toByteArray() + spendPriv.value.toByteArray() // 64 bytes
        val spspend = Bech32.encode(
            ScanAddressCodec.HRP_SPEND_MAINNET,
            arrayOf<Int5>(0) + Bech32.eight2five(payload),
            Bech32.Encoding.Bech32m
        )
        val e = assertThrows(IllegalArgumentException::class.java) { ScanAddressCodec.decode(spspend) }
        assertTrue(e.message!!.contains("spend key"))
    }

    @Test
    fun rejectsWrongPayloadLength() {
        val bad = Bech32.encode(
            ScanAddressCodec.HRP_SCAN_MAINNET,
            arrayOf<Int5>(0) + Bech32.eight2five(ByteArray(40)),
            Bech32.Encoding.Bech32m
        )
        assertThrows(IllegalArgumentException::class.java) { ScanAddressCodec.decode(bad) }
    }

    @Test
    fun rejectsCorruptedChecksum() {
        val encoded = ScanAddressCodec.encode(scanPriv, spendPub, isTestnet = false)
        val corrupted = encoded.dropLast(1) + if (encoded.last() == 'q') 'p' else 'q'
        assertThrows(IllegalArgumentException::class.java) { ScanAddressCodec.decode(corrupted) }
    }
}
