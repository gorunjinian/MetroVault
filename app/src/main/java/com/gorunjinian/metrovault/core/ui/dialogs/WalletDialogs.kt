package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.Wallet

private const val MAX_WALLET_NAME_LENGTH = 25

@Composable
fun RenameWalletDialog(
    currentName: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit
) {
    var newName by remember { mutableStateOf(currentName.take(MAX_WALLET_NAME_LENGTH)) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Rename Wallet") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.End
            ) {
                SecureOutlinedTextField(
                    value = newName,
                    onValueChange = { 
                        if (it.length <= MAX_WALLET_NAME_LENGTH) {
                            newName = it
                        }
                    },
                    label = { Text("Wallet Name") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${newName.length}/$MAX_WALLET_NAME_LENGTH",
                    style = MaterialTheme.typography.labelSmall,
                    color = if (newName.length >= MAX_WALLET_NAME_LENGTH) 
                        MaterialTheme.colorScheme.error 
                    else 
                        MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    if (newName.isNotBlank()) {
                        onConfirm(newName.trim())
                    }
                },
                enabled = newName.isNotBlank()
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
 * Reusable two-step delete wallet dialog flow.
 * Step 1: Confirmation dialog asking if user wants to delete
 * Step 2: Password verification before actual deletion
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
    // Track which step we're on
    var showPasswordDialog by remember { mutableStateOf(false) }
    
    // Reset password dialog state when wallet changes
    LaunchedEffect(walletToDelete) {
        if (walletToDelete == null) {
            showPasswordDialog = false
        }
    }
    
    // Step 1: Confirmation dialog
    if (walletToDelete != null && !showPasswordDialog) {
        AlertDialog(
            onDismissRequest = onDismiss,
            title = { Text("Delete Wallet") },
            text = { 
                Text("Are you sure you want to delete \"${walletToDelete.name}\"?\nThis action cannot be undone unless you have your seed phrase backed up.")
            },
            confirmButton = {
                TextButton(
                    onClick = { showPasswordDialog = true },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = onDismiss) {
                    Text("Cancel")
                }
            },
            icon = { 
                Icon(
                    painter = painterResource(R.drawable.ic_delete), 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error
                ) 
            }
        )
    }
    
    // Step 2: Password confirmation dialog
    if (walletToDelete != null && showPasswordDialog) {
        var password by remember { mutableStateOf("") }
        var passwordError by remember { mutableStateOf("") }
        val scope = rememberCoroutineScope()
        
        // Reset password when dialog opens
        LaunchedEffect(walletToDelete.id) {
            password = ""
            passwordError = ""
        }

        AlertDialog(
            onDismissRequest = { 
                showPasswordDialog = false
                onDismiss()
            },
            title = { Text("Confirm Deletion") },
            text = {
                Column {
                    Text("Enter your password to confirm deletion of \"${walletToDelete.name}\".")
                    Spacer(modifier = Modifier.height(8.dp))
                    SecureOutlinedTextField(
                        value = password,
                        onValueChange = { 
                            password = it
                            passwordError = ""
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        isError = passwordError.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError.isNotEmpty()) {
                        Text(
                            text = passwordError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val isDecoy = wallet.isDecoyMode
                            val isValid = withContext(Dispatchers.IO) {
                                if (isDecoy) {
                                    secureStorage.isDecoyPassword(password)
                                } else {
                                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                                }
                            }
                            
                            if (isValid) {
                                val deleted = wallet.deleteWallet(walletToDelete.id)
                                if (deleted) {
                                    showPasswordDialog = false
                                    onDeleted()
                                } else {
                                    passwordError = "Failed to delete wallet. Session might be expired."
                                }
                            } else {
                                passwordError = "Incorrect password"
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showPasswordDialog = false
                    onDismiss()
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}
