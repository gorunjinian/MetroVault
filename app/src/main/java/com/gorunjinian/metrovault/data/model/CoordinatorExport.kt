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
    val coldcardJson: String
)

/**
 * Result type for building a coordinator export.
 *
 * [Unsupported] means the wallet is fine but this export does not apply to it, so the UI points
 * at the export that does. [Error] means the export should have been possible and was not.
 */
sealed class CoordinatorExportResult {
    data class Available(val data: CoordinatorExportData) : CoordinatorExportResult()
    data class Unsupported(val reason: CoordinatorExportError) : CoordinatorExportResult()
    data class Error(val reason: CoordinatorExportError) : CoordinatorExportResult()
}

/** Enumeration of coordinator export failures with user-friendly messages. */
enum class CoordinatorExportError(val message: String) {
    MULTISIG_WALLET("Coordinator export currently supports single-signature wallets only. Use the existing multisig export instead."),
    SILENT_PAYMENT_WALLET("Silent Payments wallets are not supported by this coordinator export. Use the existing Silent Payments export instead."),
    UNSUPPORTED_DERIVATION_PATH("This wallet type or derivation path is not supported. Supported single-signature paths are BIP44, BIP49, BIP84, and BIP86."),
    WALLET_NOT_LOADED("The active wallet is not loaded."),
    ACCOUNT_KEY_UNAVAILABLE("The active account public key is unavailable."),
    BUILD_FAILED("Could not build a safe public coordinator export.")
}
