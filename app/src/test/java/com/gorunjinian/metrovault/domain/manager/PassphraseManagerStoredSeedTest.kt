package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import com.gorunjinian.vaultovich.MnemonicCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a shared WalletKey's stored seed must hold. The key's fingerprint is always that of
 * mnemonic + passphrase, while the seed on disk is the full one or only the base one (mnemonic
 * alone); the fingerprint of the stored seed is how the app tells which it has.
 */
class PassphraseManagerStoredSeedTest {

    private val bitcoinService = BitcoinService()
    private val mnemonic = List(11) { "abandon" } + "about"

    private fun seedHex(passphrase: String): String {
        val seed = MnemonicCode.toSeed(mnemonic, passphrase)
        return try {
            seed.toHexString()
        } finally {
            seed.fill(0)
        }
    }

    private fun wallet(
        keyId: String,
        hasPassphrase: Boolean = false,
        isMultisig: Boolean = false
    ) = WalletMetadata(
        id = "wallet-$keyId-$hasPassphrase-$isMultisig",
        name = "Wallet",
        derivationPath = if (isMultisig) "multisig/2of3" else "m/84'/0'/0'",
        masterFingerprint = "73c5da0a",
        hasPassphrase = hasPassphrase,
        createdAt = 0L,
        isMultisig = isMultisig,
        keyIds = listOf(keyId)
    )

    // ==================== Stored seed vs. fingerprint ====================

    @Test
    fun onlyTheFullSeedMatchesAPassphraseWalletsFingerprint() {
        val fingerprint = bitcoinService.calculateFingerprint(mnemonic, "secret")

        assertNotNull(fingerprint)
        assertEquals(fingerprint,bitcoinService.calculateFingerprintFromSeed(seedHex("secret")))
        assertNotEquals(fingerprint, bitcoinService.calculateFingerprintFromSeed(seedHex("")))
    }

    @Test
    fun withoutAPassphraseTheBaseSeedIsTheFullSeed() {
        assertEquals("73c5da0a", bitcoinService.calculateFingerprintFromSeed(seedHex("")))
    }

    // ==================== storedSeedInUse ====================

    @Test
    fun walletsThatAllAskForThePassphraseDoNotNeedItSaved() {
        val wallets = listOf(wallet("k", hasPassphrase = true), wallet("k", hasPassphrase = true))

        assertFalse(PassphraseManager.storedSeedInUse("k", wallets))
    }

    @Test
    fun aWalletThatSavesItsPassphraseKeepsItSaved() {
        val wallets = listOf(wallet("k", hasPassphrase = true), wallet("k", hasPassphrase = false))

        assertTrue(PassphraseManager.storedSeedInUse("k", wallets))
    }

    @Test
    fun aMultisigWalletKeepsWhateverTheKeyHolds() {
        val wallets = listOf(wallet("k", hasPassphrase = true), wallet("k", isMultisig = true))

        assertTrue(PassphraseManager.storedSeedInUse("k", wallets))
    }

    @Test
    fun walletsOnOtherKeysAreIgnored() {
        val wallets = listOf(
            wallet("k", hasPassphrase = true),
            wallet("other", hasPassphrase = false),
            wallet("other", isMultisig = true)
        )

        assertFalse(PassphraseManager.storedSeedInUse("k", wallets))
    }

    @Test
    fun aKeyNoWalletUsesIsNotInUse() {
        assertFalse(PassphraseManager.storedSeedInUse("k", emptyList()))
    }
}
