package com.gorunjinian.metrovault.navigation

import com.gorunjinian.metrovault.data.model.WalletFeature
import com.gorunjinian.metrovault.data.model.WalletKind
import com.gorunjinian.metrovault.data.model.WalletProfile
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Pins where each wallet feature leads. Wallet Details and the wallet-card shortcuts both go
 * through [routeFor], so these are the destinations either entry point reaches.
 */
class WalletFeatureRoutesTest {

    private val singleSig = WalletProfile(WalletKind.SINGLE_SIG, isStateless = false)
    private val silentPayment = WalletProfile(WalletKind.SILENT_PAYMENT, isStateless = false)
    private val multisig = WalletProfile(WalletKind.MULTISIG, isStateless = false)
    private val statelessSilentPayment = WalletProfile(WalletKind.SILENT_PAYMENT, isStateless = true)

    /** Features acted on inside a screen rather than opened from Wallet Details or a shortcut. */
    private val notDestinations = setOf(
        WalletFeature.DELETE,
        WalletFeature.COORDINATOR_EXPORT,
        WalletFeature.ACCOUNT_KEYS_AND_DESCRIPTORS,
        WalletFeature.SEED_PHRASE,
    )

    @Test
    fun `silent-payment wallets view their SP address, stored or stateless`() {
        assertEquals(Screen.SPAddress.route, WalletFeature.VIEW_ADDRESSES.routeFor(silentPayment))
        assertEquals(Screen.SPAddress.route, WalletFeature.VIEW_ADDRESSES.routeFor(statelessSilentPayment))
        assertEquals(Screen.Addresses.createRoute(), WalletFeature.VIEW_ADDRESSES.routeFor(singleSig))
        assertEquals(Screen.Addresses.createRoute(), WalletFeature.VIEW_ADDRESSES.routeFor(multisig))
    }

    @Test
    fun `multisig wallets export from their own screen`() {
        assertEquals(Screen.ExportMultiSig.route, WalletFeature.EXPORT.routeFor(multisig))
        assertEquals(Screen.ExportOptions.route, WalletFeature.EXPORT.routeFor(singleSig))
        assertEquals(Screen.ExportOptions.route, WalletFeature.EXPORT.routeFor(silentPayment))
    }

    @Test
    fun `an unsupported feature has no route`() {
        assertNull(WalletFeature.CHECK_ADDRESS.routeFor(silentPayment))
        assertNull(WalletFeature.CHECK_ADDRESS.routeFor(statelessSilentPayment))
        assertNull(WalletFeature.SIGN_MESSAGE.routeFor(multisig))
        assertNull(WalletFeature.BIP85.routeFor(statelessSilentPayment))
    }

    @Test
    fun `every supported destination resolves to a route`() {
        for (profile in listOf(singleSig, silentPayment, multisig, statelessSilentPayment)) {
            for (feature in WalletFeature.entries) {
                val route = feature.routeFor(profile)
                if (feature in notDestinations || !profile.supports(feature)) {
                    assertNull("$feature on $profile", route)
                } else {
                    assertNotNull("$feature on $profile", route)
                }
            }
        }
    }
}
