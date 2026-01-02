package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.createBitmap
import com.gorunjinian.metrovault.lib.bitcoin.PSBTDecoder
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.byteVector32
import com.gorunjinian.metrovault.lib.qrtools.registry.RegistryType

/**
 * Utilities for QR code generation including animated QR codes for large data.
 */
object QRCodeUtils {

    // Prefix for animated QR code frames
    private const val ANIMATED_PREFIX = "p"  // p[part]/[total] format

    /**
     * Generates QR code bitmap from text.
     * For large content, returns null - use generateAnimatedQRFrames instead.
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
            android.util.Log.e("QRCodeUtils", "generateBinaryQRCode failed: ${e.message}")
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

    /**
     * Output format options for signed PSBT QR codes
     */
    enum class OutputFormat(val displayName: String) {
        UR_LEGACY("BC-UR v1"),  // Legacy ur:crypto-psbt/ format - BlueWallet, Sparrow compatibility
        BBQR("BBQr"),           // BBQr format - best for Coldcard
        UR_MODERN("BC-UR v2")   // Modern ur:psbt/ format - UR 2.0 standard
    }
    
    /**
     * QR code density options for controlling frame capacity.
     * Based on BBQr spec and Sparrow Hummingbird library recommendations.
     */
    enum class QRDensity(val displayName: String) {
        LOW("Low"),      // Easy to scan, more frames (~QR v10-15)
        MEDIUM("Medium"), // Balanced approach, recommended sweet spot (~QR v21-27)
        HIGH("High")     // Dense QR, fewer frames (~QR v35-40)
    }
    
    /**
     * Density settings for different QR generation methods.
     * Values based on:
     * - BBQr spec: https://bbqr.org/BBQr.html
     * - Sparrow Hummingbird: https://github.com/sparrowwallet/hummingbird
     */
    private object DensitySettings {
        // BC-UR fragment lengths (bytes before UR encoding)
        const val UR_MAX_FRAG_LOW = 100     // Sparrow default
        const val UR_MIN_FRAG_LOW = 10
        const val UR_MAX_FRAG_MEDIUM = 250  // Current default
        const val UR_MIN_FRAG_MEDIUM = 50
        const val UR_MAX_FRAG_HIGH = 400    // Dense QR
        const val UR_MIN_FRAG_HIGH = 100
        
        // BBQr max alphanumeric characters per frame
        const val BBQR_MAX_CHARS_LOW = 350    // Easy to scan
        const val BBQR_MAX_CHARS_MEDIUM = 700 // Sweet spot (~QR v27)
        const val BBQR_MAX_CHARS_HIGH = 1200  // Dense (~QR v40)
        
        fun getURFragmentLengths(density: QRDensity): Pair<Int, Int> = when (density) {
            QRDensity.LOW -> Pair(UR_MAX_FRAG_LOW, UR_MIN_FRAG_LOW)
            QRDensity.MEDIUM -> Pair(UR_MAX_FRAG_MEDIUM, UR_MIN_FRAG_MEDIUM)
            QRDensity.HIGH -> Pair(UR_MAX_FRAG_HIGH, UR_MIN_FRAG_HIGH)
        }
        
        fun getBBQrMaxChars(density: QRDensity): Int = when (density) {
            QRDensity.LOW -> BBQR_MAX_CHARS_LOW
            QRDensity.MEDIUM -> BBQR_MAX_CHARS_MEDIUM
            QRDensity.HIGH -> BBQR_MAX_CHARS_HIGH
        }
    }

    
    /**
     * Data class to hold animated QR code information
     */
    data class AnimatedQRResult(
        val frames: List<Bitmap>,
        val totalParts: Int,
        val isAnimated: Boolean,
        val recommendedFrameDelayMs: Long = 500,
        val format: OutputFormat = OutputFormat.UR_LEGACY
    )

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
                    android.util.Log.w("QRCodeUtils", "BC-UR v1 returned null, falling back to BBQr")
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
                    android.util.Log.w("QRCodeUtils", "BC-UR v2 returned null, falling back to BBQr")
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
            android.util.Log.d("QRCodeUtils", "BC-UR v1 using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")
            
            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)
            
            if (encoder.isSinglePart) {
                // Single frame - simple case
                val urString = encoder.nextPart()
                android.util.Log.d("QRCodeUtils", "BC-UR v1 single part: ${urString.length} chars")
                val bitmap = generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    android.util.Log.d("QRCodeUtils", "BC-UR v1 single part: bitmap generated successfully")
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.UR_LEGACY
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR v1 single part: bitmap generation failed!")
                    null
                }
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("QRCodeUtils", "BC-UR v1 multi-part: seqLen=$seqLen")
                // Generate exactly seqLen frames - fountain codes provide redundancy naturally
                // when looping through the animation
                val totalFrames = seqLen
                
                val frameStrings = mutableListOf<String>()
                repeat(totalFrames) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }
                
                // Generate QR codes for all frames
                val bitmaps = generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
                if (bitmaps != null && bitmaps.isNotEmpty()) {
                    android.util.Log.d("QRCodeUtils", "BC-UR v1: Generated ${bitmaps.size} frames (seqLen=$seqLen, ${psbtBytes.size} bytes)")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = OutputFormat.UR_LEGACY
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR v1 multi-part: bitmap generation failed!")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QRCodeUtils", "BC-UR v1 encoding failed: ${e.message}")
            e.printStackTrace()
            null  // Don't fall back here, let the caller handle fallback
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
            android.util.Log.d("QRCodeUtils", "BC-UR v2 using density=$density, maxFrag=$maxFragmentLen, minFrag=$minFragmentLen")
            
            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)
            
            if (encoder.isSinglePart) {
                // Single frame - simple case
                val urString = encoder.nextPart()
                android.util.Log.d("QRCodeUtils", "BC-UR v2 single part: ${urString.length} chars")
                val bitmap = generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    android.util.Log.d("QRCodeUtils", "BC-UR v2 single part: bitmap generated successfully")
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.UR_MODERN
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR v2 single part: bitmap generation failed!")
                    null
                }
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("QRCodeUtils", "BC-UR v2 multi-part: seqLen=$seqLen")
                // Generate exactly seqLen frames - fountain codes provide redundancy naturally
                // when looping through the animation
                val totalFrames = seqLen
                
                val frameStrings = mutableListOf<String>()
                repeat(totalFrames) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }
                
                // Generate QR codes for all frames
                val bitmaps = generateConsistentQRCodes(frameStrings, size, foregroundColor, backgroundColor)
                if (bitmaps != null && bitmaps.isNotEmpty()) {
                    android.util.Log.d("QRCodeUtils", "BC-UR v2: Generated ${bitmaps.size} frames (seqLen=$seqLen, ${psbtBytes.size} bytes)")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = OutputFormat.UR_MODERN
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR v2 multi-part: bitmap generation failed!")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QRCodeUtils", "BC-UR v2 encoding failed: ${e.message}")
            e.printStackTrace()
            null  // Don't fall back here, let the caller handle fallback
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
            android.util.Log.d("QRCodeUtils", "BBQr generation starting, PSBT length: ${psbt.length}, density=$density, maxChars=$maxQrChars")
            val bbqrFrames = PSBTDecoder.encodeToBBQr(psbt, maxQrChars)
            if (bbqrFrames == null || bbqrFrames.isEmpty()) {
                android.util.Log.w("QRCodeUtils", "BBQr encoding failed - encodeToBBQr returned null/empty")
                return null
            }
            
            android.util.Log.d("QRCodeUtils", "BBQr encoded to ${bbqrFrames.size} frames")
            
            // Generate consistent QR codes for all frames
            val bitmaps = if (bbqrFrames.size > 1) {
                generateConsistentQRCodes(bbqrFrames, size, foregroundColor, backgroundColor)
            } else {
                bbqrFrames.mapNotNull { frame ->
                    generateQRCode(frame, size, foregroundColor, backgroundColor)
                }
            }
            
            if (bitmaps != null && bitmaps.size == bbqrFrames.size) {
                android.util.Log.d("QRCodeUtils", "BBQr generation successful: ${bitmaps.size} frames")
                AnimatedQRResult(
                    frames = bitmaps,
                    totalParts = bitmaps.size,
                    isAnimated = bitmaps.size > 1,
                    recommendedFrameDelayMs = if (bitmaps.size > 5) 500 else 400,
                    format = OutputFormat.BBQR
                )
            } else {
                android.util.Log.e("QRCodeUtils", "BBQr bitmap generation failed: got ${bitmaps?.size ?: 0} bitmaps for ${bbqrFrames.size} frames")
                null
            }
        } catch (e: Exception) {
            android.util.Log.e("QRCodeUtils", "BBQr generation exception: ${e.message}")
            e.printStackTrace()
            null
        }
    }

    /**
     * Parses animated QR frame to extract part info.
     * Returns (partNumber, totalParts, data) or null if not animated format.
     */
    fun parseAnimatedFrame(content: String): Triple<Int, Int, String>? {
        return try {
            when {
                // Simple format: "p1/10 data"
                content.startsWith(ANIMATED_PREFIX) -> {
                    val spaceIndex = content.indexOf(' ')
                    if (spaceIndex == -1) return null

                    val header = content.substring(1, spaceIndex) // Remove 'p' prefix
                    val parts = header.split("/")
                    if (parts.size != 2) return null

                    val partNum = parts[0].toIntOrNull() ?: return null
                    val totalParts = parts[1].toIntOrNull() ?: return null
                    val data = content.substring(spaceIndex + 1)

                    Triple(partNum, totalParts, data)
                }
                // UR format: "ur:bytes/1-10/data"
                content.startsWith("ur:bytes/") -> {
                    val withoutPrefix = content.removePrefix("ur:bytes/")
                    val slashIndex = withoutPrefix.indexOf('/')
                    if (slashIndex == -1) return null

                    val header = withoutPrefix.take(slashIndex)
                    val parts = header.split("-")
                    if (parts.size != 2) return null

                    val partNum = parts[0].toIntOrNull() ?: return null
                    val totalParts = parts[1].toIntOrNull() ?: return null
                    val data = withoutPrefix.substring(slashIndex + 1)

                    Triple(partNum, totalParts, data)
                }
                else -> null
            }
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Helper class to track animated QR scanning progress.
     * Supports multiple formats: simple p1/10, ur:bytes/, BBQr (B$...), and ur:crypto-psbt/
     * 
     * For UR formats (ur:psbt/, ur:crypto-psbt/), uses Hummingbird's URDecoder
     * which properly handles fountain code reconstruction.
     */
    class AnimatedQRScanner {
        // For non-UR formats (BBQr, simple)
        private val receivedFrames = mutableMapOf<Int, String>()
        private var expectedTotal: Int? = null
        private var detectedFormat: String? = null  // "simple", "ur", "bbqr", "ur-psbt"
        
        // For UR formats - use local URTools library which handles fountain codes
        private var urDecoder: URDecoder? = null
        private var urFrameCount: Int = 0
        private var urEstimatedTotal: Int = 0

        /**
         * Process a scanned frame.
         * @return Progress percentage (0-100), or null if frame is invalid
         */
        fun processFrame(content: String): Int? {
            val lower = content.lowercase()
            
            // Detect format on first frame
            if (detectedFormat == null) {
                detectedFormat = when {
                    content.startsWith("B$") -> "bbqr"
                    lower.startsWith("ur:psbt/") || lower.startsWith("ur:crypto-psbt/") -> "ur-psbt"
                    content.startsWith("ur:") -> "ur"
                    content.startsWith(ANIMATED_PREFIX) -> "simple"
                    else -> "single"
                }
                android.util.Log.d("AnimatedQRScanner", "Detected format: $detectedFormat")
            }
            
            // Handle UR formats with Hummingbird's URDecoder (fountain code support)
            if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
                return processURFrame(content)
            }
            
            // Handle non-UR formats with our own logic
            return processNonURFrame(content)
        }
        
        /**
         * Process UR format frames using URTools library
         */
        private fun processURFrame(content: String): Int? {
            try {
                // Initialize URDecoder if needed
                if (urDecoder == null) {
                    urDecoder = URDecoder()
                    android.util.Log.d("AnimatedQRScanner", "Initialized URDecoder for fountain codes")
                }
                
                val decoder = urDecoder!!
                decoder.receivePart(content)
                urFrameCount++
                
                // Get progress info
                val progress = decoder.estimatedPercentComplete
                if (urEstimatedTotal == 0 && decoder.expectedPartCount > 0) {
                    urEstimatedTotal = decoder.expectedPartCount
                }
                
                val percentComplete = (progress * 100).toInt().coerceIn(0, 100)
                android.util.Log.d("AnimatedQRScanner", "UR frame $urFrameCount received, progress: $percentComplete%, expected parts: $urEstimatedTotal")
                
                return percentComplete
            } catch (e: Exception) {
                android.util.Log.e("AnimatedQRScanner", "Error processing UR frame: ${e.message}")
                e.printStackTrace()
                return null
            }
        }
        
        /**
         * Process non-UR formats (BBQr, simple)
         */
        private fun processNonURFrame(content: String): Int? {
            val parsed = when (detectedFormat) {
                "bbqr" -> PSBTDecoder.parseBBQrFrame(content)
                "simple" -> parseAnimatedFrame(content)
                "single" -> {
                    receivedFrames[1] = content
                    expectedTotal = 1
                    return 100
                }
                else -> null
            }
            
            if (parsed == null) {
                android.util.Log.d("AnimatedQRScanner", "Could not parse frame")
                return null
            }

            val (partNum, totalParts, data) = parsed

            if (expectedTotal == null) {
                expectedTotal = totalParts
            } else if (expectedTotal != totalParts) {
                android.util.Log.w("AnimatedQRScanner", "Total parts changed, resetting")
                reset()
                expectedTotal = totalParts
            }

            receivedFrames[partNum] = data
            android.util.Log.d("AnimatedQRScanner", "Got frame $partNum/$totalParts, have ${receivedFrames.size} frames")

            return ((receivedFrames.size * 100) / totalParts)
        }

        /**
         * Check if all frames have been received
         */
        fun isComplete(): Boolean {
            // For UR formats, check URDecoder result
            if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
                val result = urDecoder?.result
                if (result != null) {
                    android.util.Log.d("AnimatedQRScanner", "URDecoder complete, result type: ${result.type}")
                    return result.type == ResultType.SUCCESS
                }
                return false
            }
            
            // For non-UR formats
            val total = expectedTotal ?: return false
            return receivedFrames.size >= total
        }

        /**
         * Get the reassembled and decoded PSBT as Base64.
         */
        fun getResult(): String? {
            if (!isComplete()) return null
            
            return when (detectedFormat) {
                "ur-psbt", "ur" -> {
                    // Get result from URDecoder
                    val result = urDecoder?.result
                    if (result?.type == ResultType.SUCCESS) {
                        try {
                            val ur = result.ur
                            val psbtBytes = ur.toBytes()
                            android.util.Log.d("AnimatedQRScanner", "URDecoder success, PSBT size: ${psbtBytes.size} bytes")
                            android.util.Base64.encodeToString(psbtBytes, android.util.Base64.NO_WRAP)
                        } catch (e: Exception) {
                            android.util.Log.e("AnimatedQRScanner", "Failed to get UR result: ${e.message}")
                            null
                        }
                    } else {
                        android.util.Log.w("AnimatedQRScanner", "URDecoder result not successful: ${result?.type}")
                        null
                    }
                }
                "bbqr" -> {
                    val frames = receivedFrames.entries.sortedBy { it.key }.map { it.value }
                    PSBTDecoder.decodeBBQrFrames(frames)
                }
                "single" -> {
                    PSBTDecoder.decode(receivedFrames[1] ?: return null)
                }
                else -> {
                    val frames = receivedFrames.entries.sortedBy { it.key }.map { it.value }
                    val combined = frames.joinToString("")
                    PSBTDecoder.decode(combined) ?: combined
                }
            }
        }

        /**
         * Reset scanner state
         */
        fun reset() {
            receivedFrames.clear()
            expectedTotal = null
            detectedFormat = null
            urDecoder = null
            urFrameCount = 0
            urEstimatedTotal = 0
        }

        /**
         * Get progress as human-readable string
         */
        fun getProgressString(): String {
            if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
                val progress = (urDecoder?.estimatedPercentComplete ?: 0.0) * 100
                return "${progress.toInt()}% BC-UR"
            }
            val total = expectedTotal ?: return "Waiting..."
            val formatLabel = when (detectedFormat) {
                "bbqr" -> "BBQr"
                else -> "QR"
            }
            return "${receivedFrames.size}/$total $formatLabel parts"
        }
        
        /**
         * Get detected format name
         */
        fun getDetectedFormat(): String? = detectedFormat
    }
    
    /**
     * Helper class to track animated QR scanning for descriptors.
     * Supports: UR:CRYPTO-OUTPUT, UR:OUTPUT-DESCRIPTOR, BBQr with text content
     * 
     * Similar to AnimatedQRScanner but returns raw descriptor string instead of PSBT.
     */
    class DescriptorQRScanner {
        // For non-UR formats (BBQr)
        private val receivedFrames = mutableMapOf<Int, String>()
        private var expectedTotal: Int? = null
        private var detectedFormat: String? = null
        
        // For UR formats - use URDecoder which handles fountain codes
        private var urDecoder: URDecoder? = null
        private var urFrameCount: Int = 0
        private var urEstimatedTotal: Int = 0
        
        // Single-frame result
        private var singleFrameResult: String? = null
        
        /**
         * Process a scanned frame.
         * @return Progress percentage (0-100), or null if frame is invalid
         */
        fun processFrame(content: String): Int? {
            val lower = content.lowercase()
            
            // Detect format on first frame
            if (detectedFormat == null) {
                detectedFormat = when {
                    content.startsWith("B$") -> "bbqr"
                    lower.startsWith("ur:crypto-output/") -> "ur-crypto-output"
                    lower.startsWith("ur:output-descriptor/") -> "ur-output-descriptor"
                    lower.startsWith("ur:bytes/") -> "ur-bytes"  // BSMS exported as raw bytes
                    lower.startsWith("wsh(") || lower.startsWith("sh(") ||
                    lower.startsWith("wpkh(") || lower.startsWith("pkh(") ||
                    lower.startsWith("tr(") -> "plain"
                    else -> "unknown"
                }
                android.util.Log.d("DescriptorQRScanner", "Detected format: $detectedFormat")
            }

            return when (detectedFormat) {
                "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> processURFrame(content)
                "bbqr" -> processBBQrFrame(content)
                "plain" -> {
                    singleFrameResult = content
                    100 // Complete immediately
                }
                else -> {
                    // Try to handle as single plain text
                    singleFrameResult = content
                    100
                }
            }
        }
        
        /**
         * Process UR format frames using URDecoder
         */
        private fun processURFrame(content: String): Int? {
            try {
                // Initialize URDecoder if needed
                if (urDecoder == null) {
                    urDecoder = URDecoder()
                    android.util.Log.d("DescriptorQRScanner", "Initialized URDecoder for fountain codes")
                }
                
                val decoder = urDecoder!!
                decoder.receivePart(content)
                urFrameCount++
                
                // Get progress info
                val progress = decoder.estimatedPercentComplete
                if (urEstimatedTotal == 0 && decoder.expectedPartCount > 0) {
                    urEstimatedTotal = decoder.expectedPartCount
                }
                
                val percentComplete = (progress * 100).toInt().coerceIn(0, 100)
                android.util.Log.d("DescriptorQRScanner", "UR frame $urFrameCount received, progress: $percentComplete%")
                
                return percentComplete
            } catch (e: Exception) {
                android.util.Log.e("DescriptorQRScanner", "Error processing UR frame: ${e.message}")
                return null
            }
        }
        
        /**
         * Process BBQr format frames
         */
        private fun processBBQrFrame(content: String): Int? {
            val parsed = PSBTDecoder.parseBBQrFrame(content) ?: return null
            val (partNum, totalParts, _) = parsed
            
            if (expectedTotal == null) {
                expectedTotal = totalParts
            } else if (expectedTotal != totalParts) {
                android.util.Log.w("DescriptorQRScanner", "Total parts changed, resetting")
                reset()
                expectedTotal = totalParts
            }
            
            // Store the full frame (including header) for later decoding
            receivedFrames[partNum] = content
            android.util.Log.d("DescriptorQRScanner", "BBQr frame $partNum/$totalParts, have ${receivedFrames.size} frames")
            
            return ((receivedFrames.size * 100) / totalParts)
        }
        
        /**
         * Check if all frames have been received
         */
        fun isComplete(): Boolean {
            return when (detectedFormat) {
                "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> {
                    val result = urDecoder?.result
                    result?.type == ResultType.SUCCESS
                }
                "bbqr" -> {
                    val total = expectedTotal ?: return false
                    receivedFrames.size >= total
                }
                "plain", "unknown" -> singleFrameResult != null
                else -> false
            }
        }
        
        /**
         * Get the assembled descriptor string.
         * Returns null if not complete.
         */
        fun getResult(): String? {
            if (!isComplete()) return null

            return when (detectedFormat) {
                "ur-crypto-output", "ur-output-descriptor" -> {
                    // Get UR from decoder and use DescriptorDecoder to convert to string
                    val result = urDecoder?.result
                    if (result?.type == ResultType.SUCCESS) {
                        try {
                            val ur = result.ur
                            // Use DescriptorDecoder's reconstruction logic
                            when (val decoded = ur.decodeFromRegistry()) {
                                is com.gorunjinian.metrovault.lib.qrtools.registry.CryptoOutput -> {
                                    reconstructDescriptorFromCryptoOutput(decoded)
                                }

                                is com.gorunjinian.metrovault.lib.qrtools.registry.UROutputDescriptor -> {
                                    decoded.source
                                }

                                else -> {
                                    android.util.Log.w("DescriptorQRScanner", "Unknown UR decoded type: ${decoded?.javaClass}")
                                    null
                                }
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DescriptorQRScanner", "Failed to decode UR result: ${e.message}")
                            null
                        }
                    } else null
                }
                "ur-bytes" -> {
                    // UR:BYTES contains raw bytes - decode to string (BSMS format)
                    val result = urDecoder?.result
                    if (result?.type == ResultType.SUCCESS) {
                        try {
                            val ur = result.ur
                            val bytes = ur.toBytes()
                            if (bytes != null) {
                                val content = String(bytes, Charsets.UTF_8)
                                android.util.Log.d("DescriptorQRScanner", "UR:BYTES decoded: ${content.take(100)}...")
                                content
                            } else {
                                android.util.Log.e("DescriptorQRScanner", "UR:BYTES toBytes() returned null")
                                null
                            }
                        } catch (e: Exception) {
                            android.util.Log.e("DescriptorQRScanner", "Failed to decode UR:BYTES: ${e.message}")
                            null
                        }
                    } else null
                }
                "bbqr" -> {
                    // Reassemble BBQr frames and decode
                    val frames = receivedFrames.entries.sortedBy { it.key }.map { it.value }
                    decodeBBQrToDescriptor(frames)
                }
                "plain", "unknown" -> singleFrameResult
                else -> null
            }
        }
        
        /**
         * Reconstructs descriptor string from CryptoOutput
         */
        private fun reconstructDescriptorFromCryptoOutput(
            output: com.gorunjinian.metrovault.lib.qrtools.registry.CryptoOutput
        ): String? {
            // Directly reconstruct from the parsed CryptoOutput object
            return reconstructFromCryptoOutput(output)
        }
        
        /**
         * Manual reconstruction from CryptoOutput
         */
        private fun reconstructFromCryptoOutput(
            output: com.gorunjinian.metrovault.lib.qrtools.registry.CryptoOutput
        ): String? {
            val expressions = output.scriptExpressions ?: return null
            val multiKey = output.multiKey
            
            if (multiKey != null) {
                val threshold = multiKey.threshold
                val hdKeys = multiKey.hdKeys ?: return null
                
                val isSorted = expressions.any { 
                    it == com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.SORTED_MULTISIG 
                }
                val multiFunc = if (isSorted) "sortedmulti" else "multi"
                
                val keyStrings = hdKeys.map { key -> formatHDKeyForDescriptor(key) }
                var result = "$multiFunc($threshold,${keyStrings.joinToString(",")})"
                
                // Wrap in script expressions
                for (expr in expressions.reversed()) {
                    result = when (expr) {
                        com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.WITNESS_SCRIPT_HASH -> "wsh($result)"
                        com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.SCRIPT_HASH -> "sh($result)"
                        com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.PUBLIC_KEY_HASH -> "pkh($result)"
                        com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.WITNESS_PUBLIC_KEY_HASH -> "wpkh($result)"
                        com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression.TAPROOT -> "tr($result)"
                        else -> result
                    }
                }
                return result
            }
            return null
        }
        
        private fun formatHDKeyForDescriptor(
            hdKey: com.gorunjinian.metrovault.lib.qrtools.registry.CryptoHDKey
        ): String {
            val sb = StringBuilder()

            val origin = hdKey.origin
            var originPath = ""
            if (origin != null) {
                val fingerprintBytes = origin.sourceFingerprint
                val fingerprint = if (fingerprintBytes != null && fingerprintBytes.size >= 4) {
                    fingerprintBytes.joinToString("") { String.format("%02x", it) }
                } else ""
                originPath = origin.path ?: ""
                if (fingerprint.isNotEmpty() || originPath.isNotEmpty()) {
                    sb.append("[$fingerprint/$originPath]")
                }
            }

            // Determine if testnet
            val isTestnet = hdKey.useInfo?.network ==
                com.gorunjinian.metrovault.lib.qrtools.registry.CryptoCoinInfo.Network.TESTNET

            val keyBytes = hdKey.key
            val chainCodeBytes = hdKey.chainCode ?: ByteArray(32)

            if (keyBytes != null && keyBytes.size == 33 && chainCodeBytes.size == 32) {
                try {
                    // Determine depth from origin path (count path components)
                    val depth = if (originPath.isEmpty()) 0 else originPath.split("/").filter { it.isNotEmpty() }.size

                    // Get parent fingerprint (use origin's source fingerprint or 0)
                    val parentFingerprint = origin?.sourceFingerprint?.let { fp ->
                        if (fp.size >= 4) {
                            ((fp[0].toLong() and 0xFF) shl 24) or
                            ((fp[1].toLong() and 0xFF) shl 16) or
                            ((fp[2].toLong() and 0xFF) shl 8) or
                            (fp[3].toLong() and 0xFF)
                        } else 0L
                    } ?: 0L

                    // Get child number from last path component
                    val childNumber = originPath.split("/").lastOrNull { it.isNotEmpty() }
                        ?.let { component ->
                        val cleaned = component.replace("'", "").replace("h", "")
                        val num = cleaned.toLongOrNull() ?: 0L
                        if (component.contains("'") || component.contains("h")) {
                            num + 0x80000000L
                        } else num
                    } ?: 0L

                    // Create ExtendedPublicKey and encode properly
                    val extPubKey = com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.ExtendedPublicKey(
                        publickeybytes = keyBytes.byteVector(),
                        chaincode = chainCodeBytes.byteVector32(),
                        depth = depth,
                        path = com.gorunjinian.metrovault.lib.bitcoin.KeyPath(listOf(childNumber)),
                        parent = parentFingerprint
                    )

                    // Encode with appropriate prefix (xpub/tpub for now, could extend to zpub/vpub)
                    val prefix = if (isTestnet) {
                        com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.tpub
                    } else {
                        com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.xpub
                    }
                    sb.append(extPubKey.encode(prefix))
                } catch (e: Exception) {
                    android.util.Log.e("DescriptorQRScanner", "Failed to encode xpub: ${e.message}")
                    // Fallback - shouldn't happen with valid keys
                    return ""
                }
            }

            // Children path (wildcard derivation suffix)
            val children = hdKey.children
            if (children != null) {
                val childPath = children.path
                if (!childPath.isNullOrEmpty()) {
                    if (!childPath.startsWith("/")) sb.append("/")
                    sb.append(childPath)
                }
            }

            return sb.toString()
        }
        
        /**
         * Decode BBQr frames to descriptor string
         */
        private fun decodeBBQrToDescriptor(frames: List<String>): String? {
            if (frames.isEmpty()) return null
            
            try {
                val encoding = frames[0][2]
                
                // Sort and extract data
                val sortedFrames = frames
                    .mapNotNull { frame ->
                        PSBTDecoder.parseBBQrFrame(frame)?.let { (part, _, raw) ->
                            part to raw.substring(8)
                        }
                    }
                    .sortedBy { it.first }
                    .map { it.second }
                
                val combinedData = sortedFrames.joinToString("")
                
                // Handle different BBQr encodings
                // U = Uncompressed UTF-8 (raw text, no encoding)
                // H = Hex encoded
                // 2 = Base32 encoded
                // Z = Zlib compressed + Base32 encoded
                return when (encoding) {
                    'U' -> combinedData  // Uncompressed UTF-8 - data is already the string
                    'H' -> decodeHex(combinedData)?.let { String(it, Charsets.UTF_8) }
                    '2' -> decodeBase32(combinedData)?.let { String(it, Charsets.UTF_8) }
                    'Z' -> decodeZlibBase32(combinedData)?.let { String(it, Charsets.UTF_8) }
                    else -> {
                        android.util.Log.w("DescriptorQRScanner", "Unknown BBQr encoding: $encoding")
                        null
                    }
                }
            } catch (e: Exception) {
                android.util.Log.e("DescriptorQRScanner", "BBQr decode error: ${e.message}")
                return null
            }
        }
        
        // Helper decode functions
        private fun decodeHex(hex: String): ByteArray? {
            return try {
                val clean = hex.replace(" ", "").replace("\n", "")
                if (clean.length % 2 != 0) return null
                ByteArray(clean.length / 2) { i ->
                    clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
                }
            } catch (_: Exception) { null }
        }
        
        private fun decodeBase32(input: String): ByteArray? {
            return try {
                val clean = input.uppercase().replace("=", "")
                val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
                val bits = StringBuilder()
                for (c in clean) {
                    val index = alphabet.indexOf(c)
                    if (index < 0) return null
                    bits.append(index.toString(2).padStart(5, '0'))
                }
                val bytes = mutableListOf<Byte>()
                var i = 0
                while (i + 8 <= bits.length) {
                    bytes.add(bits.substring(i, i + 8).toInt(2).toByte())
                    i += 8
                }
                bytes.toByteArray()
            } catch (_: Exception) { null }
        }
        
        private fun decodeZlibBase32(input: String): ByteArray? {
            val compressed = decodeBase32(input) ?: return null
            return try {
                val inflater = java.util.zip.Inflater(true)
                inflater.setInput(compressed)
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(1024)
                while (!inflater.finished()) {
                    val count = inflater.inflate(buffer)
                    if (count == 0 && inflater.needsInput()) break
                    output.write(buffer, 0, count)
                }
                inflater.end()
                output.toByteArray()
            } catch (_: Exception) { null }
        }
        
        /**
         * Reset scanner state
         */
        fun reset() {
            receivedFrames.clear()
            expectedTotal = null
            detectedFormat = null
            urDecoder = null
            urFrameCount = 0
            urEstimatedTotal = 0
            singleFrameResult = null
        }
        
        /**
         * Get progress string
         */
        fun getProgressString(): String {
            return when (detectedFormat) {
                "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> {
                    val progress = (urDecoder?.estimatedPercentComplete ?: 0.0) * 100
                    "${progress.toInt()}% BC-UR"
                }
                "bbqr" -> {
                    val total = expectedTotal ?: return "Waiting..."
                    "${receivedFrames.size}/$total BBQr"
                }
                else -> "Scanning..."
            }
        }
        
        fun getDetectedFormat(): String? = detectedFormat
    }
}