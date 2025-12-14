package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Bitmap
import android.graphics.Color
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import androidx.core.graphics.createBitmap
import androidx.core.graphics.set
import com.gorunjinian.metrovault.lib.bitcoin.PSBTDecoder
import com.gorunjinian.metrovault.lib.qrtools.registry.RegistryType
import kotlin.math.ceil

/**
 * Utilities for QR code generation including animated QR codes for large data.
 */
object QRCodeUtils {

    // Maximum bytes that can fit in a single QR code with error correction level M
    // This is conservative to ensure reliable scanning
    private const val MAX_QR_BYTES = 1800

    // Prefix for animated QR code frames
    private const val ANIMATED_PREFIX = "p"  // p[part]/[total] format

    /**
     * Generates QR code bitmap from text.
     * For large content, returns null - use generateAnimatedQRFrames instead.
     */
    fun generateQRCode(
        content: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M)
                put(EncodeHintType.MARGIN, 0)  // Minimal margin for larger QR display
                put(EncodeHintType.CHARACTER_SET, "UTF-8")
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap[x, y] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
                }
            }

            // Crop white margins to ensure QR fills the image
            cropQRWhiteMargins(bitmap, backgroundColor)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Crops white margins from a QR code bitmap to make the QR pattern fill the image.
     * This ensures consistent visual size across QR codes of different densities.
     */
    private fun cropQRWhiteMargins(bitmap: Bitmap, backgroundColor: Int): Bitmap {
        val width = bitmap.width
        val height = bitmap.height
        
        // Find the bounding box of the QR pattern (non-background pixels)
        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        
        for (y in 0 until height) {
            for (x in 0 until width) {
                if (bitmap.getPixel(x, y) != backgroundColor) {
                    if (x < minX) minX = x
                    if (y < minY) minY = y
                    if (x > maxX) maxX = x
                    if (y > maxY) maxY = y
                }
            }
        }
        
        // If no QR pattern found or margins are tiny, return original
        if (maxX <= minX || maxY <= minY || (minX < 5 && minY < 5)) {
            return bitmap
        }
        
        // Add a small margin (2 pixels) for better scanning
        val margin = 2
        minX = (minX - margin).coerceAtLeast(0)
        minY = (minY - margin).coerceAtLeast(0)
        maxX = (maxX + margin).coerceAtMost(width - 1)
        maxY = (maxY + margin).coerceAtMost(height - 1)
        
        // Crop to the QR pattern bounds
        val croppedWidth = maxX - minX + 1
        val croppedHeight = maxY - minY + 1
        
        return Bitmap.createBitmap(bitmap, minX, minY, croppedWidth, croppedHeight)
    }
    
    /**
     * Generates multiple QR codes for animated sequences.
     * Uses standard generation for reliability.
     */
    fun generateConsistentQRCodes(
        contents: List<String>,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): List<Bitmap>? {
        if (contents.isEmpty()) return null
        
        return try {
            // Generate all QR codes with standard settings
            contents.mapNotNull { content ->
                generateQRCode(content, size, foregroundColor, backgroundColor)
            }.takeIf { it.size == contents.size }
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Generates QR code for Bitcoin address with bitcoin: URI prefix
     */
    fun generateAddressQRCode(address: String, size: Int = 512): Bitmap? {
        return generateQRCode("bitcoin:$address", size)
    }

    /**
     * Generates QR code for PSBT.
     * For large PSBTs, returns null - use generatePSBTQRFrames instead.
     */
    fun generatePSBTQRCode(psbt: String, size: Int = 512): Bitmap? {
        // Check if PSBT fits in a single QR code
        if (psbt.length > MAX_QR_BYTES) {
            return null // Use animated QR instead
        }
        return generateQRCode(psbt, size)
    }

    /**
     * Check if content needs animated QR codes (multiple frames)
     */
    fun needsAnimatedQR(content: String): Boolean {
        return content.length > MAX_QR_BYTES
    }

    /**
     * Generates animated QR code frames for large PSBTs.
     * Uses a simple format: "p[part]/[total] [data]"
     *
     * @param psbt The full PSBT base64 string
     * @param size QR code size in pixels
     * @return List of bitmap frames, or null on error
     */
    fun generatePSBTQRFrames(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): List<Bitmap>? {
        return try {
            // Calculate chunk size accounting for header overhead
            // Header format: "p1/10 " = max 8 chars for parts up to 99
            val headerOverhead = 10
            val chunkSize = MAX_QR_BYTES - headerOverhead

            val chunks = splitIntoChunks(psbt, chunkSize)
            val totalParts = chunks.size

            // Build all frame contents first
            val frameContents = chunks.mapIndexed { index, chunk ->
                val partNumber = index + 1
                "$ANIMATED_PREFIX$partNumber/$totalParts $chunk"
            }
            
            // Generate with consistent density
            generateConsistentQRCodes(frameContents, size, foregroundColor, backgroundColor)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Alternative animated QR format using UR (Uniform Resources) style.
     * Format: "ur:bytes/[part]/[total]/[data]"
     * This is more compatible with some wallet implementations.
     */
    fun generateURQRFrames(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): List<Bitmap>? {
        return try {
            // UR format has more overhead
            val headerOverhead = 25
            val chunkSize = MAX_QR_BYTES - headerOverhead

            val chunks = splitIntoChunks(psbt, chunkSize)
            val totalParts = chunks.size

            // Build all frame contents first
            val frameContents = chunks.mapIndexed { index, chunk ->
                val partNumber = index + 1
                "ur:bytes/$partNumber-$totalParts/$chunk"
            }
            
            // Generate with consistent density
            generateConsistentQRCodes(frameContents, size, foregroundColor, backgroundColor)
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    /**
     * Output format options for signed PSBT QR codes
     */
    enum class OutputFormat(val displayName: String) {
        UR_PSBT("BC-UR"),     // Modern ur:psbt/ format - best interoperability
        BBQR("BBQr"),          // Coinkite BBQr format - best for Coldcard
        BASE64("Base64")       // Raw Base64 - fallback for basic wallets
    }
    
    /**
     * QR density options - controls trade-off between frame count and QR complexity
     * Low = simpler QR codes (easier to scan, more frames)
     * High = denser QR codes (harder to scan, fewer frames)
     */
    enum class QRDensity(val displayName: String, val fragmentBytes: Int) {
        LOW("Low", 250),       // Simpler QR codes, more frames (easier to scan)
        NORMAL("Normal", 350), // Balanced (default)
        HIGH("High", 500)      // Denser QR codes, fewer frames (harder to scan)
    }
    
    /**
     * Data class to hold animated QR code information
     */
    data class AnimatedQRResult(
        val frames: List<Bitmap>,
        val totalParts: Int,
        val isAnimated: Boolean,
        val recommendedFrameDelayMs: Long = 500,
        val format: OutputFormat = OutputFormat.UR_PSBT
    )

    /**
     * Smart QR code generation that automatically handles large PSBTs.
     * Returns either a single frame or animated frames as needed.
     * Default output is BC-UR format (ur:psbt/) for best interoperability.
     */
    fun generateSmartPSBTQR(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_PSBT,
        density: QRDensity = QRDensity.NORMAL
    ): AnimatedQRResult? {
        return when (format) {
            OutputFormat.UR_PSBT -> {
                // Try BC-UR first, fall back to BBQr if it fails
                val result = generateURPsbtQR(psbt, size, foregroundColor, backgroundColor, density)
                if (result != null) {
                    result
                } else {
                    android.util.Log.w("QRCodeUtils", "BC-UR returned null, falling back to BBQr")
                    generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
                }
            }
            OutputFormat.BBQR -> generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
            OutputFormat.BASE64 -> generateSmartPSBTQRRaw(psbt, size, foregroundColor, backgroundColor)
        }
    }
    
    /**
     * Generate QR code(s) in modern BC-UR format (ur:psbt/) using Hummingbird library.
     * Uses proper fountain codes for multi-frame encoding.
     */
    private fun generateURPsbtQR(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        density: QRDensity = QRDensity.NORMAL
    ): AnimatedQRResult? {
        return try {
            // Decode Base64 PSBT to bytes
            val psbtBytes = android.util.Base64.decode(psbt, android.util.Base64.NO_WRAP)
            
            // Create UR using "crypto-psbt" type for wider compatibility
            // (BlueWallet and some wallets prefer legacy type over modern "psbt")
            val ur = UR.fromBytes(RegistryType.CRYPTO_PSBT.toString(), psbtBytes)
            
            // Fragment length based on density setting
            val maxFragmentLen = density.fragmentBytes
            val minFragmentLen = 50
            
            // Create encoder with fountain code support
            val encoder = UREncoder(ur, maxFragmentLen, minFragmentLen, 0)
            
            if (encoder.isSinglePart) {
                // Single frame - simple case
                val urString = encoder.nextPart()
                android.util.Log.d("QRCodeUtils", "BC-UR single part: ${urString.length} chars, density=${density.displayName}")
                val bitmap = generateQRCode(urString.uppercase(), size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    android.util.Log.d("QRCodeUtils", "BC-UR single part: bitmap generated successfully")
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.UR_PSBT
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR single part: bitmap generation failed!")
                    null
                }
            } else {
                // Multi-frame with fountain codes
                val seqLen = encoder.seqLen
                android.util.Log.d("QRCodeUtils", "BC-UR multi-part: seqLen=$seqLen, density=${density.displayName}")
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
                    android.util.Log.d("QRCodeUtils", "BC-UR: Generated ${bitmaps.size} frames (seqLen=$seqLen, ${psbtBytes.size} bytes)")
                    AnimatedQRResult(
                        frames = bitmaps,
                        totalParts = bitmaps.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (bitmaps.size > 5) 600 else 500,
                        format = OutputFormat.UR_PSBT
                    )
                } else {
                    android.util.Log.e("QRCodeUtils", "BC-UR multi-part: bitmap generation failed!")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("QRCodeUtils", "BC-UR encoding failed: ${e.message}")
            e.printStackTrace()
            // Fall back to BBQr on error with same density
            generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor, density)
        }
    }
    
    /**
     * Generate QR code(s) in BBQr format.
     */
    private fun generateBBQrPSBT(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        density: QRDensity = QRDensity.NORMAL
    ): AnimatedQRResult? {
        return try {
            // Map density to max QR capacity - higher density = smaller chunks = more frames
            // BBQr uses Base32, so multiply bytes by 1.6 for character count
            val maxQrChars = when (density) {
                QRDensity.LOW -> 2000    // Fewer, denser QR codes
                QRDensity.NORMAL -> 1500 // Balanced
                QRDensity.HIGH -> 1000   // More, simpler QR codes
            }
            val bbqrFrames = PSBTDecoder.encodeToBBQr(psbt, maxQrChars)
            if (bbqrFrames == null || bbqrFrames.isEmpty()) {
                android.util.Log.w("QRCodeUtils", "BBQr encoding failed")
                return null
            }
            
            // Use consistent density for all frames
            val bitmaps = if (bbqrFrames.size > 1) {
                generateConsistentQRCodes(bbqrFrames, size, foregroundColor, backgroundColor)
            } else {
                bbqrFrames.mapNotNull { frame ->
                    generateQRCode(frame, size, foregroundColor, backgroundColor)
                }
            }
            
            if (bitmaps != null && bitmaps.size == bbqrFrames.size) {
                AnimatedQRResult(
                    frames = bitmaps,
                    totalParts = bitmaps.size,
                    isAnimated = bitmaps.size > 1,
                    recommendedFrameDelayMs = if (bitmaps.size > 5) 500 else 400,
                    format = OutputFormat.BBQR
                )
            } else null
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Fallback to raw Base64 format.
     */
    private fun generateSmartPSBTQRRaw(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): AnimatedQRResult? {
        return try {
            if (!needsAnimatedQR(psbt)) {
                val bitmap = generateQRCode(psbt, size, foregroundColor, backgroundColor)
                if (bitmap != null) {
                    AnimatedQRResult(
                        frames = listOf(bitmap),
                        totalParts = 1,
                        isAnimated = false,
                        format = OutputFormat.BASE64
                    )
                } else null
            } else {
                val frames = generatePSBTQRFrames(psbt, size, foregroundColor, backgroundColor)
                if (frames != null && frames.isNotEmpty()) {
                    AnimatedQRResult(
                        frames = frames,
                        totalParts = frames.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = if (frames.size > 5) 600 else 500,
                        format = OutputFormat.BASE64
                    )
                } else null
            }
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Generate animated QR frames for BC-UR ur:psbt/ format.
     * Uses part markers: ur:psbt/1-5/[data_chunk]
     */
    private fun generateURPsbtFrames(
        urPsbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): List<Bitmap>? {
        return try {
            // Extract the data portion (after ur:psbt/)
            val prefix = "ur:psbt/"
            if (!urPsbt.lowercase().startsWith(prefix)) return null
            val data = urPsbt.substring(prefix.length)
            
            // Calculate chunk size accounting for header overhead
            // Format: ur:psbt/1-10/[data] = max ~15 chars overhead
            val headerOverhead = 20
            val chunkSize = MAX_QR_BYTES - headerOverhead

            val chunks = splitIntoChunks(data, chunkSize)
            val totalParts = chunks.size

            // Build all frame contents first
            val frameContents = chunks.mapIndexed { index, chunk ->
                val partNumber = index + 1
                "$prefix$partNumber-$totalParts/$chunk"
            }
            
            // Generate with consistent density
            generateConsistentQRCodes(frameContents, size, foregroundColor, backgroundColor)
        } catch (e: Exception) {
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
     * Reassembles data from multiple animated QR frames.
     * Frames can be provided in any order.
     *
     * @param frames List of scanned frame contents
     * @return The reassembled data, or null if incomplete/invalid
     */
    fun reassembleAnimatedFrames(frames: List<String>): String? {
        return try {
            if (frames.isEmpty()) return null

            val parsedFrames = frames.mapNotNull { parseAnimatedFrame(it) }
            if (parsedFrames.isEmpty()) {
                // Not animated format, return single frame content
                return if (frames.size == 1) frames[0] else null
            }

            val totalParts = parsedFrames.first().second

            // Verify all frames agree on total parts
            if (parsedFrames.any { it.second != totalParts }) return null

            // Check we have all parts
            val partNumbers = parsedFrames.map { it.first }.toSet()
            if (partNumbers.size != totalParts) return null
            if (partNumbers != (1..totalParts).toSet()) return null

            // Sort by part number and concatenate data
            parsedFrames
                .sortedBy { it.first }
                .joinToString("") { it.third }
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
        private var urDecoder: com.gorunjinian.metrovault.lib.qrtools.URDecoder? = null
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
                    urDecoder = com.gorunjinian.metrovault.lib.qrtools.URDecoder()
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
                    return result.type == com.gorunjinian.metrovault.lib.qrtools.ResultType.SUCCESS
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
                    if (result?.type == com.gorunjinian.metrovault.lib.qrtools.ResultType.SUCCESS) {
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
         * Get missing part numbers (only for non-UR formats)
         */
        fun getMissingParts(): List<Int> {
            if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
                return emptyList() // UR fountain codes don't have sequential parts
            }
            val total = expectedTotal ?: return emptyList()
            return (1..total).filter { it !in receivedFrames.keys }
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

    // ==================== Private Helpers ====================

    private fun splitIntoChunks(data: String, chunkSize: Int): List<String> {
        val numChunks = ceil(data.length.toDouble() / chunkSize).toInt()
        return (0 until numChunks).map { i ->
            val start = i * chunkSize
            val end = minOf(start + chunkSize, data.length)
            data.substring(start, end)
        }
    }
}