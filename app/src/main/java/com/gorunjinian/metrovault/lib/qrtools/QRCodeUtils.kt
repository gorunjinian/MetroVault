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

    // Maximum bytes that can fit in a single QR code for easy scanning
    // Reduced to 500 to ensure consistent, easily scannable QR codes across all frames
    private const val MAX_QR_BYTES = 500

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
     * Generates QR code bitmap from binary data (byte array).
     * Uses ISO_8859_1 charset for true binary encoding, as required by CompactSeedQR.
     * Uses "L" (Low) error correction for smallest possible QR code.
     * 
     * @param bytes Binary data to encode (e.g., 16 or 32 bytes for CompactSeedQR)
     * @param size QR code size in pixels
     * @return Bitmap of the QR code, or null on error
     */
    fun generateBinaryQRCode(
        bytes: ByteArray,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? {
        return try {
            // Convert bytes to ISO-8859-1 string (1:1 byte mapping)
            val content = String(bytes, Charsets.ISO_8859_1)
            
            val hints = hashMapOf<EncodeHintType, Any>().apply {
                // Use "L" (Low) error correction as specified in SeedQR spec for smallest QR
                put(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.L)
                put(EncodeHintType.MARGIN, 0)
                // ISO-8859-1 is essential for binary data - maps bytes 0-255 to chars 1:1
                put(EncodeHintType.CHARACTER_SET, "ISO-8859-1")
            }

            val writer = QRCodeWriter()
            val bitMatrix = writer.encode(content, BarcodeFormat.QR_CODE, size, size, hints)

            val bitmap = createBitmap(size, size, Bitmap.Config.RGB_565)
            for (x in 0 until size) {
                for (y in 0 until size) {
                    bitmap[x, y] = if (bitMatrix[x, y]) foregroundColor else backgroundColor
                }
            }

            cropQRWhiteMargins(bitmap, backgroundColor)
        } catch (e: Exception) {
            android.util.Log.e("QRCodeUtils", "generateBinaryQRCode failed: ${e.message}")
            e.printStackTrace()
            null
        }
    }
    
    /**
     * Crops white margins from a QR code bitmap to make the QR pattern fill the image,
     * then adds a consistent border for reliable scanning.
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
        
        // If no QR pattern found, return original
        if (maxX <= minX || maxY <= minY) {
            return bitmap
        }
        
        // First, crop tightly to the QR content
        val contentWidth = maxX - minX + 1
        val contentHeight = maxY - minY + 1
        val croppedBitmap = Bitmap.createBitmap(bitmap, minX, minY, contentWidth, contentHeight)
        
        // Then add a consistent white border (16 pixels on each side)
        // This ensures the border is always present regardless of QR density
        val margin = 16
        val paddedWidth = contentWidth + (margin * 2)
        val paddedHeight = contentHeight + (margin * 2)
        
        val paddedBitmap = createBitmap(paddedWidth, paddedHeight, Bitmap.Config.RGB_565)
        // Fill with background color (white)
        for (x in 0 until paddedWidth) {
            for (y in 0 until paddedHeight) {
                paddedBitmap[x, y] = backgroundColor
            }
        }
        
        // Draw the cropped QR content centered with margin
        val canvas = android.graphics.Canvas(paddedBitmap)
        canvas.drawBitmap(croppedBitmap, margin.toFloat(), margin.toFloat(), null)
        
        return paddedBitmap
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
        UR_LEGACY("BC-UR v1"),  // Legacy ur:crypto-psbt/ format - BlueWallet, Sparrow compatibility
        BBQR("BBQr"),           // Coinkite BBQr format - best for Coldcard
        UR_MODERN("BC-UR v2")   // Modern ur:psbt/ format - UR 2.0 standard
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
     * Uses optimal density for easy scanning when multi-frame is needed.
     */
    fun generateSmartPSBTQR(
        psbt: String,
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_LEGACY
    ): AnimatedQRResult? {
        return when (format) {
            OutputFormat.UR_LEGACY -> {
                // Try BC-UR v1 (crypto-psbt) first, fall back to BBQr if it fails
                val result = generateURPsbtQRv1(psbt, size, foregroundColor, backgroundColor)
                if (result != null) {
                    result
                } else {
                    android.util.Log.w("QRCodeUtils", "BC-UR v1 returned null, falling back to BBQr")
                    generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor)
                }
            }
            OutputFormat.BBQR -> generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor)
            OutputFormat.UR_MODERN -> {
                // Try BC-UR v2 (psbt) first, fall back to BBQr if it fails
                val result = generateURPsbtQRv2(psbt, size, foregroundColor, backgroundColor)
                if (result != null) {
                    result
                } else {
                    android.util.Log.w("QRCodeUtils", "BC-UR v2 returned null, falling back to BBQr")
                    generateBBQrPSBT(psbt, size, foregroundColor, backgroundColor)
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
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): AnimatedQRResult? {
        return try {
            // Decode Base64 PSBT to bytes
            val psbtBytes = android.util.Base64.decode(psbt, android.util.Base64.NO_WRAP)
            
            // Create UR using "crypto-psbt" type (BC-UR v1) for legacy wallet compatibility
            val ur = UR.fromBytes(RegistryType.CRYPTO_PSBT.toString(), psbtBytes)
            
            // Use optimal fragment length for easy scanning (smaller = more frames but easier to scan)
            val maxFragmentLen = 250
            val minFragmentLen = 50
            
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
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): AnimatedQRResult? {
        return try {
            // Decode Base64 PSBT to bytes
            val psbtBytes = android.util.Base64.decode(psbt, android.util.Base64.NO_WRAP)
            
            // Create UR using "psbt" type (BC-UR v2) for modern UR 2.0 standard
            val ur = UR.fromBytes(RegistryType.PSBT.toString(), psbtBytes)
            
            // Use optimal fragment length for easy scanning (smaller = more frames but easier to scan)
            val maxFragmentLen = 250
            val minFragmentLen = 50
            
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
        size: Int = 512,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): AnimatedQRResult? {
        return try {
            android.util.Log.d("QRCodeUtils", "BBQr generation starting, PSBT length: ${psbt.length}")
            // Use lower QR capacity for easy scanning (more frames but each frame is easily scannable)
            // Reduced to 500 to prevent overly dense QR codes
            val maxQrChars = 500
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
                        format = OutputFormat.UR_LEGACY
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
                        format = OutputFormat.UR_LEGACY
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