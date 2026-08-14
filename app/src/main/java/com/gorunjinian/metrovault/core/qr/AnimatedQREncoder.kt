package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.UREncoder
import com.gorunjinian.metrovault.core.logging.AppLog

/**
 * Shared encode paths for text/JSON export payloads: BBQr frames and BC-UR fountain frames,
 * both rendered to [AnimatedQRResult]. [DescriptorQREncoder] and [CoordinatorQREncoder] are
 * thin façades over this. The PSBT encoders keep their own paths — they add density selection
 * and scanned-format matching this deliberately does not.
 */
internal object AnimatedQREncoder {

    private const val QR_SIZE = 512

    fun encodeBBQr(data: ByteArray, fileType: FileType, tag: String): AnimatedQRResult? {
        return try {
            val options = DensitySettings.getBBQrSplitOptions(QRDensity.LOW)
            val splitResult = SplitResult.fromData(data, fileType, options)
            val frameContents = splitResult.parts
            AppLog.d(tag) { "BBQr: ${data.size} bytes, ${frameContents.size} frames (version=${splitResult.version})" }

            val bitmaps = if (frameContents.size > 1) {
                QRCodeGenerator.generateConsistentQRCodes(frameContents, size = QR_SIZE)
            } else {
                frameContents.mapNotNull { QRCodeGenerator.generateQRCode(it, size = QR_SIZE) }
            }

            bitmaps?.takeIf { it.isNotEmpty() }?.let {
                AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = it.size > 1,
                    recommendedFrameDelayMs = 500,
                    format = OutputFormat.BBQR
                )
            }
        } catch (e: Exception) {
            AppLog.e(tag) { "BBQr generation failed: ${e.message}" }
            null
        }
    }

    /**
     * [fallbackText], when non-null, is rendered as a plain single QR if UR encoding fails —
     * the descriptor export's historical behavior. Null propagates the failure as null instead.
     */
    fun encodeUR(
        format: OutputFormat,
        tag: String,
        fallbackText: String? = null,
        buildUr: () -> UR
    ): AnimatedQRResult? {
        return try {
            val encoder = UREncoder(buildUr(), 250, 50, 0)

            if (encoder.isSinglePart()) {
                QRCodeGenerator.generateQRCode(encoder.nextPart().uppercase(), size = QR_SIZE)?.let {
                    AnimatedQRResult(
                        frames = listOf(it),
                        totalParts = 1,
                        isAnimated = false,
                        format = format
                    )
                }
            } else {
                val frameStrings = mutableListOf<String>()
                repeat(encoder.seqLen) {
                    frameStrings.add(encoder.nextPart().uppercase())
                }
                QRCodeGenerator.generateConsistentQRCodes(frameStrings, size = QR_SIZE)?.let {
                    AnimatedQRResult(
                        frames = it,
                        totalParts = it.size,
                        isAnimated = true,
                        recommendedFrameDelayMs = 500,
                        format = format
                    )
                }
            }
        } catch (e: Exception) {
            AppLog.e(tag) { "${format.displayName} generation failed: ${e.message}" }
            fallbackText?.let { text ->
                QRCodeGenerator.generateQRCode(text, size = QR_SIZE)?.let {
                    AnimatedQRResult(
                        frames = listOf(it),
                        totalParts = 1,
                        isAnimated = false,
                        format = format
                    )
                }
            }
        }
    }
}
