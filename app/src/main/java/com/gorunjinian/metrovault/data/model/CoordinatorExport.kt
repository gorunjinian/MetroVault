package com.gorunjinian.metrovault.data.model

/** Public-only material for importing one active single-signature account into a coordinator. */
data class CoordinatorExportData(
    val walletName: String,
    val networkName: String,
    val addressType: String,
    val accountNumber: Int,
    val derivationPath: String,
    val masterFingerprint: String,
    val standardAccountXpub: String,
    val slip132AccountXpub: String,
    val descriptor: String,
    val firstReceiveAddress: String,
    val nunchukSignerRecord: String,
    val nunchukJson: String
)

sealed interface CoordinatorExportResult {
    data class Available(val data: CoordinatorExportData) : CoordinatorExportResult
    data class Unsupported(val message: String) : CoordinatorExportResult
    data class Error(val message: String) : CoordinatorExportResult
}
