package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Color
import com.gorunjinian.metrovault.domain.service.util.hexToByteArray
import com.gorunjinian.metrovault.lib.bitcoin.PSBTDecoder
import com.gorunjinian.metrovault.lib.qrtools.registry.RegistryType

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
            val ur = UR.fromBytes(RegistryType.BYTES.toString(), data)

            // Get fragment lengths based on selected density
            val (maxFragmentLen, minFragmentLen) = DensitySettings.getURFragmentLengths(density)
            android.util.Log.d("TransactionQREncoder", "UR bytes using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")

            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)

            if (encoder.isSinglePart) {
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
                if (bitmaps != null && bitmaps.isNotEmpty()) {
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
     * Distributes data evenly across frames for consistent density.
     */
    private fun generateBBQrTransaction(
        txHex: String,
        size: Int,
        foregroundColor: Int,
        backgroundColor: Int,
        density: QRDensity
    ): AnimatedQRResult? {
        return try {
            val maxQrChars = DensitySettings.getBBQrMaxChars(density)
            android.util.Log.d("TransactionQREncoder", "BBQr transaction: ${txHex.length} hex chars, density=$density")

            // Convert hex to bytes
            val txBytes = txHex.hexToByteArray()
            
            // Header is 8 chars: B$ + encoding + type + 2 chars total + 2 chars part
            val headerSize = 8
            val maxDataChars = maxQrChars - headerSize
            
            // For Base32: 8 chars = 5 bytes
            // Calculate max bytes per frame (aligned to 5-byte boundary for Base32)
            val maxBytesPerFrame = (maxDataChars / 8) * 5
            
            // Calculate minimum number of frames needed
            val totalBytes = txBytes.size
            val numFrames = kotlin.math.ceil(totalBytes.toDouble() / maxBytesPerFrame).toInt()
                .coerceAtLeast(1)
            
            if (numFrames > 1295) {
                android.util.Log.e("TransactionQREncoder", "Transaction too large for BBQr: $numFrames parts")
                return null
            }
            
            // Distribute bytes evenly across frames
            val baseBytesPerFrame = totalBytes / numFrames
            val alignedBytesPerFrame = ((baseBytesPerFrame + 4) / 5) * 5

            android.util.Log.d("TransactionQREncoder", "BBQr tx: $totalBytes bytes in $numFrames frames (~$alignedBytesPerFrame bytes/frame)")

            // Split bytes into chunks with even distribution
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            for (i in 0 until numFrames) {
                val remaining = totalBytes - offset
                val chunkSize = if (i == numFrames - 1) {
                    remaining
                } else {
                    alignedBytesPerFrame.coerceAtMost(remaining)
                }
                chunks.add(txBytes.copyOfRange(offset, offset + chunkSize))
                offset += chunkSize
            }

            // Encode each byte chunk to Base32 and prepend header
            val frameStrings = chunks.mapIndexed { index, chunk ->
                val chunkBase32 = PSBTDecoder.encodeBase32(chunk)
                val totalBase36 = numFrames.toString(36).padStart(2, '0').uppercase()
                val partBase36 = index.toString(36).padStart(2, '0').uppercase()
                "B$2T${totalBase36}${partBase36}${chunkBase32}"
            }

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
