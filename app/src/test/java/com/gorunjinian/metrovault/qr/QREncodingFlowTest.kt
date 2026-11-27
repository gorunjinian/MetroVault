package com.gorunjinian.metrovault.qr

import com.gorunjinian.bbqr.Encoding
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bbqr.Version
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.metrovault.core.qr.AssembledQR
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler.FrameResult
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Round trips from the encodings MetroVault's own encoders produce (PSBTQREncoder,
 * TransactionQREncoder, DescriptorQREncoder) back through [QRFrameAssembler], the joiner every
 * scanning screen uses.
 *
 * The encoders draw bitmaps and can't run here, so each test repeats their encoding step.
 */
class QREncodingFlowTest {

    companion object {
        private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        private val smallPsbtBytes = PSBT_MAGIC + ByteArray(40) { it.toByte() }

        /**
         * High-entropy body, because the BBQr flows below compress before splitting. A real PSBT
         * is mostly signatures, pubkeys and txids and so barely deflates; a 0..255 byte ramp (the
         * previous fixture) deflates to a few dozen bytes and fits in a single BBQr frame, which
         * silently defeated the multi-part assertions. Seeded for determinism — java.util.Random's
         * algorithm is specified, so the bytes are identical on every JVM.
         */
        private val largePsbtBytes = PSBT_MAGIC + ByteArray(3000).also { java.util.Random(20260811L).nextBytes(it) }
        private val sampleTxBytes = ByteArray(150) { (it * 7 % 256).toByte() }
        private const val DESCRIPTOR =
            "wsh(sortedmulti(2,[abcdef01/48h/0h/0h/2h]xpub6ExampleKey1/0/*,[12345678/48h/0h/0h/2h]xpub6ExampleKey2/0/*))"

        // Mirrors DensitySettings from QRCodeModels.kt
        private const val UR_MAX_FRAG_MEDIUM = 250
        private const val UR_MIN_FRAG_MEDIUM = 50
    }

    /** Join [frames] as a camera would see them; fails if they never complete. */
    private fun assemble(frames: Iterable<String>): AssembledQR {
        val assembler = QRFrameAssembler()
        for (frame in frames) {
            val result = assembler.receive(frame)
            if (result is FrameResult.Complete) return result.payload
            assertTrue("unexpected failure: $result", result !is FrameResult.Failed)
        }
        throw AssertionError("the frames never completed")
    }

    /** The frames the encoders show for a UR: the first [UREncoder.seqLen] parts, on a loop. */
    private fun urFrames(ur: UR): List<String> {
        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)
        return List(encoder.seqLen) { encoder.nextPart() }
    }

    private fun bbqrFrames(data: ByteArray, fileType: FileType, maxVersion: Version): List<String> =
        SplitResult.fromData(data, fileType, SplitOptions(encoding = Encoding.Zlib, maxVersion = maxVersion)).parts

    private fun assertUr(type: String, data: ByteArray, isMultipart: Boolean) {
        val frames = urFrames(UR.fromBytes(data, type))
        assertEquals("frame count", isMultipart, frames.size > 1)
        assertTrue(frames.all { it.startsWith("ur:$type/") })

        val ur = assemble(frames + frames) as AssembledQR.Ur
        assertEquals(type, ur.ur.type)
        assertArrayEquals(data, ur.ur.toBytes())
        assertEquals(isMultipart, ur.isMultipart)
    }

    // ==================== PSBT ====================

    @Test
    fun testPsbtBCURv1SinglePartFlow() = assertUr("crypto-psbt", smallPsbtBytes, isMultipart = false)

    @Test
    fun testPsbtBCURv1MultiPartFlow() = assertUr("crypto-psbt", largePsbtBytes, isMultipart = true)

    @Test
    fun testPsbtBCURv2SinglePartFlow() = assertUr("psbt", smallPsbtBytes, isMultipart = false)

    @Test
    fun testPsbtBCURv2MultiPartFlow() = assertUr("psbt", largePsbtBytes, isMultipart = true)

    @Test
    fun testPsbtBBQrFlow() {
        val frames = bbqrFrames(largePsbtBytes, FileType.Psbt, Version.V20)
        assertTrue("Should have multiple parts", frames.size > 1)

        val bbqr = assemble(frames) as AssembledQR.BBQr
        assertEquals(FileType.Psbt, bbqr.fileType)
        assertEquals(frames.size, bbqr.numParts)
        assertArrayEquals(largePsbtBytes, bbqr.data)
    }

    // ==================== Transactions ====================

    @Test
    fun testTransactionBBQrFlow() {
        val frames = bbqrFrames(sampleTxBytes, FileType.Transaction, Version.V20)
        frames.forEach { assertEquals("Transaction type should be T", 'T', it[3]) }

        val bbqr = assemble(frames) as AssembledQR.BBQr
        assertEquals(FileType.Transaction, bbqr.fileType)
        assertArrayEquals(sampleTxBytes, bbqr.data)
    }

    @Test
    fun testTransactionBCURFlow() = assertUr("bytes", sampleTxBytes, isMultipart = false)

    // ==================== Descriptors ====================

    @Test
    fun testDescriptorBBQrFlow() {
        val frames = bbqrFrames(DESCRIPTOR.toByteArray(Charsets.UTF_8), FileType.UnicodeText, Version.V12)

        val bbqr = assemble(frames) as AssembledQR.BBQr
        assertEquals(FileType.UnicodeText, bbqr.fileType)
        assertEquals(DESCRIPTOR, String(bbqr.data, Charsets.UTF_8))
    }

    @Test
    fun testDescriptorBCURv1Flow() {
        val ur = assemble(urFrames(UR.fromBytes(DESCRIPTOR.toByteArray(Charsets.UTF_8)))) as AssembledQR.Ur
        assertEquals("bytes", ur.ur.type)
        assertEquals(DESCRIPTOR, String(ur.ur.toBytes(), Charsets.UTF_8))
    }
}
