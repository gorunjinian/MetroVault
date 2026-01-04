package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Color
import com.gorunjinian.metrovault.lib.bitcoin.PSBTDecoder
import com.gorunjinian.metrovault.lib.qrtools.registry.RegistryType

/**
 * Encodes PSBTs into QR code formats (BC-UR v1, BC-UR v2, BBQr).
 */
object PSBTQREncoder {

    /**
     * Smart QR code generation that automatically handles large PSBTs.
     * Returns either a single frame or animated frames as needed.
     * Default output is BC-UR format (ur:psbt/) for best interoperability.
     * Uses configurable density for controlling frame capacity.
     * 
     * @param density Controls QR code density: LOW (easy scan), MEDIUM (balanced), HIGH (dense)
     */
    fun generateSmartPSBTQR(
        psbt: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_LEGACY,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? {
        return when (format) {
            OutputFormat.UR_LEGACY -> {
                // Try BC-UR v1 (crypto-psbt) first, fall back to BBQr if it fails
                val result = generateURPsbtQRv1(psbt, size, foregroundColor, backgroundColor, density)
                if (result != null) {
                    result
                } else {
                    android.util.Log.w("PSBTQREncoder", "BC-UR v1 returned null, falling back to BBQr")
                    generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
                }
            }
            OutputFormat.BBQR -> generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
            OutputFormat.UR_MODERN -> {
                // Try BC-UR v2 (psbt) first, fall back to BBQr if it fails
                val result = generateURPsbtQRv2(psbt, size, foregroundColor, backgroundColor, density)
                if (result != null) {
                    result
                } else {
                    android.util.Log.w("PSBTQREncoder", "BC-UR v2 returned null, falling back to BBQr")
                    generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
                }
            }
        }
    }
    
    /**
     * Generate QR code(s) in BC-UR v1 format (ur:crypto-psbt/) using URTools library.
     * Uses proper fountain codes for multi-frame encoding.
     * This is the legacy format for wider compatibility with BlueWallet, Sparrow, etc.
     */
    private fun generateURPsbtQRv1(
        psbt: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? {
        return try {
            // Decode Base64 PSBT to bytes
            val psbtBytes = android.util.Base64.decode(psbt, android.util.Base64.NO_WRAP)
            
            // Create UR using "crypto-psbt" type (BC-UR v1) for legacy wallet compatibility
            val ur = UR.fromBytes(RegistryType.CRYPTO_PSBT.toString(), psbtBytes)
            
            // Get fragment lengths based on selected density
            val (maxFragmentLen, minFragmentLen) = DensitySettings.getURFragmentLengths(density)
            android.util.Log.d("PSBTQREncoder", "BC-UR v1 using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")
            
            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)
            
            if (encoder.isSinglePart) {
                // Single frame - simple case
                val urString = encoder.nextPart()
                android.util.Log.d("PSBTQREncoder", "BC-UR v1 single part: ${urString.length} chars")
                val bitmap = QRCodeGenerator.generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    android.util.Log.d("PSBTQREncoder", "BC-UR v1 single part: bitmap generated successfully")
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.UR_LEGACY
                    )
                } else {
                    android.util.Log.e("PSBTQREncoder", "BC-UR v1 single part: bitmap generation failed!")
                    null
                }
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("PSBTQREncoder", "BC-UR v1 multi-part: seqLen=$seqLen")

                val frameStrings = mutableListOf<String>()
                repeat(seqLen) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }
                
                // Generate QR codes for all frames
                val bitmaps = QRCodeGenerator.generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
                if (bitmaps != null && bitmaps.isNotEmpty()) {
                    android.util.Log.d("PSBTQREncoder", "BC-UR v1: Generated ${bitmaps.size} frames (seqLen=$seqLen, ${psbtBytes.size} bytes)")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = OutputFormat.UR_LEGACY
                    )
                } else {
                    android.util.Log.e("PSBTQREncoder", "BC-UR v1 multi-part: bitmap generation failed!")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PSBTQREncoder", "BC-UR v1 encoding failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Generate QR code(s) in BC-UR v2 format (ur:psbt/) using URTools library.
     * Uses proper fountain codes for multi-frame encoding.
     * This is the modern UR 2.0 standard format.
     */
    private fun generateURPsbtQRv2(
        psbt: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? {
        return try {
            // Decode Base64 PSBT to bytes
            val psbtBytes = android.util.Base64.decode(psbt, android.util.Base64.NO_WRAP)
            
            // Create UR using "psbt" type (BC-UR v2) for modern UR 2.0 standard
            val ur = UR.fromBytes(RegistryType.PSBT.toString(), psbtBytes)
            
            // Get fragment lengths based on selected density
            val (maxFragmentLen, minFragmentLen) = DensitySettings.getURFragmentLengths(density)
            android.util.Log.d("PSBTQREncoder", "BC-UR v2 using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")
            
            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)
            
            if (encoder.isSinglePart) {
                // Single frame - simple case
                val urString = encoder.nextPart()
                android.util.Log.d("PSBTQREncoder", "BC-UR v2 single part: ${urString.length} chars")
                val bitmap = QRCodeGenerator.generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    android.util.Log.d("PSBTQREncoder", "BC-UR v2 single part: bitmap generated successfully")
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.UR_MODERN
                    )
                } else {
                    android.util.Log.e("PSBTQREncoder", "BC-UR v2 single part: bitmap generation failed!")
                    null
                }
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("PSBTQREncoder", "BC-UR v2 multi-part: seqLen=$seqLen")

                val frameStrings = mutableListOf<String>()
                repeat(seqLen) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }
                
                // Generate QR codes for all frames
                val bitmaps = QRCodeGenerator.generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
                if (bitmaps != null && bitmaps.isNotEmpty()) {
                    android.util.Log.d("PSBTQREncoder", "BC-UR v2: Generated ${bitmaps.size} frames (seqLen=$seqLen, ${psbtBytes.size} bytes)")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = OutputFormat.UR_MODERN
                    )
                } else {
                    android.util.Log.e("PSBTQREncoder", "BC-UR v2 multi-part: bitmap generation failed!")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("PSBTQREncoder", "BC-UR v2 encoding failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Generate QR code(s) in BBQr format.
     */
    private fun generateBBQrPSBT(
        psbt: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? {
        return try {
            // Get max characters per frame based on selected density
            val maxQrChars = DensitySettings.getBBQrMaxChars(density)
            android.util.Log.d("PSBTQREncoder", "BBQr generation starting, PSBT length: ${psbt.length}, density=$density, maxChars=$maxQrChars")
            val bbqrFrames = PSBTDecoder.encodeToBBQr(psbt, maxQrChars)
            if (bbqrFrames == null || bbqrFrames.isEmpty()) {
                android.util.Log.w("PSBTQREncoder", "BBQr encoding failed - encodeToBBQr returned null/empty")
                return null
            }
            
            android.util.Log.d("PSBTQREncoder", "BBQr encoded to ${bbqrFrames.size} frames")
            
            // Generate consistent QR codes for all frames
            val bitmaps = if (bbqrFrames.size > 1) {
                QRCodeGenerator.generateConsistentQRCodes(bbqrFrames, size, foregroundColor, backgroundColor)
            } else {
                bbqrFrames.mapNotNull { frame ->
                    QRCodeGenerator.generateQRCode(frame, size, foregroundColor, backgroundColor)
                }
            }
            
            if (bitmaps != null && bitmaps.size == bbqrFrames.size) {
                android.util.Log.d("PSBTQREncoder", "BBQr generation successful: ${bitmaps.size} frames")
                AnimatedQRResult(
                    frames = bitmaps,
                    totalParts = bitmaps.size,
                    isAnimated = bitmaps.size > 1,
                    recommendedFrameDelayMs = if (bitmaps.size > 5) 500 else 400,
                    format = OutputFormat.BBQR
                )
            } else {
                android.util.Log.e("PSBTQREncoder", "BBQr bitmap generation failed: got ${bitmaps?.size ?: 0} bitmaps for ${bbqrFrames.size} frames")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("PSBTQREncoder", "BBQr generation exception: ${e.message}")
            e.printStackTrace()
            null
        }
    }
}
