package com.gorunjinian.metrovault.core.ui.dialogs

import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.WalletPassphrases
import com.gorunjinian.metrovault.domain.manager.PassphraseManager.KeyPassphraseInfo
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Runnable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.coroutines.CoroutineContext

/** Which passphrases are asked for before a wallet opens, in what order, and when it doesn't open. */
class PassphrasePromptStateTest {

    /** Records what was entered, in order. */
    private class FakeWallet : WalletPassphrases {
        val needing = mutableSetOf<String>()
        val keysNeeding = mutableMapOf<String, List<KeyPassphraseInfo>>()
        val mnemonics = mutableMapOf<String, List<String>>()
        val entered = mutableListOf<Pair<String, String>>()

        /** While set, looking up a multisig's keys waits for it. */
        var keyLookupGate: CompletableDeferred<Unit>? = null

        /** While set, applying a passphrase waits for it, like the key derivation does. */
        var enterGate: CompletableDeferred<Unit>? = null

        override fun needsPassphraseInput(walletId: String) = walletId in needing

        override suspend fun getKeysNeedingPassphrase(walletId: String): List<KeyPassphraseInfo> {
            keyLookupGate?.await()
            return keysNeeding[walletId].orEmpty()
        }

        override suspend fun getMnemonicForWallet(walletId: String) = mnemonics[walletId]

        override suspend fun setSessionPassphrase(walletId: String, passphrase: String) {
            enterGate?.await()
            entered += walletId to passphrase
        }

        override suspend fun setSessionKeyPassphrase(keyId: String, passphrase: String) {
            enterGate?.await()
            entered += keyId to passphrase
        }

        override fun calculateFingerprint(mnemonic: List<String>, passphrase: String) =
            "${mnemonic.first()}+$passphrase"
    }

    /** Runs the newest work first, the order in which a dropped request cleans up last. */
    private class NewestFirst : CoroutineDispatcher() {
        private val pending = ArrayDeque<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) = pending.addLast(block)

        fun runAll() {
            while (pending.isNotEmpty()) pending.removeLast().run()
        }
    }

    private val wallet = FakeWallet()
    private val state = PassphrasePromptState(wallet, CoroutineScope(Dispatchers.Unconfined))

    /** The wallets that became ready to open, in order. */
    private val ready = mutableListOf<String>()

    private fun metadata(id: String, hasPassphrase: Boolean = false, isMultisig: Boolean = false) = WalletMetadata(
        id = id,
        name = "Wallet $id",
        derivationPath = "m/84'/0'/0'",
        masterFingerprint = "fp-$id",
        hasPassphrase = hasPassphrase,
        createdAt = 0L,
        isMultisig = isMultisig
    )

    private fun key(id: String) = KeyPassphraseInfo(id, "Key $id", "fp-$id", listOf("seed-$id"))

    private fun open(metadata: WalletMetadata) = state.open(metadata) { ready += metadata.id }

    private fun passphraseWallet(id: String, hasPassphrase: Boolean = true): WalletMetadata {
        wallet.needing += id
        wallet.mnemonics[id] = listOf("seed-$id")
        return metadata(id, hasPassphrase)
    }

    private fun multisig(id: String, vararg keys: String): WalletMetadata {
        wallet.keysNeeding[id] = keys.map { key(it) }
        return metadata(id, isMultisig = true)
    }

    // ==================== Nothing to ask ====================

    @Test
    fun aWalletThatNeedsNothingOpensWithoutAPrompt() {
        open(metadata("plain"))
        open(multisig("multi"))

        assertEquals(listOf("plain", "multi"), ready)
        assertNull(state.current)
        assertTrue(wallet.entered.isEmpty())
    }

    // ==================== Single-seed wallets ====================

    @Test
    fun aPassphraseWalletOpensAfterItsPassphraseIsEntered() {
        open(passphraseWallet("a"))

        val prompt = state.current!!
        assertEquals("Wallet a", prompt.label)
        assertEquals("fp-a", prompt.fingerprint)
        assertNull("it asks every time, so nothing to explain", prompt.note)
        assertEquals("seed-a+secret", state.fingerprint(prompt, "secret"))
        assertTrue(ready.isEmpty())

        state.confirm("secret")

        assertEquals(listOf("a" to "secret"), wallet.entered)
        assertEquals(listOf("a"), ready)
        assertNull(state.current)
    }

    @Test
    fun anEmptyPassphraseIsAnAnswerToo() {
        open(passphraseWallet("a"))

        state.confirm("")

        assertEquals(listOf("a" to ""), wallet.entered)
        assertEquals(listOf("a"), ready)
    }

    @Test
    fun backingOutOpensNothing() {
        open(passphraseWallet("a"))

        state.cancel()

        assertNull(state.current)
        assertTrue(wallet.entered.isEmpty())
        assertTrue(ready.isEmpty())
    }

    @Test
    fun theWalletOpensOnlyOnceItsPassphraseIsInPlace() {
        val gate = CompletableDeferred<Unit>().also { wallet.enterGate = it }
        open(passphraseWallet("a"))

        state.confirm("secret")
        assertNull("the dialog closes at once", state.current)
        assertTrue("opening now would use the wrong seed", ready.isEmpty())

        gate.complete(Unit)
        assertEquals(listOf("a"), ready)
    }

    @Test
    fun aSecondTapOnConfirmChangesNothing() {
        val gate = CompletableDeferred<Unit>().also { wallet.enterGate = it }
        open(passphraseWallet("a"))

        state.confirm("secret")
        state.confirm("secret")
        gate.complete(Unit)

        assertEquals(listOf("a" to "secret"), wallet.entered)
        assertEquals(listOf("a"), ready)
    }

    @Test
    fun aWalletSavedWithoutItsPassphraseByAnOlderVersionSaysSo() {
        open(passphraseWallet("a", hasPassphrase = false))

        assertTrue(state.current!!.note!!.startsWith("An older version saved this wallet"))
    }

    @Test
    fun anUnreadableKeyAsksNothingAndOpensNothing() {
        val unreadable = passphraseWallet("a")
        wallet.mnemonics.clear()

        open(unreadable)

        assertNull(state.current)
        assertTrue(ready.isEmpty())
    }

    // ==================== Adding a passphrase ====================

    @Test
    fun addingAPassphraseAsksEvenWhenNoneIsNeeded() {
        wallet.mnemonics["a"] = listOf("seed-a")
        val plain = metadata("a")

        state.openWithPassphrase(plain) { ready += plain.id }
        assertEquals("Applies until you leave this wallet. Nothing is saved.", state.current!!.note)
        state.confirm("extra")

        assertEquals(listOf("a" to "extra"), wallet.entered)
        assertEquals(listOf("a"), ready)
    }

    @Test
    fun addingAPassphraseToAWalletThatAlwaysAsksIsTheUsualPrompt() {
        val asking = passphraseWallet("a")

        state.openWithPassphrase(asking) { ready += asking.id }

        assertNotNull(state.current)
        assertNull(state.current!!.note)
    }

    // ==================== Multisig ====================

    @Test
    fun aMultisigAsksForEachKeyInTurn() {
        open(multisig("multi", "k1", "k2"))

        val first = state.current!!
        assertEquals("Key k1", first.label)
        state.confirm("one")

        val second = state.current!!
        assertEquals("Key k2", second.label)
        assertEquals("fp-k2", second.fingerprint)
        // Each prompt previews its own key
        assertEquals("seed-k2+x", state.fingerprint(second, "x"))
        assertTrue(ready.isEmpty())
        state.confirm("two")

        assertEquals(listOf("k1" to "one", "k2" to "two"), wallet.entered)
        assertEquals(listOf("multi"), ready)
        assertNull(state.current)
    }

    @Test
    fun aSecondTapOnConfirmDoesNotSkipAKey() {
        val gate = CompletableDeferred<Unit>().also { wallet.enterGate = it }
        open(multisig("multi", "k1", "k2"))

        state.confirm("one")
        state.confirm("one")
        gate.complete(Unit)

        assertEquals("Key k2", state.current!!.label)
        assertEquals(listOf("k1" to "one"), wallet.entered)
        assertTrue(ready.isEmpty())
    }

    @Test
    fun backingOutOfALaterKeyOpensNothing() {
        open(multisig("multi", "k1", "k2"))

        state.confirm("one")
        state.cancel()

        assertNull(state.current)
        assertEquals(listOf("k1" to "one"), wallet.entered)
        assertTrue(ready.isEmpty())
    }

    // ==================== One wallet at a time ====================

    @Test
    fun aNewRequestReplacesTheOneBeingAskedAbout() {
        open(passphraseWallet("a"))
        open(passphraseWallet("b"))

        assertEquals("Wallet b", state.current!!.label)
        state.confirm("secret")

        assertEquals(listOf("b" to "secret"), wallet.entered)
        assertEquals(listOf("b"), ready)
        assertNull("the dropped request must not come back", state.current)
    }

    @Test
    fun aDroppedRequestDoesNotCloseThePromptThatReplacedIt() {
        val dispatcher = NewestFirst()
        val state = PassphrasePromptState(wallet, CoroutineScope(dispatcher))
        state.open(passphraseWallet("a")) { ready += "a" }
        dispatcher.runAll()
        assertEquals("Wallet a", state.current!!.label)

        state.open(passphraseWallet("b")) { ready += "b" }
        dispatcher.runAll()

        assertEquals("Wallet b", state.current?.label)
    }

    @Test
    fun aRequestDroppedWhileLookingUpItsKeysNeverOpens() {
        val gate = CompletableDeferred<Unit>().also { wallet.keyLookupGate = it }
        open(multisig("multi"))
        open(metadata("plain"))

        gate.complete(Unit)

        assertEquals(listOf("plain"), ready)
    }

    @Test
    fun aRequestDroppedWhileApplyingAPassphraseNeverOpens() {
        val gate = CompletableDeferred<Unit>().also { wallet.enterGate = it }
        open(passphraseWallet("a"))
        state.confirm("secret")
        open(metadata("plain"))

        gate.complete(Unit)

        assertEquals(listOf("plain"), ready)
    }

    @Test
    fun theSameWalletCanBeAskedAboutAgainAfterBackingOut() {
        val wallet = passphraseWallet("a")
        open(wallet)
        state.cancel()

        open(wallet)
        state.confirm("secret")

        assertEquals(listOf("a"), ready)
    }
}
