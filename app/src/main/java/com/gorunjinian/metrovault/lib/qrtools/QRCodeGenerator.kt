package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.createBitmap

/**
 * Raw QR code module data for Canvas-based rendering.
 * @param modules Boolean array where true = dark module, row-major order
 * @param moduleCount Number of modules per side (e.g., 21, 25, 29)
 */
data class QRModuleData(
    val modules: BooleanArray,
    val moduleCount: Int
) {
    /** Returns whether module at (col, row) is dark. */
    fun isDark(col: Int, row: Int): Boolean = modules[row * moduleCount + col]

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is QRModuleData) return false
        return moduleCount == other.moduleCount && modules.contentEquals(other.modules)
    }

    override fun hashCode(): Int = 31 * modules.contentHashCode() + moduleCount
}

/**
 * Basic QR code bitmap generation utilities.
 */
object QRCodeGenerator {

    /**
     * Generates QR code bitmap from text.
     * For large content, returns null - use animated QR generation instead.
     */
    fun generateQRCode(
        content: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 1)
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
                }
            }

            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Generates QR code for binary data using ISO-8859-1 encoding.
     * Used for SeedQR and other binary-encoded QR codes.
     */
    fun generateBinaryQRCode(
        bytes: ByteArray,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            // Convert bytes to ISO-8859-1 string (1:1 byte mapping)
            val content = String(bytes, Charsets.ISO_8859_1)

            val hints = hashMapOf<EncodeHintType, Any>().apply {
                // Use "L" (Low) error correction as specified in SeedQR spec for smallest QR
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
                put(EncodeHintType.MARGIN, 1)
                // ISO-8859-1 is essential for binary data - maps bytes 0-255 to chars 1:1
                put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val pixels = IntArray(size * size)
            for (y in 0 until size) {
                val offset = y * size
                for (x in 0 until size) {
                    pixels[offset + x] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
                }
            }

            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            bitmap.setPixels(pixels, 0, size, 0, 0, size, size)
            bitmap
        } catch (e: Exception) {
            android.util.Log.e("QRCodeGenerator", "generateBinaryQRCode failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Generates multiple QR codes for animated sequences.
     * Uses parallel processing for improved performance with multiple frames.
     */
    fun generateConsistentQRCodes(
        contents: List<String>,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): List<Bitmap>? {
        if (contents.isEmpty()) return null
        
        return try {
            // Use parallel stream for faster multi-frame generation
            val results = contents.parallelStream()
                .map { content -> 
                    content to generateQRCode(content, size, foregroundColor, backgroundColor)
                }
                .collect(java.util.stream.Collectors.toList())
            
            // Check if all succeeded and maintain order
            val bitmaps = results.map { it.second }
            if (bitmaps.all { it != null }) {
                @Suppress("UNCHECKED_CAST")
                bitmaps as List<Bitmap>
            } else {
                null
            }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates QR code for Bitcoin address with bitcoin: URI prefix
     */
    fun generateAddressQRCode(address: String, size: Int = 768): Bitmap? {
        return generateQRCode("bitcoin:$address", size)
    }

    // ==================== Module Data Extraction (for Canvas-based rendering) ====================

    /**
     * Extracts raw QR module data for text content (Standard SeedQR, etc.).
     * Returns the module grid at native QR dimensions with no margin.
     */
    fun extractModuleData(
        content: String,
        errorCorrection: ErrorCorrectionLevel = ErrorCorrectionLevel.M
    ): QRModuleData? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to errorCorrection,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "UTF-8"
            )
            val bitMatrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 1, 1, hints
            )
            bitMatrixToModuleData(bitMatrix)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Extracts raw QR module data for binary content (Compact SeedQR).
     * Uses ISO-8859-1 encoding and Error Correction L as per SeedQR spec.
     */
    fun extractBinaryModuleData(bytes: ByteArray): QRModuleData? {
        return try {
            val content = String(bytes, Charsets.ISO_8859_1)
            val hints = hashMapOf<EncodeHintType, Any>(
                EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.L,
                EncodeHintType.MARGIN to 0,
                EncodeHintType.CHARACTER_SET to "ISO-8859-1"
            )
            val bitMatrix = QRCodeWriter().encode(
                content, BarcodeFormat.QR_CODE, 1, 1, hints
            )
            bitMatrixToModuleData(bitMatrix)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /** Converts a ZXing BitMatrix to QRModuleData. */
    private fun bitMatrixToModuleData(
        bitMatrix: com.google.zxing.common.BitMatrix
    ): QRModuleData {
        val size = bitMatrix.width
        val modules = BooleanArray(size * size) { i ->
            bitMatrix[i % size, i / size]
        }
        return QRModuleData(modules, size)
    }
}
