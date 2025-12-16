package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField

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

