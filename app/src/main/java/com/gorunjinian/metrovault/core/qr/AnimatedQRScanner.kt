package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bbqr.ContinuousJoinResult
import com.gorunjinian.bbqr.ContinuousJoiner
import com.gorunjinian.bcur.ResultType
import com.gorunjinian.bcur.URDecoder

/**
 * Helper class to track animated QR scanning progress for PSBTs.
 * Supports multiple formats: simple p1/10, ur:bytes/, BBQr (B$...), and ur:crypto-psbt/
 *
 * For UR formats (ur:psbt/, ur:crypto-psbt/), uses bcur-kotlin's URDecoder
 * which properly handles fountain code reconstruction.
 * For BBQr formats, uses bbqr-kotlin's ContinuousJoiner for streaming decoding.
 *
 * @param decodeSingleFrame Optional function to decode a single-frame PSBT string
 *   (e.g., Base64, hex) into a normalized Base64 PSBT. Callers should pass
 *   PSBTDecoder::decode to enable single/simple frame PSBT decoding.
 */
@Suppress("PrivatePropertyName")
class AnimatedQRScanner(
    private val decodeSingleFrame: (String) -> String? = { null }
) {
    // For simple format only
    private val receivedFrames = mutableMapOf<Int, String>()
    private var expectedTotal: Int? = null
    private var detectedFormat: String? = null  // "simple", "ur", "bbqr", "ur-psbt"

    // For UR formats - bcur-kotlin library handles fountain codes
    private var urDecoder: URDecoder? = null
    private var urFrameCount: Int = 0
    private var urEstimatedTotal: Int = 0

    // For BBQr formats - bbqr-kotlin library handles streaming decoding
    private var bbqrJoiner: ContinuousJoiner? = null
    private var bbqrTotalParts: Int = 0
    private var bbqrReceivedParts: Int = 0

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

        // Handle UR formats with URDecoder (fountain code support)
        if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
            return processURFrame(content)
        }

        // Handle non-UR formats
        return processNonURFrame(content)
    }

    /**
     * Process UR format frames using bcur-kotlin URDecoder
     */
    private fun processURFrame(content: String): Int? {
        try {
            if (urDecoder == null) {
                urDecoder = URDecoder()
                android.util.Log.d("AnimatedQRScanner", "Initialized URDecoder for fountain codes")
            }

            val decoder = urDecoder!!
            decoder.receivePart(content)
            urFrameCount++

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
        return when (detectedFormat) {
            "bbqr" -> processBBQrFrame(content)
            "simple" -> {
                val parsed = parseAnimatedFrame(content) ?: return null
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
                ((receivedFrames.size * 100) / totalParts)
            }
            "single" -> {
                receivedFrames[1] = content
                expectedTotal = 1
                100
            }
            else -> null
        }
    }

    /**
     * Process BBQr frame using ContinuousJoiner from bbqr-kotlin
     */
    private fun processBBQrFrame(content: String): Int? {
        try {
            if (bbqrJoiner == null) {
                bbqrJoiner = ContinuousJoiner()
            }

            val result = bbqrJoiner!!.addPart(content)

            return when (result) {
                is ContinuousJoinResult.NotStarted -> {
                    android.util.Log.d("AnimatedQRScanner", "BBQr: not started yet")
                    0
                }
                is ContinuousJoinResult.InProgress -> {
                    bbqrReceivedParts++
                    if (bbqrTotalParts == 0) {
                        bbqrTotalParts = bbqrReceivedParts + result.partsLeft
                    }
                    val progress = ((bbqrTotalParts - result.partsLeft) * 100) / bbqrTotalParts
                    android.util.Log.d("AnimatedQRScanner", "BBQr: ${result.partsLeft} parts left, progress: $progress%")
                    progress
                }
                is ContinuousJoinResult.Complete -> {
                    bbqrCompleteData = result.joined.data
                    android.util.Log.d("AnimatedQRScanner", "BBQr: complete, ${bbqrCompleteData!!.size} bytes")
                    100
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("AnimatedQRScanner", "Error processing BBQr frame: ${e.message}")
            return null
        }
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

                    val header = content.substring(1, spaceIndex)
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

        // For BBQr, check if we have the complete data
        if (detectedFormat == "bbqr") {
            return bbqrCompleteData != null
        }

        // For non-UR/BBQr formats
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
                val result = urDecoder?.result
                if (result?.type == ResultType.SUCCESS) {
                    try {
                        val ur = result.ur!!
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
                bbqrCompleteData?.let { bytes ->
                    android.util.Log.d("AnimatedQRScanner", "BBQr result: ${bytes.size} bytes")
                    android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                }
            }
            "single" -> {
                decodeSingleFrame(receivedFrames[1] ?: return null)
            }
            else -> {
                val frames = receivedFrames.entries.sortedBy { it.key }.map { it.value }
                val combined = frames.joinToString("")
                decodeSingleFrame(combined) ?: combined
            }
        }
    }

    // Store BBQr complete data when joiner completes
    private var bbqrCompleteData: ByteArray? = null

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
        bbqrJoiner = null
        bbqrTotalParts = 0
        bbqrReceivedParts = 0
        bbqrCompleteData = null
    }

    /**
     * Get progress as human-readable string
     */
    fun getProgressString(): String {
        if (detectedFormat == "ur-psbt" || detectedFormat == "ur") {
            val progress = (urDecoder?.estimatedPercentComplete ?: 0.0) * 100
            return "${progress.toInt()}% BC-UR"
        }
        if (detectedFormat == "bbqr") {
            return if (bbqrTotalParts > 0) {
                "${bbqrTotalParts - (bbqrTotalParts - bbqrReceivedParts).coerceAtLeast(0)}/$bbqrTotalParts BBQr parts"
            } else {
                "Scanning BBQr..."
            }
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
