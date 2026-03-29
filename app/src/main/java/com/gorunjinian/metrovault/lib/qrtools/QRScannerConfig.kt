package com.gorunjinian.metrovault.lib.qrtools

import com.google.zxing.BarcodeFormat
import com.google.zxing.DecodeHintType
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
