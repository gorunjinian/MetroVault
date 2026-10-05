package com.gorunjinian.metrovault.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Pins which wallets offer which features. Wallet Details, Export Options, the wallet-card
 * shortcuts and navigation all read [WalletProfile.supports], so a change here changes what the
 * user can reach everywhere at once.
 */
class WalletProfileTest {

    private val singleSig = WalletProfile(WalletKind.SINGLE_SIG, isStateless = false)
    private val silentPayment = WalletProfile(WalletKind.SILENT_PAYMENT, isStateless = false)
    private val multisig = WalletProfile(WalletKind.MULTISIG, isStateless = false)
    private val statelessSingleSig = WalletProfile(WalletKind.SINGLE_SIG, isStateless = true)
    private val statelessSilentPayment = WalletProfile(WalletKind.SILENT_PAYMENT, isStateless = true)

    private val allProfiles = listOf(singleSig, silentPayment, multisig, statelessSingleSig, statelessSilentPayment)

    private fun metadata(path: String, isMultisig: Boolean = false, isSilentPayment: Boolean = false) =
        WalletMetadata(
            id = "id",
            name = "Wallet",
            derivationPath = path,
            masterFingerprint = "73c5da0a",
            hasPassphrase = false,
            createdAt = 0L,
            isMultisig = isMultisig,
            isSilentPayment = isSilentPayment
        )

    @Test
    fun `stored wallet kind comes from its metadata flags`() {
        assertEquals(singleSig, WalletProfile.of(metadata(DerivationPaths.TAPROOT)))
        assertEquals(
            silentPayment,
            WalletProfile.of(metadata(DerivationPaths.SILENT_PAYMENT, isSilentPayment = true))
        )
        assertEquals(multisig, WalletProfile.of(metadata("multisig/2of3", isMultisig = true)))
    }

    @Test
    fun `stateless wallet kind comes from its derivation path`() {
        assertEquals(statelessSingleSig, WalletProfile.stateless(DerivationPaths.NATIVE_SEGWIT))
        assertEquals(statelessSingleSig, WalletProfile.stateless(DerivationPaths.legacy(2, testnet = true)))
        assertEquals(statelessSilentPayment, WalletProfile.stateless(DerivationPaths.SILENT_PAYMENT))
        assertEquals(
            statelessSilentPayment,
            WalletProfile.stateless(DerivationPaths.silentPaymentAccount(3, testnet = true))
        )
    }

    @Test
    fun `each feature is offered to exactly the expected wallets`() {
        val expected = mapOf(
            WalletFeature.VIEW_ADDRESSES to allProfiles,
            WalletFeature.SIGN_PSBT to allProfiles,
            WalletFeature.EXPORT to allProfiles,
            WalletFeature.CHECK_ADDRESS to listOf(singleSig, multisig, statelessSingleSig),
            WalletFeature.SIGN_MESSAGE to
                listOf(singleSig, silentPayment, statelessSingleSig, statelessSilentPayment),
            WalletFeature.BIP85 to listOf(singleSig, silentPayment),
            WalletFeature.DIFFERENT_ACCOUNTS to listOf(singleSig, silentPayment),
            WalletFeature.CHANGE_ADDRESS_TYPE to listOf(singleSig, silentPayment),
            WalletFeature.DELETE to listOf(singleSig, silentPayment, multisig),
            WalletFeature.COORDINATOR_EXPORT to listOf(singleSig, statelessSingleSig),
            WalletFeature.ACCOUNT_KEYS_AND_DESCRIPTORS to listOf(singleSig, statelessSingleSig),
            WalletFeature.SEED_PHRASE to listOf(singleSig, silentPayment),
        )
        // A new feature has to be added to the table above before this passes
        assertEquals(WalletFeature.entries.toSet(), expected.keys)

        for ((feature, supported) in expected) {
            for (profile in allProfiles) {
                assertEquals("$feature on $profile", profile in supported, profile.supports(feature))
            }
        }
    }

    @Test
    fun `wallet cards drop the shortcuts their wallet does not support`() {
        val chosen = listOf(QuickShortcut.VIEW_ADDRESSES, QuickShortcut.CHECK_ADDRESS, QuickShortcut.EXPORT)

        assertEquals(chosen, QuickShortcut.forProfile(chosen, singleSig))
        assertEquals(
            listOf(QuickShortcut.VIEW_ADDRESSES, QuickShortcut.EXPORT),
            QuickShortcut.forProfile(chosen, silentPayment)
        )
    }

    @Test
    fun `multisig cards always show the default shortcuts, all of which multisig supports`() {
        val chosen = listOf(QuickShortcut.EXPORT, QuickShortcut.BIP85, QuickShortcut.SIGN_MESSAGE)

        assertEquals(QuickShortcut.DEFAULT, QuickShortcut.forProfile(chosen, multisig))
        for (shortcut in QuickShortcut.DEFAULT) {
            assertEquals(shortcut.name, true, multisig.supports(shortcut.feature))
        }
    }
}
