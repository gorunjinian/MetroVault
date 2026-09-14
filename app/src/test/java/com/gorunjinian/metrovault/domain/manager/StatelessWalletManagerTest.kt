package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Lifecycle of the in-memory stateless wallet: keys exist only while the wallet is live,
 * every handle is invalidated on wipe, and creating a new one disposes the previous one.
 */
class StatelessWalletManagerTest {

    private val manager = StatelessWalletManager(BitcoinService())

    // BIP39 canonical vectors; empty passphrase master fingerprint of the first is 73c5da0a
    private val abandonAbout = List(11) { "abandon" } + "about"
    private val abandonArt = List(23) { "abandon" } + "art"

    @Test
    fun createHoldsDerivedKeysButNeverTheMnemonic() {
        val state = manager.create(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)

        assertNotNull(state)
        assertTrue(manager.hasWallet())
        assertSame(state, manager.get())
        assertEquals("73c5da0a", state!!.fingerprint)
        assertEquals(DerivationPaths.NATIVE_SEGWIT, state.derivationPath)
        assertNull("mnemonic must not be retained", state.getMnemonic())
        assertNotNull(state.getMasterPrivateKey())
        assertNotNull(state.getAccountPrivateKey())
        assertNotNull(state.getAccountPublicKey())
    }

    @Test
    fun wipeDisposesTheWalletAndInvalidatesEveryHandle() {
        val state = manager.create(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)!!

        manager.wipe()

        assertFalse(manager.hasWallet())
        assertNull(manager.get())
        // A caller that kept the old handle can no longer read keys through it
        assertThrows { state.getMasterPrivateKey() }
        assertThrows { state.getAccountPrivateKey() }
        assertThrows { state.getAccountPublicKey() }
    }

    @Test
    fun wipeIsIdempotentAndSafeWithoutAWallet() {
        manager.wipe()
        manager.create(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)
        manager.wipe()
        manager.wipe()
        assertFalse(manager.hasWallet())
    }

    @Test
    fun creatingANewWalletDisposesThePreviousOne() {
        val first = manager.create(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)!!

        val second = manager.create(abandonArt, "", DerivationPaths.NATIVE_SEGWIT)!!

        assertSame(second, manager.get())
        assertNotEquals(first.fingerprint, second.fingerprint)
        assertThrows { first.getMasterPrivateKey() }
        assertNotNull(second.getMasterPrivateKey())
    }

    @Test
    fun invalidMnemonicCreatesNothing() {
        val state = manager.create(List(12) { "abandon" }, "", DerivationPaths.NATIVE_SEGWIT)

        assertNull(state)
        assertFalse(manager.hasWallet())
        assertNull(manager.get())
    }

    @Test
    fun passphraseChangesTheWallet() {
        val plain = manager.create(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)!!.fingerprint
        val withPassphrase = manager.create(abandonAbout, "TREZOR", DerivationPaths.NATIVE_SEGWIT)!!.fingerprint

        assertNotEquals(plain, withPassphrase)
    }

    @Test
    fun computeFingerprintOnlyLeavesNoWalletBehind() {
        val fingerprint = manager.computeFingerprintOnly(abandonAbout, "", DerivationPaths.NATIVE_SEGWIT)

        assertEquals("73c5da0a", fingerprint)
        assertFalse(manager.hasWallet())
        assertNull(manager.get())
        assertNull(manager.computeFingerprintOnly(List(12) { "abandon" }, "", DerivationPaths.NATIVE_SEGWIT))
    }

    @Test
    fun activeWalletInfoFollowsTheStatelessWalletWhileItLives() {
        val path = DerivationPaths.withAccountNumber(DerivationPaths.NATIVE_SEGWIT, 3)
        manager.create(abandonAbout, "", path)

        val live = manager.getActiveWalletInfo(metadata = null)
        assertTrue(live.isStateless)
        assertEquals(3, live.accountNumber)
        assertEquals(path, live.derivationPath)
        assertEquals(listOf(3), live.accounts)

        manager.wipe()

        val gone = manager.getActiveWalletInfo(metadata = null)
        assertFalse(gone.isStateless)
        assertEquals(0, gone.accountNumber)
        assertEquals(listOf(0), gone.accounts)
    }

    private fun assertThrows(block: () -> Unit) {
        var threw = false
        try {
            block()
        } catch (_: IllegalStateException) {
            threw = true
        }
        assertTrue("expected IllegalStateException from a wiped WalletState", threw)
    }
}
