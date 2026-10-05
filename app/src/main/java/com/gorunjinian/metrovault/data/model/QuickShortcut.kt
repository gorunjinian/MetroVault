package com.gorunjinian.metrovault.data.model

import com.gorunjinian.metrovault.R

/**
 * Quick shortcuts available for the expanded wallet card.
 * Users can select any 3 of these options to display.
 */
enum class QuickShortcut(
    val label: String,
    val iconRes: Int,
    val feature: WalletFeature
) {
    VIEW_ADDRESSES("Addresses", R.drawable.ic_addresses, WalletFeature.VIEW_ADDRESSES),
    SIGN_PSBT("Sign PSBT", R.drawable.ic_qr_code_scanner, WalletFeature.SIGN_PSBT),
    CHECK_ADDRESS("Check", R.drawable.ic_search, WalletFeature.CHECK_ADDRESS),
    EXPORT("Export", R.drawable.ic_upload, WalletFeature.EXPORT),
    BIP85("BIP-85", R.drawable.ic_account_tree, WalletFeature.BIP85),
    SIGN_MESSAGE("Message", R.drawable.ic_signature, WalletFeature.SIGN_MESSAGE);

    companion object {
        val DEFAULT = listOf(VIEW_ADDRESSES, SIGN_PSBT, CHECK_ADDRESS)

        /**
         * The shortcuts a card shows for a wallet with [profile]. Multisig cards always show
         * [DEFAULT]; every other card shows the [chosen] shortcuts the wallet supports.
         */
        fun forProfile(chosen: List<QuickShortcut>, profile: WalletProfile): List<QuickShortcut> =
            if (profile.kind == WalletKind.MULTISIG) DEFAULT
            else chosen.filter { profile.supports(it.feature) }

        /**
         * Parse a comma-separated string of shortcut names.
         * Returns DEFAULT if the string is invalid or has wrong count.
         */
        fun fromStorageString(str: String?): List<QuickShortcut> {
            if (str.isNullOrBlank()) return DEFAULT
            val shortcuts = str.split(",").mapNotNull { name ->
                entries.find { it.name == name.trim() }
            }
            return if (shortcuts.size == 3) shortcuts else DEFAULT
        }

        /**
         * Convert a list of shortcuts to a comma-separated storage string.
         */
        fun toStorageString(shortcuts: List<QuickShortcut>): String {
            return shortcuts.joinToString(",") { it.name }
        }
    }
}
