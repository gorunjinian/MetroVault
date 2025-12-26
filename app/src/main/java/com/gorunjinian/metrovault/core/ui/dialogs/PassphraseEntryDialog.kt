package com.gorunjinian.metrovault.core.ui.dialogs

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import kotlinx.coroutines.delay

/**
 * Dialog for re-entering BIP39 passphrase when opening a wallet
 * that has hasPassphrase = true (passphrase not saved locally).
 * 
 * Shows a live-updating master fingerprint as user types.
 * Fingerprint is red if it doesn't match the original.
 */
@Composable
fun PassphraseEntryDialog(
    walletName: String,
    originalFingerprint: String,  // From metadata (for comparison, NOT displayed)
    onDismiss: () -> Unit,
    onConfirm: (passphrase: String, calculatedFingerprint: String) -> Unit,
    calculateFingerprint: suspend (passphrase: String) -> String?
) {
    var passphrase by remember { mutableStateOf("") }
    var currentFingerprint by remember { mutableStateOf("") }
    var isCalculating by remember { mutableStateOf(true) }  // Start as true to show initial calculation
    
    // Calculate fingerprint in real-time as user types (with debounce)
    LaunchedEffect(passphrase) {
        isCalculating = true
        delay(150)  // 150ms debounce
        val fingerprint = calculateFingerprint(passphrase)
        currentFingerprint = fingerprint ?: ""
        isCalculating = false
    }
    
    // Mismatch if: fingerprint calculated AND doesn't match original
    // OR if still calculating/empty (initial state = mismatch since empty passphrase != original)
    val fingerprintMismatch = currentFingerprint.isEmpty() || currentFingerprint != originalFingerprint
    
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Enter Passphrase") },
        text = {
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enter the BIP39 passphrase for \"$walletName\"",
                    style = MaterialTheme.typography.bodyMedium
                )
                
                // Passphrase input
                SecureOutlinedTextField(
                    value = passphrase,
                    onValueChange = { passphrase = it },
                    label = { Text("BIP39 Passphrase") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    isPasswordField = true
                )
                
                // Live fingerprint display
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(12.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Master Fingerprint:",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        if (isCalculating) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        } else {
                            Text(
                                text = currentFingerprint.ifEmpty { "..." },
                                style = MaterialTheme.typography.bodyMedium,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Bold,
                                color = if (fingerprintMismatch) 
                                    MaterialTheme.colorScheme.error
                                else 
                                    MaterialTheme.colorScheme.onSecondaryContainer
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onConfirm(passphrase, currentFingerprint) },
                enabled = currentFingerprint.isNotEmpty()
            ) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
