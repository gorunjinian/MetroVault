package com.gorunjinian.metrovault.bitcoin.silentpayments

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.manager.SilentPaymentManager
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the wallet-lifecycle wiring around silent payments:
 *  - account-level path builder + `withAccountNumber` purpose-352 case (used by `Wallet.createWallet`
 *    and account-add/switch flows)
 *  - resolver preference order (live derivation when unlocked, cached pubkeys when locked, null
 *    when neither is available) — the contract `SPAddressScreen`, `WalletDetails`, and
 *    `AddressesScreen`'s SP tab rely on.
 *
 * Pure JVM — no Android dependencies, runs under :app:testDebugUnitTest.
 */
class SilentPaymentWalletLifecycleTest {

    private val manager = SilentPaymentManager()
    private val mnemonic =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about"
            .split(" ")
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(mnemonic, ""))

    @Test
    fun silentPaymentAccount_path_mainnet() {
        assertEquals("m/352'/0'/0'", DerivationPaths.silentPaymentAccount(0, testnet = false))
        assertEquals("m/352'/0'/5'", DerivationPaths.silentPaymentAccount(5, testnet = false))
    }

    @Test
    fun silentPaymentAccount_path_testnet() {
        assertEquals("m/352'/1'/0'", DerivationPaths.silentPaymentAccount(0, testnet = true))
        assertEquals("m/352'/1'/3'", DerivationPaths.silentPaymentAccount(3, testnet = true))
    }

    @Test
    fun withAccountNumber_preserves_purpose_352() {
        val base = DerivationPaths.SILENT_PAYMENT
        assertEquals("m/352'/0'/7'", DerivationPaths.withAccountNumber(base, 7))

        val baseTestnet = DerivationPaths.SILENT_PAYMENT_TESTNET
        assertEquals("m/352'/1'/2'", DerivationPaths.withAccountNumber(baseTestnet, 2))
    }

    @Test
    fun withAccountNumber_does_not_collapse_352_to_native_segwit() {
        // Regression: before Phase B, `withAccountNumber` defaulted purpose 352 to BIP84 paths.
        val rebuilt = DerivationPaths.withAccountNumber(DerivationPaths.SILENT_PAYMENT, 0)
        assertEquals(352, DerivationPaths.getPurpose(rebuilt))
    }

    @Test
    fun resolveAddress_prefers_live_derivation_when_master_available() {
        val cachedAccount = 5
        val activeAccount = 0
        val cached = SilentPaymentWalletService.deriveKeys(master, cachedAccount, isTestnet = false)
        val live = SilentPaymentWalletService.deriveKeys(master, activeAccount, isTestnet = false)

        val cachedAddress = SilentPaymentWalletService.silentPaymentAddress(cached, isTestnet = false)
        val liveAddress = SilentPaymentWalletService.silentPaymentAddress(live, isTestnet = false)
        assertNotEquals(cachedAddress, liveAddress)

        val metadata = spMetadata(
            scanPubKeyHex = cached.scanPublicKey.toHex(),
            spendPubKeyHex = cached.spendPublicKey.toHex(),
        )

        val resolved = manager.resolveAddress(
            metadata = metadata,
            masterPrivateKey = master,
            account = activeAccount,
            isTestnet = false,
        )

        assertEquals(
            "Unlocked wallet must derive fresh sp1q for the active account, not return stale cache",
            liveAddress,
            resolved,
        )
    }

    @Test
    fun resolveAddress_falls_back_to_cache_when_locked() {
        val cached = SilentPaymentWalletService.deriveKeys(master, 0, isTestnet = false)
        val cachedAddress = SilentPaymentWalletService.silentPaymentAddress(cached, isTestnet = false)
        val metadata = spMetadata(
            scanPubKeyHex = cached.scanPublicKey.toHex(),
            spendPubKeyHex = cached.spendPublicKey.toHex(),
        )

        val resolved = manager.resolveAddress(
            metadata = metadata,
            masterPrivateKey = null,
            account = 0,
            isTestnet = false,
        )

        assertEquals(cachedAddress, resolved)
    }

    @Test
    fun resolveAddress_returns_null_when_locked_and_no_cache() {
        val resolved = manager.resolveAddress(
            metadata = spMetadata(scanPubKeyHex = "", spendPubKeyHex = ""),
            masterPrivateKey = null,
            account = 0,
            isTestnet = false,
        )
        assertNull(resolved)
    }

    @Test
    fun resolveAddress_testnet_addresses_use_tsp1q_hrp() {
        val keys = SilentPaymentWalletService.deriveKeys(master, 0, isTestnet = true)
        val metadata = spMetadata(
            scanPubKeyHex = keys.scanPublicKey.toHex(),
            spendPubKeyHex = keys.spendPublicKey.toHex(),
        )
        val resolved = manager.resolveAddress(
            metadata = metadata,
            masterPrivateKey = master,
            account = 0,
            isTestnet = true,
        ) ?: error("expected non-null testnet address")
        assertTrue("Testnet SP must use tsp1q… HRP, got $resolved", resolved.startsWith("tsp1q"))
    }

    private fun spMetadata(scanPubKeyHex: String, spendPubKeyHex: String) = WalletMetadata(
        id = "test-wallet",
        name = "SP",
        derivationPath = DerivationPaths.SILENT_PAYMENT,
        masterFingerprint = "deadbeef",
        hasPassphrase = false,
        createdAt = 0L,
        accounts = listOf(0),
        activeAccountNumber = 0,
        keyIds = listOf("kid"),
        isSilentPayment = true,
        silentPaymentScanPubKey = scanPubKeyHex,
        silentPaymentSpendPubKey = spendPubKeyHex,
    )
}
