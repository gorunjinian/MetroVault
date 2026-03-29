package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Color
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.metrovault.domain.service.util.hexToByteArray

/**
 * Encodes raw transactions into QR code formats (BC-UR, BBQr).
 */
object TransactionQREncoder {

    /**
     * Generates QR code(s) for a raw finalized transaction hex.
     * Uses appropriate encoding based on format:
     * - BC-UR: ur:bytes type (raw bytes in CBOR)
     * - BBQr: T$ prefix for transaction type
     *
     * @param txHex Raw transaction hex string
     * @param format Output format (UR_LEGACY, UR_MODERN, or BBQR)
     * @param density QR code density setting
     * @return AnimatedQRResult with QR code bitmaps, or null on failure
     */
    fun generateRawTxQR(
        txHex: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_LEGACY,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? {
        return try {
            // Convert hex string to bytes
            val txBytes = txHex.hexToByteArray()
            android.util.Log.d("TransactionQREncoder", "Generating raw tx QR: ${txBytes.size} bytes, format=$format")

            when (format) {
                OutputFormat.UR_LEGACY, OutputFormat.UR_MODERN -> {
                    // Use ur:bytes for raw transaction data
                    generateURBytesQR(txBytes, size, foregroundColor, backgroundColor, density, format)
                }
                OutputFormat.BBQR -> {
                    // Use BBQr with transaction type
                    generateBBQrTransaction(txHex, size, foregroundColor, backgroundColor, density)
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("TransactionQREncoder", "Raw tx QR generation failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Generate QR code(s) in BC-UR format using ur:bytes type for raw binary data.
     */
    private fun generateURBytesQR(
        data: ByteArray,
        size: Int,
        foregroundColor: Int,
        backgroundColor: Int,
        density: QRDensity,
        format: OutputFormat
    ): AnimatedQRResult? {
        return try {
            // Create UR using "bytes" type for raw binary data
            val ur = UR.fromBytes(data)

            // Get fragment lengths based on selected density
            val (maxFragmentLen, minFragmentLen) = DensitySettings.getURFragmentLengths(density)
            android.util.Log.d("TransactionQREncoder", "UR bytes using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")

            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)

            if (encoder.isSinglePart()) {
                // Single frame
                val urString = encoder.nextPart()
                android.util.Log.d("TransactionQREncoder", "UR bytes single part: ${urString.length} chars")
                val bitmap = QRCodeGenerator.generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = format
                    )
                } else null
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("TransactionQREncoder", "UR bytes multi-part: seqLen=$seqLen")

                val frameStrings = mutableListOf<String>()
                repeat(seqLen) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }

                val bitmaps = QRCodeGenerator.generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
                if (!bitmaps.isNullOrEmpty()) {
                    android.util.Log.d("TransactionQREncoder", "UR bytes: Generated ${bitmaps.size} frames")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = format
                    )
                } else null
            }
        } catch (e: Exception) {
            android.util.Log.e("TransactionQREncoder", "UR bytes encoding failed: ${e.message}")
            null
        }
    }

    /**
     * Generate QR code(s) in BBQr format for raw transaction.
     * Uses 'T' type code for transaction (per BBQr spec).
     */
    private fun generateBBQrTransaction(
        txHex: String,
        size: Int,
        foregroundColor: Int,
        backgroundColor: Int,
        density: QRDensity
    ): AnimatedQRResult? {
        return try {
            val txBytes = txHex.hexToByteArray()
            val options = DensitySettings.getBBQrSplitOptions(density)
            android.util.Log.d("TransactionQREncoder", "BBQr transaction: ${txBytes.size} bytes, density=$density")

            val splitResult = SplitResult.fromData(txBytes, FileType.Transaction, options)
            val frameStrings = splitResult.parts

            android.util.Log.d("TransactionQREncoder", "BBQr tx: ${frameStrings.size} frames (version=${splitResult.version}, encoding=${splitResult.encoding})")

            val bitmaps = if (frameStrings.size > 1) {
                QRCodeGenerator.generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
            } else {
                frameStrings.mapNotNull { QRCodeGenerator.generateQRCode(it, size, foregroundColor, backgroundColor) }
            }

            if (bitmaps != null && bitmaps.size == frameStrings.size) {
                android.util.Log.d("TransactionQREncoder", "BBQr tx: Generated ${bitmaps.size} frames")
                AnimatedQRResult(
                    frames = bitmaps,
                    totalParts = bitmaps.size,
                    isAnimated = bitmaps.size > 1,
                    recommendedFrameDelayMs = if (bitmaps.size > 5) 500 else 400,
                    format = OutputFormat.BBQR
                )
            } else {
                android.util.Log.e("TransactionQREncoder", "BBQr tx bitmap generation failed")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("TransactionQREncoder", "BBQr tx exception: ${e.message}")
            null
        }
    }
}
