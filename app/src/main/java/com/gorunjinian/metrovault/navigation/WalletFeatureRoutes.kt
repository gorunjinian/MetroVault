package com.gorunjinian.metrovault.navigation

import androidx.navigation.NavHostController
import com.gorunjinian.metrovault.data.model.WalletFeature
import com.gorunjinian.metrovault.data.model.WalletKind
import com.gorunjinian.metrovault.data.model.WalletProfile
import com.gorunjinian.metrovault.domain.Wallet

/**
 * Open [feature] for the active wallet. Does nothing when no wallet is open or the wallet doesn't
 * support it. Wallet Details and the wallet-card shortcuts both navigate through here, so they
 * can't disagree about where a feature leads or whether it's offered.
 */
fun NavHostController.openWalletFeature(wallet: Wallet, feature: WalletFeature) {
    val route = wallet.getActiveWalletProfile()?.let { feature.routeFor(it) } ?: return
    navigate(route)
}

/** The screen [this] opens for a wallet with [profile], or null when the wallet doesn't support it. */
internal fun WalletFeature.routeFor(profile: WalletProfile): String? {
    if (!profile.supports(this)) return null
    return when (this) {
        WalletFeature.VIEW_ADDRESSES ->
            if (profile.kind == WalletKind.SILENT_PAYMENT) Screen.SPAddress.route else Screen.Addresses.createRoute()
        WalletFeature.SIGN_PSBT -> Screen.ScanPSBT.route
        WalletFeature.CHECK_ADDRESS -> Screen.CheckAddress.route
        WalletFeature.EXPORT ->
            if (profile.kind == WalletKind.MULTISIG) Screen.ExportMultiSig.route else Screen.ExportOptions.route
        WalletFeature.SIGN_MESSAGE -> Screen.SignMessage.createRoute()
        WalletFeature.BIP85 -> Screen.BIP85Derive.route
        WalletFeature.DIFFERENT_ACCOUNTS -> Screen.DifferentAccounts.route
        WalletFeature.CHANGE_ADDRESS_TYPE -> Screen.AddressType.route
        // Reached from Export Options or acted on in place, never from Wallet Details or a shortcut
        WalletFeature.DELETE,
        WalletFeature.COORDINATOR_EXPORT,
        WalletFeature.ACCOUNT_KEYS_AND_DESCRIPTORS,
        WalletFeature.SEED_PHRASE -> null
    }
}
