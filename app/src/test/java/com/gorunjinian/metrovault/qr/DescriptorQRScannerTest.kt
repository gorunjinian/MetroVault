package com.gorunjinian.metrovault.qr

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.Cbor
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.metrovault.core.qr.DescriptorQRScanner
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tests for the descriptor QR transport layer: UR and BBQr payloads must unwrap to
 * the original text, and UR:OUTPUT-DESCRIPTOR must surface its embedded wallet name.
 */
class DescriptorQRScannerTest {

    // Content validity is the parser's concern, not the scanner's — placeholders suffice.
    private val descriptor =
        "wsh(sortedmulti(2,[aaaaaaaa/48'/0'/0'/2']xpubPlaceholderKey1/<0;1>/*,[bbbbbbbb/48'/0'/0'/2']xpubPlaceholderKey2/<0;1>/*))"

    private fun outputDescriptorUr(source: String, name: String? = null): String {
        val cbor = UROutputDescriptor(source = source, name = name).toCbor().encode()
        return UREncoder.encode(UR("output-descriptor", cbor))
    }

    private fun scan(vararg frames: String): DescriptorQRScanner.ScanResult? {
        val scanner = DescriptorQRScanner()
        frames.forEach { scanner.processFrame(it) }
        assertTrue("Scanner should be complete after ${frames.size} frame(s)", scanner.isComplete())
        return scanner.getResult()
    }

    @Test
    fun outputDescriptorUrCarriesWalletName() {
        val result = scan(outputDescriptorUr(descriptor, name = "Family Vault"))

        assertNotNull(result)
        assertEquals(descriptor, result!!.content)
        assertEquals("Family Vault", result.walletName)
    }

    @Test
    fun outputDescriptorUrWithoutNameYieldsNullName() {
        val result = scan(outputDescriptorUr(descriptor))

        assertNotNull(result)
        assertEquals(descriptor, result!!.content)
        assertNull(result.walletName)
    }

    @Test
    fun outputDescriptorUrBlankNameYieldsNullName() {
        val result = scan(outputDescriptorUr(descriptor, name = "   "))

        assertNotNull(result)
        assertNull(result!!.walletName)
    }

    @Test
    fun plainDescriptorFrameHasNoName() {
        val result = scan(descriptor)

        assertNotNull(result)
        assertEquals(descriptor, result!!.content)
        assertNull(result.walletName)
    }

    /** Setup-file text over UR:BYTES — how BSMS and ColdCard setups arrive via UR. */
    @Test
    fun urBytesUnwrapsToOriginalText() {
        val setupFile = "Name: My_Wallet\nPolicy: 2 of 2\nFormat: P2WSH\n" +
            "Derivation: m/48'/0'/0'/2'\naaaaaaaa: xpubKey1\nbbbbbbbb: xpubKey2"
        val ur = UREncoder.encode(UR("bytes", Cbor.encodeByteString(setupFile.toByteArray(Charsets.UTF_8))))

        val result = scan(ur)

        assertNotNull(result)
        assertEquals(setupFile, result!!.content)
        assertNull(result.walletName)
    }

    /** Setup-file text over BBQr type U with default (zlib) encoding — the ColdCard wrapping. */
    @Test
    fun bbqrUnicodeUnwrapsToOriginalText() {
        val setupFile = "Name: BBQr_Wallet\nPolicy: 2 of 3\nFormat: P2WSH\n" +
            "Derivation: m/48'/0'/0'/2'\naaaaaaaa: xpubKey1\nbbbbbbbb: xpubKey2\ncccccccc: xpubKey3"
        val split = SplitResult.fromData(setupFile.toByteArray(Charsets.UTF_8), FileType.UnicodeText)

        val result = scan(*split.parts.toTypedArray())

        assertNotNull(result)
        assertEquals(setupFile, result!!.content)
        assertNull(result.walletName)
    }
}
