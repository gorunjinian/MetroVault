package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bcur.UR
import com.gorunjinian.bcur.registry.UROutputDescriptor

/**
 * Content format options for multisig descriptor export.
 */
enum class ContentFormat(val displayName: String) {
    DESCRIPTOR("Descriptor"),
    BSMS("BSMS")
}

/**
 * Encodes multisig wallet descriptors (raw or BSMS-formatted) into single-frame
 * or animated QR codes. Counterpart to [DescriptorQRScanner].
 *
 * Supported QR encodings:
 *  - BC-UR v1: `ur:bytes/` for broad compatibility with legacy wallets
 *  - BBQr: multi-frame BBQr with low density
 *  - BC-UR v2: `ur:output-descriptor/` for raw descriptors (UR 2.0 standard),
 *    `ur:bytes/` for BSMS multi-line text
 */
object DescriptorQREncoder {

    private const val TAG = "DescriptorQREncoder"

    fun encode(
        content: String,
        format: OutputFormat,
        contentFormat: ContentFormat
    ): AnimatedQRResult? {
        return when (format) {
            OutputFormat.UR_LEGACY -> AnimatedQREncoder.encodeUR(format, TAG, fallbackText = content) {
                UR.fromBytes(content.toByteArray(Charsets.UTF_8))
            }
            OutputFormat.BBQR -> AnimatedQREncoder.encodeBBQr(
                content.toByteArray(Charsets.UTF_8), FileType.UnicodeText, TAG
            )
            OutputFormat.UR_MODERN -> AnimatedQREncoder.encodeUR(format, TAG, fallbackText = content) {
                when (contentFormat) {
                    // UROutputDescriptor encodes as CBOR map with SOURCE key
                    ContentFormat.DESCRIPTOR -> UROutputDescriptor(content).toUR()
                    ContentFormat.BSMS -> UR.fromBytes(content.toByteArray(Charsets.UTF_8))
                }
            }
        }
    }
}
