package com.gorunjinian.metrovault.qr

import com.gorunjinian.bcur.ResultType
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.URDecoder
import com.gorunjinian.bcur.UREncoder
import org.junit.Assert.*
import org.junit.Test

class BCURLibraryTest {

    companion object {
        val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        val smallData = ByteArray(50) { (it + 1).toByte() }
        val samplePsbtBytes = PSBT_MAGIC + ByteArray(200) { it.toByte() }
        val largeData = ByteArray(5000) { (it % 256).toByte() }
    }

    @Test
    fun testFromBytesDefaultType() {
        val ur = UR.fromBytes(smallData)
        assertEquals("bytes", ur.type)
    }

    @Test
    fun testFromBytesWithCryptoPsbtType() {
        val ur = UR.fromBytes(samplePsbtBytes, "crypto-psbt")
        assertEquals("crypto-psbt", ur.type)
        assertTrue(ur.cborData.isNotEmpty())
    }

    @Test
    fun testFromBytesWithPsbtType() {
        val ur = UR.fromBytes(samplePsbtBytes, "psbt")
        assertEquals("psbt", ur.type)
    }

    @Test
    fun testFromBytesToBytesRoundtrip() {
        val ur = UR.fromBytes(samplePsbtBytes, "crypto-psbt")
        val decoded = ur.toBytes()
        assertArrayEquals(samplePsbtBytes, decoded)
    }

    @Test
    fun testSinglePartEncodeDecodeRoundtrip() {
        val ur = UR.fromBytes(smallData)
        val encoded = ur.encode()
        val parsed = UR.parse(encoded)
        assertArrayEquals(smallData, parsed.toBytes())
    }

    @Test
    fun testSinglePartURStringFormat() {
        val ur = UR.fromBytes(smallData)
        val encoded = ur.encode()
        assertTrue("UR string should start with ur:bytes/, got: $encoded", encoded.startsWith("ur:bytes/"))
    }

    @Test
    fun testSinglePartCryptoPsbtFormat() {
        val small = PSBT_MAGIC + ByteArray(20) { it.toByte() }
        val ur = UR.fromBytes(small, "crypto-psbt")
        val encoded = ur.encode()
        assertTrue("Should start with ur:crypto-psbt/, got: $encoded", encoded.startsWith("ur:crypto-psbt/"))
    }

    @Test
    fun testUREncoderSinglePart() {
        val ur = UR.fromBytes(smallData)
        val encoder = UREncoder(ur, 1000, 10, 0)
        assertTrue(encoder.isSinglePart())
        val part = encoder.nextPart()
        assertTrue(part.startsWith("ur:bytes/"))
    }

    @Test
    fun testUREncoderMultiPart() {
        val data = ByteArray(256) { (it % 256).toByte() }
        val ur = UR.fromBytes(data)
        val encoder = UREncoder(ur, 30, 10, 0)
        assertFalse(encoder.isSinglePart())
        assertTrue("seqLen should be > 1, got ${encoder.seqLen}", encoder.seqLen > 1)

        val parts = mutableListOf<String>()
        repeat(encoder.seqLen) {
            parts.add(encoder.nextPart())
        }
        // All parts should have type and sequence info
        for (part in parts) {
            assertTrue("Multi-part should start with ur:bytes/, got: $part", part.startsWith("ur:bytes/"))
            assertTrue("Multi-part should contain seq-len separator", part.contains("/"))
        }
    }

    @Test
    fun testURDecoderSinglePart() {
        val ur = UR.fromBytes(smallData)
        val encoded = ur.encode()

        val decoder = URDecoder()
        decoder.receivePart(encoded)
        val result = decoder.result
        assertNotNull(result)
        assertEquals(ResultType.SUCCESS, result!!.type)
        assertArrayEquals(smallData, result.ur!!.toBytes())
    }

    @Test
    fun testURDecoderMultiPartFountainCodes() {
        val ur = UR.fromBytes(largeData)
        val encoder = UREncoder(ur, 100, 10, 0)
        val decoder = URDecoder()

        val maxIterations = encoder.seqLen * 3
        var iterations = 0
        while (decoder.result == null && iterations < maxIterations) {
            decoder.receivePart(encoder.nextPart())
            iterations++
        }

        val result = decoder.result
        assertNotNull("Decoder should complete within ${maxIterations} iterations", result)
        assertEquals(ResultType.SUCCESS, result!!.type)
        assertArrayEquals(largeData, result.ur!!.toBytes())
    }

    @Test
    fun testURDecoderProgressTracking() {
        val ur = UR.fromBytes(largeData)
        val encoder = UREncoder(ur, 100, 10, 0)
        val decoder = URDecoder()

        // Feed first part
        decoder.receivePart(encoder.nextPart())
        assertTrue("Expected part count should be > 0", decoder.expectedPartCount > 0)

        // Feed a few more parts
        repeat(3) { decoder.receivePart(encoder.nextPart()) }
        assertTrue("Progress should be > 0 after parts", decoder.estimatedPercentComplete > 0.0)
        assertTrue("Processed count should be > 0", decoder.processedPartsCount > 0)
    }

    @Test
    fun testURDecoderDuplicatePartHandling() {
        val ur = UR.fromBytes(largeData)
        val encoder = UREncoder(ur, 100, 10, 0)
        val decoder = URDecoder()

        val firstPart = encoder.nextPart()
        decoder.receivePart(firstPart)
        val countAfterFirst = decoder.processedPartsCount

        // Feed same part again
        decoder.receivePart(firstPart)
        assertEquals("Duplicate part should not increment count", countAfterFirst, decoder.processedPartsCount)
    }

    @Test(expected = UR.InvalidURException::class)
    fun testParseInvalidURThrows() {
        UR.parse("not-a-ur-string")
    }
}
