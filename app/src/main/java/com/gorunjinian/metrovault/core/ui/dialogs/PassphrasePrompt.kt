package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.WalletPassphrases
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

private const val ADDED_PASSPHRASE_NOTE = "Applies until you leave this wallet. Nothing is saved."

// A wallet that saves its passphrase is only asked for it when an older version stored its key
// without it (see PassphraseManager.needsPassphraseInput)
private const val OLDER_VERSION_NOTE =
    "An older version saved this wallet without its passphrase. Enter it to " +
        "continue; funds sent to its earlier addresses open with an empty passphrase."

/**
 * Asks for the passphrases a wallet needs before it can be opened. Show [PassphrasePrompt] on the
 * same screen.
 *
 * One wallet is asked about at a time: a new request drops the one before it.
 */
@Stable
class PassphrasePromptState(
    private val wallet: WalletPassphrases,
    private val scope: CoroutineScope
) {
    /** One passphrase to ask for. */
    class Prompt internal constructor(
        val label: String,
        val fingerprint: String,
        val note: String?,
        internal val mnemonic: List<String>,
        /** Applies the passphrase to the session. */
        internal val enter: suspend (passphrase: String) -> Unit
    ) {
        internal val answer = CompletableDeferred<String?>()
    }

    /** The passphrase being asked for; null when there is none. */
    var current: Prompt? by mutableStateOf(null)
        private set

    private var request: Job? = null

    /**
     * Calls [onReady] once the wallet can be opened: right away when nothing is missing, otherwise
     * after the user enters the passphrase (for a multisig, the passphrase of each local key that
     * needs one). Never called if the user cancels.
     */
    fun open(metadata: WalletMetadata, onReady: () -> Unit) = start {
        val prompts = when {
            metadata.isMultisig -> multisigPrompts(metadata.id)
            wallet.needsPassphraseInput(metadata.id) -> {
                val note = if (metadata.hasPassphrase) null else OLDER_VERSION_NOTE
                listOf(walletPrompt(metadata, note) ?: return@start)
            }
            else -> emptyList()
        }
        if (askAll(prompts)) onReady()
    }

    /**
     * Like [open], but always asks, so the user can give a single-seed wallet a passphrase for this
     * visit. It is applied to the stored mnemonic, replacing any saved one.
     */
    fun openWithPassphrase(metadata: WalletMetadata, onReady: () -> Unit) = start {
        val note = if (metadata.hasPassphrase) null else ADDED_PASSPHRASE_NOTE
        val prompt = walletPrompt(metadata, note) ?: return@start
        if (askAll(listOf(prompt))) onReady()
    }

    /** The user entered [passphrase], which may be empty, for the [current] prompt. */
    fun confirm(passphrase: String) {
        current?.answer?.complete(passphrase)
    }

    /** The user backed out; passphrases already entered for other keys stay for the session. */
    fun cancel() {
        current?.answer?.complete(null)
    }

    /** The fingerprint [passphrase] gives the key behind [prompt]. */
    fun fingerprint(prompt: Prompt, passphrase: String): String? =
        wallet.calculateFingerprint(prompt.mnemonic, passphrase)

    private fun start(block: suspend () -> Unit) {
        request?.cancel()
        request = scope.launch { block() }
    }

    /** Asks for [prompts] in turn. False when the user backs out of one. */
    private suspend fun askAll(prompts: List<Prompt>): Boolean {
        for (prompt in prompts) {
            current = prompt
            val passphrase = try {
                prompt.answer.await()
            } finally {
                // A dropped request must not close the prompt of the one that replaced it
                if (current === prompt) current = null
            }
            prompt.enter(passphrase ?: return false)
        }
        return true
    }

    /** Null when the wallet's key can't be read. */
    private suspend fun walletPrompt(metadata: WalletMetadata, note: String?): Prompt? {
        val mnemonic = wallet.getMnemonicForWallet(metadata.id) ?: return null
        return Prompt(metadata.name, metadata.masterFingerprint, note, mnemonic) { passphrase ->
            wallet.setSessionPassphrase(metadata.id, passphrase)
        }
    }

    private suspend fun multisigPrompts(walletId: String): List<Prompt> =
        wallet.getKeysNeedingPassphrase(walletId).map { key ->
            Prompt(key.label, key.fingerprint, note = null, key.mnemonic) { passphrase ->
                wallet.setSessionKeyPassphrase(key.keyId, passphrase)
            }
        }
}

@Composable
fun rememberPassphrasePromptState(wallet: WalletPassphrases): PassphrasePromptState {
    val scope = rememberCoroutineScope()
    return remember(wallet, scope) { PassphrasePromptState(wallet, scope) }
}

/** The dialog for whatever [state] is asking for; nothing when it isn't. */
@Composable
fun PassphrasePrompt(state: PassphrasePromptState) {
    val prompt = state.current ?: return
    // Each prompt is its own dialog, so nothing typed for one key carries over to the next
    key(prompt) {
        PassphraseEntryDialog(
            label = prompt.label,
            originalFingerprint = prompt.fingerprint,
            onDismiss = { state.cancel() },
            onConfirm = { passphrase, _ -> state.confirm(passphrase) },
            calculateFingerprint = { passphrase -> state.fingerprint(prompt, passphrase) },
            note = prompt.note
        )
    }
}
