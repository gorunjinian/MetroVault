package com.gorunjinian.metrovault.lib.qrtools

import android.graphics.Bitmap
import android.graphics.Color
import com.gorunjinian.metrovault.domain.service.psbt.PSBTQREncoder

/**
 * Facade for QR code utilities - delegates to specialized modules.
 * 
 * This object provides backward-compatible access to all QR code functionality.
 * For new code, consider using the specialized classes directly:
 * - [QRCodeGenerator] - Basic QR bitmap generation
 * - [com.gorunjinian.metrovault.domain.service.psbt.PSBTQREncoder] - PSBT encoding to QR formats
 * - [TransactionQREncoder] - Raw transaction encoding
 * - [AnimatedQRScanner] - Scanning animated PSBT QRs
 * - [DescriptorQRScanner] - Scanning descriptor QRs
 */
object QRCodeUtils {

    // ==================== Basic QR Generation (delegates to QRCodeGenerator) ====================

    /**
     * Generates QR code bitmap from text.
     */
    fun generateQRCode(
        content: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? = QRCodeGenerator.generateQRCode(content, size, foregroundColor, backgroundColor)
    
    /**
     * Generates QR code for binary data using ISO-8859-1 encoding.
     */
    fun generateBinaryQRCode(
        bytes: ByteArray,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE
    ): Bitmap? = QRCodeGenerator.generateBinaryQRCode(bytes, size, foregroundColor, backgroundColor)

    /**
     * Generates QR code for Bitcoin address with bitcoin: URI prefix
     */
    fun generateAddressQRCode(address: String, size: Int = 768): Bitmap? = 
        QRCodeGenerator.generateAddressQRCode(address, size)

    // ==================== PSBT Encoding (delegates to PSBTQREncoder) ====================

    /**
     * Smart QR code generation that automatically handles large PSBTs.
     */
    fun generateSmartPSBTQR(
        psbt: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_LEGACY,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? = PSBTQREncoder.generateSmartPSBTQR(
        psbt, size, foregroundColor, backgroundColor, format, density
    )

    // ==================== Transaction Encoding (delegates to TransactionQREncoder) ====================

    /**
     * Generates QR code(s) for a raw finalized transaction hex.
     */
    fun generateRawTxQR(
        txHex: String,
        size: Int = 768,
        foregroundColor: Int = Color.BLACK,
        backgroundColor: Int = Color.WHITE,
        format: OutputFormat = OutputFormat.UR_LEGACY,
        density: QRDensity = QRDensity.MEDIUM
    ): AnimatedQRResult? = TransactionQREncoder.generateRawTxQR(
        txHex, size, foregroundColor, backgroundColor, format, density
    )
}