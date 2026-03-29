package com.gorunjinian.metrovault.qr

import com.gorunjinian.bbqr.ContinuousJoinResult
import com.gorunjinian.bbqr.ContinuousJoiner
import com.gorunjinian.bbqr.Encoding
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.Joined
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bbqr.Version
import com.gorunjinian.bcur.ResultType
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.URDecoder
import com.gorunjinian.bcur.UREncoder
import org.junit.Assert.*
import org.junit.Test
import java.util.Base64

/**
 * Integration tests that replicate the exact encode→decode flows used by MetroVault's
 * PSBTQREncoder, TransactionQREncoder, ExportMultiSigScreen, AnimatedQRScanner, and PSBTDecoder.
 *
 * Uses java.util.Base64 instead of android.util.Base64 (functionally identical for NO_WRAP).
 */
class QREncodingFlowTest {

    companion object {
        val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        val samplePsbtBytes = PSBT_MAGIC + ByteArray(500) { (it % 256).toByte() }
        val largePsbtBytes = PSBT_MAGIC + ByteArray(3000) { (it % 256).toByte() }
        val sampleTxBytes = ByteArray(150) { (it * 7 % 256).toByte() }
        val descriptorString = "wsh(sortedmulti(2,[abcdef01/48h/0h/0h/2h]xpub6ExampleKey1/0/*,[12345678/48h/0h/0h/2h]xpub6ExampleKey2/0/*))"

        // Mirrors DensitySettings from QRCodeModels.kt
        const val UR_MAX_FRAG_MEDIUM = 250
        const val UR_MIN_FRAG_MEDIUM = 50
    }

    // ==================== PSBT BC-UR v1 (ur:crypto-psbt/) ====================

    @Test
    fun testPsbtBCURv1SinglePartFlow() {
        // Mirrors PSBTQREncoder.generateURPsbtQRv1 with small PSBT
        val psbtBase64 = Base64.getEncoder().encodeToString(samplePsbtBytes)
        val psbtBytes = Base64.getDecoder().decode(psbtBase64)

        val ur = UR.fromBytes(psbtBytes, "crypto-psbt")
        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)

        if (encoder.isSinglePart()) {
            val urString = encoder.nextPart()
            assertTrue("Should start with ur:crypto-psbt/", urString.startsWith("ur:crypto-psbt/"))

            // Decode back (mirrors PSBTDecoder.decodeURPsbt)
            val parsed = UR.parse(urString)
            val decoded = parsed.toBytes()
            assertTrue("Decoded should start with PSBT magic", decoded.take(5).toByteArray().contentEquals(PSBT_MAGIC))
            assertArrayEquals(samplePsbtBytes, decoded)

            // Verify Base64 roundtrip
            val reEncoded = Base64.getEncoder().encodeToString(decoded)
            assertEquals(psbtBase64, reEncoded)
        }
    }

    @Test
    fun testPsbtBCURv1MultiPartFlow() {
        // Mirrors PSBTQREncoder.generateURPsbtQRv1 with large PSBT
        val ur = UR.fromBytes(largePsbtBytes, "crypto-psbt")
        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)
        assertFalse("Large PSBT should be multi-part", encoder.isSinglePart())

        // Generate all parts (mirrors the repeat(seqLen) loop)
        val parts = mutableListOf<String>()
        repeat(encoder.seqLen) {
            parts.add(encoder.nextPart())
        }

        // Decode via URDecoder (mirrors AnimatedQRScanner UR path)
        val decoder = URDecoder()
        val maxIterations = encoder.seqLen * 3
        var idx = 0
        while (decoder.result == null && idx < maxIterations) {
            decoder.receivePart(parts[idx % parts.size])
            idx++
        }

        val result = decoder.result
        assertNotNull("Decoder should complete", result)
        assertEquals(ResultType.SUCCESS, result!!.type)
        assertArrayEquals(largePsbtBytes, result.ur!!.toBytes())
    }

    // ==================== PSBT BC-UR v2 (ur:psbt/) ====================

    @Test
    fun testPsbtBCURv2SinglePartFlow() {
        val ur = UR.fromBytes(samplePsbtBytes, "psbt")
        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)

        if (encoder.isSinglePart()) {
            val urString = encoder.nextPart()
            assertTrue("Should start with ur:psbt/", urString.startsWith("ur:psbt/"))

            val parsed = UR.parse(urString)
            assertArrayEquals(samplePsbtBytes, parsed.toBytes())
        }
    }

    @Test
    fun testPsbtBCURv2MultiPartFlow() {
        val ur = UR.fromBytes(largePsbtBytes, "psbt")
        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)

        val decoder = URDecoder()
        val maxIterations = encoder.seqLen * 3
        var iterations = 0
        while (decoder.result == null && iterations < maxIterations) {
            decoder.receivePart(encoder.nextPart())
            iterations++
        }

        val result = decoder.result
        assertNotNull(result)
        assertEquals(ResultType.SUCCESS, result!!.type)
        assertArrayEquals(largePsbtBytes, result.ur!!.toBytes())
    }

    // ==================== PSBT BBQr ====================

    @Test
    fun testPsbtBBQrFlow() {
        // Mirrors PSBTQREncoder.generateBBQrPSBT → MEDIUM density
        val options = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V20)
        val splitResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, options)

        // Batch decode (mirrors PSBTDecoder.decodeBBQrFrames)
        val joined = Joined.fromParts(splitResult.parts)
        assertTrue("Decoded should start with PSBT magic",
            joined.data.take(5).toByteArray().contentEquals(PSBT_MAGIC))
        assertArrayEquals(largePsbtBytes, joined.data)
    }

    @Test
    fun testPsbtBBQrFlowWithContinuousJoiner() {
        // Mirrors AnimatedQRScanner BBQr path with ContinuousJoiner
        val options = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V20)
        val splitResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, options)
        assertTrue("Should have multiple parts", splitResult.parts.size > 1)

        val joiner = ContinuousJoiner()
        var completeData: ByteArray? = null

        for (part in splitResult.parts) {
            val result = joiner.addPart(part)
            if (result is ContinuousJoinResult.Complete) {
                completeData = result.joined.data
                break
            }
        }

        assertNotNull("Joiner should complete", completeData)
        assertArrayEquals(largePsbtBytes, completeData)

        // Verify Base64 encoding (mirrors AnimatedQRScanner.getResult)
        val base64Result = Base64.getEncoder().encodeToString(completeData)
        val roundtripped = Base64.getDecoder().decode(base64Result)
        assertArrayEquals(largePsbtBytes, roundtripped)
    }

    // ==================== Transaction flows ====================

    @Test
    fun testTransactionBBQrFlow() {
        // Mirrors TransactionQREncoder.generateBBQrTransaction
        val options = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V20)
        val splitResult = SplitResult.fromData(sampleTxBytes, FileType.Transaction, options)

        // Verify file type header
        for (part in splitResult.parts) {
            assertEquals("Transaction type should be T", 'T', part[3])
        }

        val joined = Joined.fromParts(splitResult.parts)
        assertArrayEquals(sampleTxBytes, joined.data)
        assertEquals(FileType.Transaction, joined.fileType)
    }

    @Test
    fun testTransactionBCURFlow() {
        // Mirrors TransactionQREncoder.generateURBytesQR
        val ur = UR.fromBytes(sampleTxBytes)
        assertEquals("bytes", ur.type)

        val encoder = UREncoder(ur, UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM, 0)
        val decoder = URDecoder()

        val maxIterations = encoder.seqLen * 3
        var iterations = 0
        while (decoder.result == null && iterations < maxIterations) {
            decoder.receivePart(encoder.nextPart())
            iterations++
        }

        assertNotNull(decoder.result)
        assertEquals(ResultType.SUCCESS, decoder.result!!.type)
        assertArrayEquals(sampleTxBytes, decoder.result!!.ur!!.toBytes())
    }

    // ==================== Descriptor flows ====================

    @Test
    fun testDescriptorBBQrFlow() {
        // Mirrors ExportMultiSigScreen.generateDescriptorBBQr
        val descriptorBytes = descriptorString.toByteArray(Charsets.UTF_8)
        val options = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V12)
        val splitResult = SplitResult.fromData(descriptorBytes, FileType.UnicodeText, options)

        val joined = Joined.fromParts(splitResult.parts)
        val decoded = String(joined.data, Charsets.UTF_8)
        assertEquals(descriptorString, decoded)
    }

    @Test
    fun testDescriptorBCURv1Flow() {
        // Mirrors ExportMultiSigScreen.generateDescriptorURv1
        val descriptorBytes = descriptorString.toByteArray(Charsets.UTF_8)
        val ur = UR.fromBytes(descriptorBytes)

        val encoder = UREncoder(ur, 250, 50, 0)
        val decoder = URDecoder()

        val maxIterations = encoder.seqLen * 3
        var iterations = 0
        while (decoder.result == null && iterations < maxIterations) {
            decoder.receivePart(encoder.nextPart())
            iterations++
        }

        assertNotNull(decoder.result)
        val decodedBytes = decoder.result!!.ur!!.toBytes()
        val decoded = String(decodedBytes, Charsets.UTF_8)
        assertEquals(descriptorString, decoded)
    }

    // ==================== PSBTDecoder logic verification ====================

    @Test
    fun testPsbtMagicBytesValidationAfterDecode() {
        // Simulates PSBTDecoder.decodeURPsbt validation logic
        val ur = UR.fromBytes(samplePsbtBytes, "crypto-psbt")
        val encoded = ur.encode()
        val parsed = UR.parse(encoded)
        val decoded = parsed.toBytes()

        // This is exactly what PSBTDecoder checks
        assertTrue("Decoded bytes should be >= 5", decoded.size >= 5)
        assertTrue("First 5 bytes should be PSBT magic",
            decoded.take(5).toByteArray().contentEquals(PSBT_MAGIC))
    }

    @Test
    fun testBBQrHeaderParsingMatchesPSBTDecoder() {
        // Simulates PSBTDecoder.parseBBQrFrame header parsing
        val splitResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt)

        for ((index, part) in splitResult.parts.withIndex()) {
            assertTrue("Part should start with B\$", part.startsWith("B$"))
            assertTrue("Part should be at least 8 chars", part.length >= 8)

            // Parse header (same logic as PSBTDecoder.parseBBQrFrame)
            val totalBase36 = part.substring(4, 6)
            val partBase36 = part.substring(6, 8)
            val total = totalBase36.toInt(36)
            val partNum = partBase36.toInt(36)

            assertEquals("Total should match parts count", splitResult.parts.size, total)
            assertEquals("Part index should match", index, partNum)
            assertTrue("Part number should be in range", partNum in 0 until total)
        }
    }
}
