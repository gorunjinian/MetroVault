package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Bitmap

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
internal object DensitySettings {
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
