package com.gorunjinian.metrovault.data.model

/**
 * Result type for wallet creation operations.
 */
sealed class WalletCreationResult {
    data class Success(val metadata: WalletMetadata) : WalletCreationResult()
    data class Error(val reason: WalletCreationError) : WalletCreationResult()
}

/**
 * Enumeration of wallet creation error types with user-friendly messages.
 */
enum class WalletCreationError(val message: String) {
    NOT_AUTHENTICATED("Session not authenticated. Please unlock the app first."),
    MAX_WALLETS_REACHED("Maximum number of wallets reached. Delete a wallet to create a new one."),
    INVALID_MNEMONIC("Invalid mnemonic phrase. Please check and try again."),
    WALLET_CREATION_FAILED("Failed to create wallet from mnemonic."),
    STORAGE_FAILED("Failed to save wallet to secure storage."),
    UNKNOWN_ERROR("An unexpected error occurred.")
}
