package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.data.model.CoordinatorExportError
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.bitcoin.CoordinatorExportService
import com.gorunjinian.metrovault.domain.service.bitcoin.KeyEncodingService
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.MnemonicCode
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The private-key side of the coordinator exports: which account key each export is built from
 * (cached or derived from the master key), and how failures surface. Section contents and
 * validation are CoordinatorExportService's and are covered by its own test.
 */
class CoordinatorExportManagerTest {

    private val keyEncodingService = KeyEncodingService()
    private val manager = CoordinatorExportManager(keyEncodingService, CoordinatorExportService(keyEncodingService))

    // BIP39 canonical vector; empty passphrase master fingerprint is 73c5da0a
    private val master = DeterministicWallet.generate(MnemonicCode.toSeed(List(11) { "abandon" } + "about", ""))
    private val fingerprint = "73c5da0a"

    private fun xpubAt(path: String, testnet: Boolean = false): String =
        master.derivePrivateKey(path).extendedPublicKey
            .encode(if (testnet) DeterministicWallet.tpub else DeterministicWallet.xpub)

    /** A loaded seed wallet: master key plus the cached account keys for [path]. */
    private fun seedWallet(path: String) = WalletState(
        name = "Seed",
        mnemonic = null,
        derivationPath = path,
        fingerprint = fingerprint,
        masterPrivateKey = master,
        accountPrivateKey = master.derivePrivateKey(path),
        accountPublicKey = master.derivePrivateKey(path).extendedPublicKey
    )

    /** An xpub-only wallet: the account public key for [path] and nothing else. */
    private fun xpubOnlyWallet(path: String) = WalletState(
        name = "Watch",
        mnemonic = null,
        derivationPath = path,
        fingerprint = fingerprint,
        masterPrivateKey = null,
        accountPrivateKey = null,
        accountPublicKey = master.derivePrivateKey(path).extendedPublicKey
    )

    private fun available(result: CoordinatorExportResult) =
        (result as? CoordinatorExportResult.Available)?.data
            ?: throw AssertionError("Expected an available export, got $result")

    @Test
    fun activeAccountUsesTheCachedKeyWithoutAMasterKey() {
        val data = available(manager.buildExport(xpubOnlyWallet("m/84'/0'/0'"), 0))

        assertEquals(0, data.accountNumber)
        assertEquals(xpubAt("m/84'/0'/0'"), data.standardAccountXpub)
        assertEquals(fingerprint.uppercase(), data.masterFingerprint)
        assertEquals("Watch", data.walletName)
    }

    @Test
    fun otherAccountsAreDerivedFromTheMasterKeyUnderTheWalletsPurpose() {
        val data = available(manager.buildExport(seedWallet("m/49'/0'/0'"), 3))

        assertEquals(3, data.accountNumber)
        assertEquals(xpubAt("m/49'/0'/3'"), data.standardAccountXpub)
    }

    @Test
    fun otherAccountsOfAnXpubOnlyWalletAreUnavailable() {
        val result = manager.buildExport(xpubOnlyWallet("m/84'/0'/0'"), 1)

        assertEquals(CoordinatorExportResult.Error(CoordinatorExportError.ACCOUNT_KEY_UNAVAILABLE), result)
    }

    @Test
    fun aPurposeTheExportDoesNotSupportIsReportedAsUnsupported() {
        val result = manager.buildExport(seedWallet("m/352'/0'/0'"), 0)

        assertEquals(CoordinatorExportResult.Unsupported(CoordinatorExportError.UNSUPPORTED_DERIVATION_PATH), result)
    }

    @Test
    fun combinedExportBuildsEachSectionFromItsOwnPath() {
        listOf(false, true).forEach { testnet ->
            val coin = if (testnet) 1 else 0
            val json = manager.buildCombinedExport(master, fingerprint, accountNumber = 2, isTestnet = testnet)
            assertNotNull(json)
            val parsed = JSONObject(json!!)

            mapOf(
                "bip44" to "m/44'/$coin'/2'",
                "bip49" to "m/49'/$coin'/2'",
                "bip84" to "m/84'/$coin'/2'",
                "bip86" to "m/86'/$coin'/2'",
                "bip48_1" to "m/48'/$coin'/2'/1'",
                "bip48_2" to "m/48'/$coin'/2'/2'"
            ).forEach { (section, path) ->
                assertEquals("$section (testnet=$testnet)", xpubAt(path, testnet), parsed.getJSONObject(section).getString("xpub"))
            }
        }
    }

    @Test
    fun combinedExportFailureReturnsNull() {
        assertNull(manager.buildCombinedExport(master, "1234", accountNumber = 0, isTestnet = false))
    }

    @Test
    fun nunchukRecordUsesTheRequestedBip48ScriptType() {
        val p2wsh = manager.buildNunchukBip48SignerRecord(
            master, fingerprint, 0, DerivationPaths.Bip48ScriptType.P2WSH, isTestnet = false
        )
        val p2shP2wsh = manager.buildNunchukBip48SignerRecord(
            master, fingerprint, 0, DerivationPaths.Bip48ScriptType.P2SH_P2WSH, isTestnet = false
        )

        assertEquals("[73C5DA0A/48h/0h/0h/2h]${xpubAt("m/48'/0'/0'/2'")}/<0;1>/*", p2wsh)
        assertEquals("[73C5DA0A/48h/0h/0h/1h]${xpubAt("m/48'/0'/0'/1'")}/<0;1>/*", p2shP2wsh)
    }

    @Test
    fun nunchukRecordFailureIsEmpty() {
        val record = manager.buildNunchukBip48SignerRecord(
            master, "1234", 0, DerivationPaths.Bip48ScriptType.P2WSH, isTestnet = false
        )

        assertEquals("", record)
    }
}
