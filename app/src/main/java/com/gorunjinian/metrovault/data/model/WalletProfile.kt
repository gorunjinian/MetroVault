package com.gorunjinian.metrovault.data.model

/** What a wallet derives. How it is held (stored or memory-only) is [WalletProfile.isStateless]. */
enum class WalletKind { SINGLE_SIG, SILENT_PAYMENT, MULTISIG }

/** A wallet feature that only some wallets offer. [WalletProfile.supports] decides which. */
enum class WalletFeature {
    VIEW_ADDRESSES,
    SIGN_PSBT,
    CHECK_ADDRESS,
    EXPORT,
    SIGN_MESSAGE,
    BIP85,
    DIFFERENT_ACCOUNTS,
    CHANGE_ADDRESS_TYPE,
    DELETE,
    COORDINATOR_EXPORT,
    ACCOUNT_KEYS_AND_DESCRIPTORS,
    SEED_PHRASE,
}

/**
 * The one answer to "what can this wallet do?". Screens, shortcuts and navigation ask [supports]
 * rather than combining multisig, silent-payment and stateless checks themselves, so a new wallet
 * kind or feature is decided here once.
 *
 * A stateless wallet can be single-sig or silent-payment, never multisig.
 */
data class WalletProfile(val kind: WalletKind, val isStateless: Boolean) {

    fun supports(feature: WalletFeature): Boolean = when (feature) {
        WalletFeature.VIEW_ADDRESSES, WalletFeature.SIGN_PSBT, WalletFeature.EXPORT -> true
        // An SP wallet has no address chain to scan: each received output is tweaked per payment
        WalletFeature.CHECK_ADDRESS -> kind != WalletKind.SILENT_PAYMENT
        WalletFeature.SIGN_MESSAGE -> kind != WalletKind.MULTISIG
        WalletFeature.BIP85 -> kind != WalletKind.MULTISIG && !isStateless
        // These rewrite or remove the stored wallet record, which a stateless wallet doesn't have
        WalletFeature.DIFFERENT_ACCOUNTS, WalletFeature.CHANGE_ADDRESS_TYPE ->
            kind != WalletKind.MULTISIG && !isStateless
        WalletFeature.DELETE -> !isStateless
        // Watch-only xpub exports; an SP wallet's equivalent is its scan key
        WalletFeature.COORDINATOR_EXPORT, WalletFeature.ACCOUNT_KEYS_AND_DESCRIPTORS ->
            kind == WalletKind.SINGLE_SIG
        // A stateless wallet never keeps its mnemonic
        WalletFeature.SEED_PHRASE -> kind != WalletKind.MULTISIG && !isStateless
    }

    companion object {
        /** Profile of a stored wallet, read from its metadata flags. */
        fun of(metadata: WalletMetadata): WalletProfile = WalletProfile(
            kind = when {
                metadata.isMultisig -> WalletKind.MULTISIG
                metadata.isSilentPayment -> WalletKind.SILENT_PAYMENT
                else -> WalletKind.SINGLE_SIG
            },
            isStateless = false
        )

        /** Profile of a stateless wallet, which has no metadata: its kind follows its derivation path. */
        fun stateless(derivationPath: String): WalletProfile = WalletProfile(
            kind = if (AddressFormat.fromPath(derivationPath).isSilentPayment) {
                WalletKind.SILENT_PAYMENT
            } else {
                WalletKind.SINGLE_SIG
            },
            isStateless = true
        )
    }
}
