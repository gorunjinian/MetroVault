package com.gorunjinian.metrovault.lib.qrtools

import com.gorunjinian.metrovault.lib.bitcoin.PSBTDecoder

/**
 * Helper class to track animated QR scanning progress for PSBTs.
 * Supports multiple formats: simple p1/10, ur:bytes/, BBQr (B$...), and ur:crypto-psbt/
 * 
 * For UR formats (ur:psbt/, ur:crypto-psbt/), uses Hummingbird's URDecoder
 * which properly handles fountain code reconstruction.
 */
@Suppress("PrivatePropertyName")
class AnimatedQRScanner {
    // For non-UR formats (BBQr, simple)
    private val receivedFrames = mutableMapOf<Int, String>()
    private var expectedTotal: Int? = null
    private var detectedFormat: String? = null  // "simple", "ur", "bbqr", "ur-psbt"
    
    // For UR formats - use local URTools library which handles fountain codes
    private var urDecoder: URDecoder? = null
    private var urFrameCount: Int = 0
    private var urEstimatedTotal: Int = 0

    // Prefix for animated QR code frames
    private val ANIMATED_PREFIX = "p"  // p[part]/[total] format

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
     * Parses animated QR frame to extract part info.
     * Returns (partNumber, totalParts, data) or null if not animated format.
     */
    private fun parseAnimatedFrame(content: String): Triple<Int, Int, String>? {
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
