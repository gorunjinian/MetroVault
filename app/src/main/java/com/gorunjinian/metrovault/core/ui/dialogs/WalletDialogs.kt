package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.TextRange
import androidx.compose.ui.text.input.TextFieldValue
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.Wallet
import kotlin.time.Duration.Companion.milliseconds

private const val MAX_NAME_LENGTH = 25

/**
 * Rename dialog for wallets and accounts: opens with the current name selected and the
 * keyboard up, caps the name at 25 characters, and returns it trimmed.
 */
@Composable
fun RenameDialog(
    title: String,
    label: String,
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    // Use TextFieldValue to control text selection
    var textFieldValue by remember {
        val initialText = currentName.take(MAX_NAME_LENGTH)
        mutableStateOf(TextFieldValue(initialText, TextRange(0, initialText.length)))
    }

    val focusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    // Request focus and show keyboard when dialog appears
    LaunchedEffect(Unit) {
        delay(100.milliseconds) // Small delay to ensure dialog is fully composed
        focusRequester.requestFocus()
        keyboardController?.show()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                SecureOutlinedTextField(
                    value = textFieldValue,
                    onValueChange = { newValue ->
                        if (newValue.text.length <= MAX_NAME_LENGTH) {
                            textFieldValue = newValue
                        }
                    },
                    label = { Text(label) },
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
                Text(
                    text = "${textFieldValue.text.length}/$MAX_NAME_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (textFieldValue.text.length >= MAX_NAME_LENGTH)
                        MaterialTheme.colorScheme.error
                    else
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (textFieldValue.text.isNotBlank()) {
                        onConfirm(textFieldValue.text.trim())
                    }
                },
                enabled = textFieldValue.text.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

/**
 * Reusable two-step delete wallet dialog flow: a warning, then password verification
 * before the actual deletion.
 *
 * @param walletToDelete The wallet metadata to delete, or null to hide dialogs
 * @param wallet The Wallet domain object for performing deletion
 * @param secureStorage SecureStorage for password verification
 * @param onDismiss Called when user cancels at any point
 * @param onDeleted Called after successful deletion (e.g., for navigation)
 */
@Composable
fun DeleteWalletDialogs(
    walletToDelete: WalletMetadata?,
    wallet: Wallet,
    secureStorage: SecureStorage,
    onDismiss: () -> Unit,
    onDeleted: () -> Unit
) {
    if (walletToDelete != null) {
        var passwordError by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()

        PasswordGatedWarningDialog(
            title = "Delete \"${walletToDelete.name}\"?",
            confirmLabel = "Delete",
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = onDismiss,
            onVerified = {
                scope.launch {
                    if (wallet.deleteWallet(walletToDelete.id)) {
                        onDeleted()
                    } else {
                        passwordError = "Failed to delete wallet. Session might be expired."
                    }
                }
            },
            errorMessage = passwordError
        ) {
            Text("This permanently removes the wallet from this device, along with any of its keys that no other wallet uses.")
            Text(
                if (walletToDelete.isMultisig) {
                    "To restore this wallet you'll need its descriptor or setup file, plus the seed phrases of the keys this device holds for it."
                } else {
                    "You'll need this wallet's seed phrase (and passphrase, if it has one) to access it again."
                }
            )
        }
    }
}
