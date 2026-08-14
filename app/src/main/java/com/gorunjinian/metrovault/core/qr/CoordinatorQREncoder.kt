package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bcur.UR

/**
 * QR encoding options for the combined coordinator JSON export.
 */
enum class CoordinatorQrEncoding(val displayName: String) {
    BBQR("BBQr"),
    UR("BC-UR")
}

/**
 * Encodes the combined Coldcard Generic JSON coordinator export into single-frame or animated
 * QR codes. The combined payload (all single-sig sections plus BIP48 multisig keys) is far too
 * large for one scannable QR, so it is carried as BBQr `J` (JSON) frames or BC-UR `ur:bytes`
 * fountain frames — the encodings Sparrow and Coldcard already speak for large payloads.
 */
object CoordinatorQREncoder {

    private const val TAG = "CoordinatorQREncoder"

    fun encode(json: String, encoding: CoordinatorQrEncoding): AnimatedQRResult? {
        return when (encoding) {
            CoordinatorQrEncoding.BBQR -> AnimatedQREncoder.encodeBBQr(
                json.toByteArray(Charsets.UTF_8), FileType.Json, TAG
            )
            CoordinatorQrEncoding.UR -> AnimatedQREncoder.encodeUR(OutputFormat.UR_MODERN, TAG) {
                UR.fromBytes(json.toByteArray(Charsets.UTF_8))
            }
        }
    }
}
