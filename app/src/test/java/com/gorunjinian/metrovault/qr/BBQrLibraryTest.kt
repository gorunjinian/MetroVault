package com.gorunjinian.metrovault.qr

import com.gorunjinian.bbqr.ContinuousJoinResult
import com.gorunjinian.bbqr.ContinuousJoiner
import com.gorunjinian.bbqr.Encoding
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.Joined
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bbqr.Version
import com.gorunjinian.bbqr.SplitError
import org.junit.Assert.*
import org.junit.Test

class BBQrLibraryTest {

    companion object {
        val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())
        val samplePsbtBytes = PSBT_MAGIC + ByteArray(200) { it.toByte() }
        val largePsbtBytes = PSBT_MAGIC + ByteArray(5000) { (it % 256).toByte() }
        val sampleTxBytes = ByteArray(250) { (it * 3 % 256).toByte() }
        val descriptorText = "wsh(sortedmulti(2,[abcdef01/48h/0h/0h/2h]xpub6ExampleKey1/0/*,[12345678/48h/0h/0h/2h]xpub6ExampleKey2/0/*))"
    }

    @Test
    fun testSplitResultPsbtDefaultOptions() {
        val result = SplitResult.fromData(samplePsbtBytes, FileType.Psbt)
        assertTrue("Should have at least one part", result.parts.isNotEmpty())
        for (part in result.parts) {
            assertTrue("Every part should start with B\$, got: ${part.take(10)}", part.startsWith("B$"))
        }
    }

    @Test
    fun testSplitResultHeaderFormatPsbt() {
        val result = SplitResult.fromData(samplePsbtBytes, FileType.Psbt)
        val firstPart = result.parts[0]
        assertEquals("File type character should be P (PSBT)", 'P', firstPart[3])
    }

    @Test
    fun testSplitResultTransactionType() {
        val result = SplitResult.fromData(sampleTxBytes, FileType.Transaction)
        val firstPart = result.parts[0]
        assertEquals("File type character should be T (Transaction)", 'T', firstPart[3])
    }

    @Test
    fun testSplitResultUnicodeTextType() {
        val result = SplitResult.fromData(descriptorText.toByteArray(Charsets.UTF_8), FileType.UnicodeText)
        val firstPart = result.parts[0]
        assertEquals("File type character should be U (UnicodeText)", 'U', firstPart[3])
    }

    @Test
    fun testJoinedFromPartsRoundtrip() {
        val splitResult = SplitResult.fromData(samplePsbtBytes, FileType.Psbt)
        val joined = Joined.fromParts(splitResult.parts)
        assertArrayEquals(samplePsbtBytes, joined.data)
        assertEquals(FileType.Psbt, joined.fileType)
    }

    @Test
    fun testJoinedPreservesEncoding() {
        val options = SplitOptions(encoding = Encoding.Hex)
        val splitResult = SplitResult.fromData(samplePsbtBytes, FileType.Psbt, options)
        val joined = Joined.fromParts(splitResult.parts)
        assertEquals(Encoding.Hex, joined.encoding)
        assertArrayEquals(samplePsbtBytes, joined.data)
    }

    @Test
    fun testSplitWithZlibEncoding() {
        // Use highly repetitive data that compresses well
        val repetitiveData = PSBT_MAGIC + ByteArray(1000) { 0x41 }
        val options = SplitOptions(encoding = Encoding.Zlib)
        val splitResult = SplitResult.fromData(repetitiveData, FileType.Psbt, options)
        val joined = Joined.fromParts(splitResult.parts)
        assertArrayEquals(repetitiveData, joined.data)
    }

    @Test
    fun testSplitWithBase32Encoding() {
        val options = SplitOptions(encoding = Encoding.Base32)
        val splitResult = SplitResult.fromData(samplePsbtBytes, FileType.Psbt, options)
        assertEquals(Encoding.Base32, splitResult.encoding)
        val joined = Joined.fromParts(splitResult.parts)
        assertArrayEquals(samplePsbtBytes, joined.data)
    }

    @Test
    fun testSplitWithVersionConstraint() {
        val options = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V12)
        val splitResult = SplitResult.fromData(samplePsbtBytes, FileType.Psbt, options)
        assertTrue(
            "Version should be <= V12, got ${splitResult.version}",
            splitResult.version.ordinal <= Version.V12.ordinal
        )
    }

    @Test
    fun testDensityPresetsRoundtrip() {
        // LOW density
        val lowOptions = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V12)
        val lowResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, lowOptions)
        val lowJoined = Joined.fromParts(lowResult.parts)
        assertArrayEquals("LOW density roundtrip failed", largePsbtBytes, lowJoined.data)

        // MEDIUM density
        val medOptions = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V20)
        val medResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, medOptions)
        val medJoined = Joined.fromParts(medResult.parts)
        assertArrayEquals("MEDIUM density roundtrip failed", largePsbtBytes, medJoined.data)

        // HIGH density
        val highOptions = SplitOptions(encoding = Encoding.Zlib, maxVersion = Version.V27)
        val highResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, highOptions)
        val highJoined = Joined.fromParts(highResult.parts)
        assertArrayEquals("HIGH density roundtrip failed", largePsbtBytes, highJoined.data)

        // Lower density = more parts
        assertTrue(
            "LOW should have more parts than HIGH: ${lowResult.parts.size} vs ${highResult.parts.size}",
            lowResult.parts.size >= highResult.parts.size
        )
    }

    @Test
    fun testContinuousJoinerSinglePart() {
        val options = SplitOptions(maxVersion = Version.V40)
        val splitResult = SplitResult.fromData(samplePsbtBytes, FileType.Psbt, options)
        if (splitResult.parts.size != 1) return // skip if data too large for single part at V40

        val joiner = ContinuousJoiner()
        val result = joiner.addPart(splitResult.parts[0])
        assertTrue("Single part should complete immediately", result is ContinuousJoinResult.Complete)
        val complete = result as ContinuousJoinResult.Complete
        assertArrayEquals(samplePsbtBytes, complete.joined.data)
    }

    @Test
    fun testContinuousJoinerMultiPartInOrder() {
        val options = SplitOptions(encoding = Encoding.Base32, maxVersion = Version.V05)
        val splitResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, options)
        assertTrue("Should have multiple parts, got ${splitResult.parts.size}", splitResult.parts.size > 1)

        val joiner = ContinuousJoiner()
        for (i in splitResult.parts.indices) {
            val result = joiner.addPart(splitResult.parts[i])
            if (i < splitResult.parts.size - 1) {
                assertTrue("Part $i should be InProgress", result is ContinuousJoinResult.InProgress)
            } else {
                assertTrue("Last part should Complete", result is ContinuousJoinResult.Complete)
                val complete = result as ContinuousJoinResult.Complete
                assertArrayEquals(largePsbtBytes, complete.joined.data)
            }
        }
    }

    @Test
    fun testContinuousJoinerMultiPartOutOfOrder() {
        val options = SplitOptions(encoding = Encoding.Base32, maxVersion = Version.V05)
        val splitResult = SplitResult.fromData(largePsbtBytes, FileType.Psbt, options)
        assertTrue("Should have multiple parts, got ${splitResult.parts.size}", splitResult.parts.size > 1)

        val joiner = ContinuousJoiner()
        val reversed = splitResult.parts.reversed()
        var finalResult: ContinuousJoinResult? = null
        for (part in reversed) {
            finalResult = joiner.addPart(part)
        }

        assertTrue("Reversed order should still complete", finalResult is ContinuousJoinResult.Complete)
        val complete = finalResult as ContinuousJoinResult.Complete
        assertArrayEquals(largePsbtBytes, complete.joined.data)
    }

    @Test
    fun testLargePsbtRoundtrip() {
        val hugePsbt = PSBT_MAGIC + ByteArray(10000) { (it % 256).toByte() }
        val splitResult = SplitResult.fromData(hugePsbt, FileType.Psbt)
        val joined = Joined.fromParts(splitResult.parts)
        assertArrayEquals(hugePsbt, joined.data)
    }

    @Test(expected = SplitError.Empty::class)
    fun testSplitEmptyDataThrows() {
        SplitResult.fromData(ByteArray(0), FileType.Psbt)
    }
}
