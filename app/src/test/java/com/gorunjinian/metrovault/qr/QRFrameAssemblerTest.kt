package com.gorunjinian.metrovault.qr

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bbqr.Version
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.metrovault.core.qr.AssembledQR
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler
import com.gorunjinian.metrovault.core.qr.QRFrameAssembler.FrameResult
import com.gorunjinian.metrovault.core.qr.QRTransport
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.util.Random

/**
 * The scan-anything transport layer: every transport joins to its exact payload, frames from
 * something else are ignored rather than restarting the scan, and conflicting frames fail.
 */
class QRFrameAssemblerTest {

    /** Incompressible (BBQr zlib-compresses) and large enough to need several frames everywhere. */
    private val payload = ByteArray(900).also { Random(42).nextBytes(it) }

    private fun AssembledQR.asText() = this as AssembledQR.Text
    private fun AssembledQR.asUr() = this as AssembledQR.Ur
    private fun AssembledQR.asBBQr() = this as AssembledQR.BBQr

    /** Feed frames until one completes; fails if none does. */
    private fun QRFrameAssembler.feedUntilComplete(frames: Sequence<String>, limit: Int = 500): AssembledQR {
        frames.take(limit).forEach { frame ->
            val result = receive(frame)
            if (result is FrameResult.Complete) return result.payload
            assertFalse("unexpected failure: $result", result is FrameResult.Failed)
        }
        throw AssertionError("no frame completed the scan within $limit frames")
    }

    private fun urFrames(type: String, data: ByteArray): Sequence<String> {
        val encoder = UREncoder(UR.fromBytes(data, type), maxFragmentLen = 120)
        return generateSequence { encoder.nextPart() }
    }

    private fun bbqrParts(fileType: FileType, data: ByteArray): List<String> =
        SplitResult.fromData(data, fileType, SplitOptions(maxVersion = Version.V05)).parts

    // ==================== Single frames ====================

    @Test
    fun plainTextCompletesOnFirstFrameKeepingRawBytes() {
        val raw = byteArrayOf(0x40, 0x12, 0x34)
        val result = QRFrameAssembler().receive("bc1qexample", raw)

        val text = (result as FrameResult.Complete).payload.asText()
        assertEquals("bc1qexample", text.text)
        assertSame(raw, text.rawBytes)
        assertEquals(QRTransport.SINGLE, text.transport)
    }

    @Test
    fun descriptorStartingWithLetterPIsNotMistakenForAnAnimation() {
        // The old PSBT joiner treated any text starting with "p" as a "p1/n" frame.
        val descriptor = "pkh([aaaaaaaa/44'/0'/0']xpubPlaceholder/0/*)"
        val result = QRFrameAssembler().receive(descriptor)

        assertEquals(descriptor, (result as FrameResult.Complete).payload.asText().text)
    }

    @Test
    fun framesAfterCompletionAreIgnoredUntilReset() {
        val assembler = QRFrameAssembler()
        assertTrue(assembler.receive("first") is FrameResult.Complete)
        assertEquals(FrameResult.Ignored, assembler.receive("second"))

        assembler.reset()
        assertEquals("second", (assembler.receive("second") as FrameResult.Complete).payload.asText().text)
    }

    // ==================== BC-UR ====================

    @Test
    fun singlePartUrOfAnyTypeCompletesImmediately() {
        val encoded = UREncoder.encode(UR.fromBytes(byteArrayOf(1, 2, 3), "bytes"))
        val assembler = QRFrameAssembler()

        val ur = (assembler.receive(encoded) as FrameResult.Complete).payload.asUr()
        assertEquals("bytes", ur.ur.type)
        assertArrayEquals(byteArrayOf(1, 2, 3), ur.ur.toBytes())
        assertFalse(ur.isMultipart)
        assertFalse(assembler.isAnimated)
    }

    @Test
    fun upperCaseUrIsAccepted() {
        // QR alphanumeric mode upper-cases UR strings.
        val encoded = UREncoder.encode(UR.fromBytes(byteArrayOf(9, 8, 7), "bytes")).uppercase()
        val result = QRFrameAssembler().receive(encoded)

        assertArrayEquals(byteArrayOf(9, 8, 7), (result as FrameResult.Complete).payload.asUr().ur.toBytes())
    }

    @Test
    fun fountainCodedUrJoinsToOriginalBytes() {
        val assembler = QRFrameAssembler()
        val ur = assembler.feedUntilComplete(urFrames("crypto-psbt", payload)).asUr()

        assertEquals("crypto-psbt", ur.ur.type)
        assertArrayEquals(payload, ur.ur.toBytes())
        assertTrue(ur.isMultipart)
        assertTrue(assembler.isAnimated)
        assertEquals(QRTransport.UR, assembler.detectedTransport)
    }

    @Test
    fun urProgressStaysBelowHundredUntilComplete() {
        val assembler = QRFrameAssembler()
        val frames = urFrames("bytes", payload).iterator()

        val first = assembler.receive(frames.next())
        assertTrue("first frame of many should be progress: $first", first is FrameResult.Progress)
        assertTrue((first as FrameResult.Progress).percent in 0..99)
        assertTrue(assembler.progressLabel.endsWith("% BC-UR"))
    }

    @Test
    fun framesOfAnotherUrTypeAreIgnoredMidScan() {
        val assembler = QRFrameAssembler()
        val frames = urFrames("crypto-psbt", payload).iterator()
        assertTrue(assembler.receive(frames.next()) is FrameResult.Progress)

        val foreign = urFrames("bytes", payload).first()
        assertEquals(FrameResult.Ignored, assembler.receive(foreign))
        assertEquals(FrameResult.Ignored, assembler.receive("bc1qnotpartofthisscan"))

        // The original scan still completes.
        var completed: AssembledQR? = null
        while (completed == null) {
            val result = assembler.receive(frames.next())
            if (result is FrameResult.Complete) completed = result.payload
        }
        assertArrayEquals(payload, completed.asUr().ur.toBytes())
    }

    @Test
    fun malformedFirstUrFrameDoesNotLockTheTransport() {
        val assembler = QRFrameAssembler()
        assertEquals(FrameResult.Ignored, assembler.receive("ur:bytes/not-bytewords!"))
        assertNull(assembler.detectedTransport)

        val result = assembler.receive("plain text")
        assertEquals("plain text", (result as FrameResult.Complete).payload.asText().text)
    }

    // ==================== BBQr ====================

    @Test
    fun multiPartBBQrJoinsToOriginalBytesInAnyOrder() {
        val parts = bbqrParts(FileType.Psbt, payload)
        assertTrue("test needs several parts, got ${parts.size}", parts.size > 2)

        val assembler = QRFrameAssembler()
        val shuffled = parts.reversed()
        shuffled.dropLast(1).forEach { assertTrue(assembler.receive(it) is FrameResult.Progress) }
        assertTrue(assembler.isAnimated)
        assertEquals("${parts.size - 1}/${parts.size} BBQr parts", assembler.progressLabel)

        val bbqr = (assembler.receive(shuffled.last()) as FrameResult.Complete).payload.asBBQr()
        assertEquals(FileType.Psbt, bbqr.fileType)
        assertArrayEquals(payload, bbqr.data)
        assertEquals(parts.size, bbqr.numParts)
    }

    @Test
    fun repeatedBBQrPartIsIgnored() {
        val parts = bbqrParts(FileType.Psbt, payload)
        val assembler = QRFrameAssembler()

        assertTrue(assembler.receive(parts[0]) is FrameResult.Progress)
        assertTrue(assembler.receive(parts[1]) is FrameResult.Progress)
        assertEquals(FrameResult.Ignored, assembler.receive(parts[1]))
    }

    @Test
    fun partsOfADifferentBBQrAreIgnored() {
        val assembler = QRFrameAssembler()
        assertTrue(assembler.receive(bbqrParts(FileType.Psbt, payload)[0]) is FrameResult.Progress)

        val other = bbqrParts(FileType.UnicodeText, "x".repeat(2000).toByteArray())
        assertEquals(FrameResult.Ignored, assembler.receive(other[0]))
    }

    @Test
    fun singlePartBBQrCompletesImmediately() {
        val parts = SplitResult.fromData("hello".toByteArray(), FileType.UnicodeText).parts
        assertEquals(1, parts.size)

        val bbqr = (QRFrameAssembler().receive(parts[0]) as FrameResult.Complete).payload.asBBQr()
        assertEquals(FileType.UnicodeText, bbqr.fileType)
        assertEquals("hello", String(bbqr.data))
    }

    @Test
    fun textStartingWithDollarHeaderButNotBBQrIsPlainText() {
        val result = QRFrameAssembler().receive("B\$ is not a BBQr header")
        assertEquals("B\$ is not a BBQr header", (result as FrameResult.Complete).payload.asText().text)
    }

    // ==================== p1/n animations ====================

    @Test
    fun simpleAnimationJoinsPartsInOrder() {
        val assembler = QRFrameAssembler()

        assertEquals(FrameResult.Progress(33), assembler.receive("p2/3 BBB"))
        assertEquals(FrameResult.Progress(66), assembler.receive("p3/3 CCC"))
        assertEquals(FrameResult.Ignored, assembler.receive("p3/3 CCC"))
        val text = (assembler.receive("p1/3 AAA") as FrameResult.Complete).payload.asText()

        assertEquals("AAABBBCCC", text.text)
        assertEquals(QRTransport.SIMPLE_MULTIPART, text.transport)
        assertNull(text.rawBytes)
    }

    @Test
    fun simpleFrameOfAnotherAnimationLengthIsIgnored() {
        val assembler = QRFrameAssembler()
        assembler.receive("p1/3 AAA")

        assertEquals(FrameResult.Ignored, assembler.receive("p1/4 ZZZ"))
        assertEquals(FrameResult.Ignored, assembler.receive("p5/3 ZZZ"))
    }

    @Test
    fun conflictingSimplePartFails() {
        val assembler = QRFrameAssembler()
        assembler.receive("p1/2 AAA")

        assertTrue(assembler.receive("p1/2 XXX") is FrameResult.Failed)
    }
}
