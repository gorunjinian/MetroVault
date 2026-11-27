package com.gorunjinian.metrovault.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.BinaryBitmap
import com.google.zxing.DecodeHintType
import com.google.zxing.EncodeHintType
import com.google.zxing.RGBLuminanceSource
import com.google.zxing.Result
import com.google.zxing.common.HybridBinarizer
import com.google.zxing.qrcode.QRCodeReader
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.core.qr.byteSegment
import com.gorunjinian.metrovault.domain.service.scan.ScanPayloadClassifier
import com.gorunjinian.metrovault.domain.service.scan.ScannedPayload
import com.gorunjinian.metrovault.domain.service.scan.SeedFormat
import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import java.util.Random

/**
 * SeedQR decoding on real QR codes: each one is drawn and read back with ZXing, so the text and
 * byte segment are what the camera delivers. CompactSeedQR has no checksum, so telling one from an
 * ordinary QR code is the whole problem: misreading text as a seed offers a guessable wallet.
 */
class SeedQRUtilsTest {

    private val random = Random(20261123L)

    private fun entropy(size: Int) = ByteArray(size).also { random.nextBytes(it) }

    private fun scan(content: String, level: ErrorCorrectionLevel, charset: String? = null): Result {
        val hints = buildMap<EncodeHintType, Any> {
            put(EncodeHintType.ERROR_CORRECTION, level)
            charset?.let { put(EncodeHintType.CHARACTER_SET, it) }
        }
        val matrix = QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, 400, 400, hints)
        val pixels = IntArray(matrix.width * matrix.height) { i ->
            if (matrix.get(i % matrix.width, i / matrix.width)) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()
        }
        val image = BinaryBitmap(HybridBinarizer(RGBLuminanceSource(matrix.width, matrix.height, pixels)))
        return QRCodeReader().decode(image, mapOf(DecodeHintType.PURE_BARCODE to true))
    }

    /** A CompactSeedQR; [charset] as MetroVault draws its own ("ISO-8859-1") or without an ECI header. */
    private fun scanCompact(entropy: ByteArray, charset: String?) =
        scan(String(entropy, Charsets.ISO_8859_1), ErrorCorrectionLevel.L, charset)

    private fun Result.compactSeed() = SeedQRUtils.decodeCompactSeedQR(text, byteSegment())

    private fun Result.classify() = ScanPayloadClassifier.classifyText(text, byteSegment())

    // ==================== CompactSeedQR ====================

    @Test
    fun everyPayloadOfTheRightLengthDecodes() {
        // Why the guards exist: the checksum words are computed from the entropy, so nothing fails.
        repeat(200) { assertNotNull(SeedQRUtils.compactSeedQRToMnemonic(entropy(16))) }
    }

    @Test
    fun compactSeedQrsDecodeWithAndWithoutAnEciHeader() {
        for (charset in listOf("ISO-8859-1", null)) {
            for (size in listOf(16, 32)) {
                repeat(100) {
                    val entropy = entropy(size)
                    val seed = scanCompact(entropy, charset).classify()
                    val expected = ScannedPayload.Seed(MnemonicCode.toMnemonics(entropy), SeedFormat.COMPACT_SEEDQR)
                    assertEquals("charset=$charset entropy=${entropy.joinToString("") { "%02x".format(it) }}", expected, seed)
                }
            }
        }
    }

    @Test
    fun ordinaryQrCodesAreNeverCompactSeeds() {
        val cases = listOf(
            // Version 1-M: the data region ZXing reports as raw bytes is exactly 16 bytes long.
            Triple("hello", ErrorCorrectionLevel.M, null),
            Triple("HELLO WORLD", ErrorCorrectionLevel.M, null),
            Triple("12345", ErrorCorrectionLevel.M, null),
            // Exactly 16 or 32 characters
            Triple("sixteen chars ok", ErrorCorrectionLevel.L, null),
            Triple("1234567890123456", ErrorCorrectionLevel.L, null),
            Triple("ABCDEFGHIJKLMNOP", ErrorCorrectionLevel.L, null),
            Triple("exactly thirty-two characters ok", ErrorCorrectionLevel.L, null),
            // Readable text beyond ASCII, 16 bytes long or 16 characters long
            Triple("café müller", ErrorCorrectionLevel.M, "UTF-8"),
            Triple("Приветаа", ErrorCorrectionLevel.L, "UTF-8"),
            Triple("café de la paix!", ErrorCorrectionLevel.L, "UTF-8"),
            Triple("pay 🍕 tonight", ErrorCorrectionLevel.L, "UTF-8")
        )
        for ((content, level, charset) in cases) {
            val result = scan(content, level, charset)
            assertEquals("fixture must survive the round trip", content, result.text)
            assertNull("'$content' was read as a seed", result.compactSeed())
            assertEquals(ScannedPayload.Text(content), result.classify())
        }
    }

    @Test
    fun onlyAWholeByteSegmentCounts() {
        val entropy = entropy(16)
        val text = String(entropy, Charsets.ISO_8859_1)

        assertNotNull(SeedQRUtils.decodeCompactSeedQR(text, entropy))
        assertNull("not a byte-mode code", SeedQRUtils.decodeCompactSeedQR(text, null))
        assertNull("other segments around it", SeedQRUtils.decodeCompactSeedQR("1234$text", entropy))
        assertNull("wrong length", SeedQRUtils.decodeCompactSeedQR(text.dropLast(1), entropy.copyOf(15)))
    }

    @Test
    fun byteSegmentIsTheEncodedContent() {
        assertEquals("hello", scan("hello", ErrorCorrectionLevel.M).byteSegment()?.let { String(it) })
        assertNull("numeric mode", scan("1234567890123456", ErrorCorrectionLevel.L).byteSegment())
        assertNull("alphanumeric mode", scan("HELLO WORLD", ErrorCorrectionLevel.M).byteSegment())
    }

    // ==================== Standard SeedQR ====================

    @Test
    fun standardSeedQrsDecode() {
        for (size in listOf(16, 32)) {
            val words = MnemonicCode.toMnemonics(entropy(size))
            val digits = SeedQRUtils.mnemonicToStandardSeedQR(words)!!
            val seed = scan(digits, ErrorCorrectionLevel.L).classify()

            assertEquals(ScannedPayload.Seed(words, SeedFormat.STANDARD_SEEDQR), seed)
            assertFalse("a seed must never print its words", seed.toString().contains(words[0]))
        }
    }
}
