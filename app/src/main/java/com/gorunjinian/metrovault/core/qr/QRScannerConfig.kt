package com.gorunjinian.metrovault.core.qr

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
import com.google.zxing.Result
import com.google.zxing.ResultMetadataType
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.journeyapps.barcodescanner.DefaultDecoderFactory

/**
 * Configures a CompoundBarcodeView for optimal QR code scanning.
 *
 * - Restricts to QR_CODE format only (skips UPC/EAN/Code128/etc.)
 * - Enables TRY_HARDER for better low-contrast recognition
 * - Enables inverted scanning so light-on-dark QR codes can be decoded
 */
fun CompoundBarcodeView.configureForQRScanning() {
    barcodeView.decoderFactory = DefaultDecoderFactory(
        listOf(BarcodeFormat.QR_CODE),
        mapOf(DecodeHintType.TRY_HARDER to true),
        null,
        2  // 0=normal only, 1=inverted only, 2=try both
    )
}

/**
 * The bytes of a QR that holds exactly one byte-mode segment, as they were encoded; null for any
 * other QR (numeric, alphanumeric, several byte segments).
 *
 * Not [Result.getRawBytes]: that is the code's whole data region, with segment headers and padding
 * around the content, so its length says nothing about the content's.
 */
fun Result.byteSegment(): ByteArray? =
    (resultMetadata?.get(ResultMetadataType.BYTE_SEGMENTS) as? List<*>)?.singleOrNull() as? ByteArray
