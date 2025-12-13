package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.util.SecurityUtils

/**
 * FIX: Critical Bug #2 - Duplicate word index bug
 *
 * Problem: Used mnemonic.indexOf(word) which returns the FIRST occurrence.
 * If a mnemonic has duplicate words (valid in BIP39), both would show the same index.
 * Example: "apple banana cherry apple" would show "1. apple" for both occurrences.
 *
 * Solution: Track the actual index using the loop index, not indexOf().
 */

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    var showSeedPhrase by remember { mutableStateOf(false) }
    var showXPub by remember { mutableStateOf(false) }
    var xpubQR by remember { mutableStateOf<Bitmap?>(null) }

    var showWarningDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }

    val xpub = remember<String>(wallet) { wallet.getActiveXpub() ?: "" }
    val keyPrefix = when {
        xpub.startsWith("zpub") -> "zpub"
        xpub.startsWith("ypub") -> "ypub"
        else -> "xpub"
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (showSeedPhrase) {
                // Show seed phrase
                val mnemonic = wallet.getActiveMnemonic() ?: emptyList()
                val is24Words = mnemonic.size == 24

                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "⚠️ Keep your seed phrase secret!",
                        modifier = Modifier.padding(if (is24Words) 12.dp else 16.dp),
                        style = if (is24Words) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    // Display words top-to-bottom in two columns
                    // For 12 words: Column 1 = 1-6, Column 2 = 7-12
                    // For 24 words: Column 1 = 1-12, Column 2 = 13-24
                    val wordsPerColumn = mnemonic.size / 2
                    val column1 = mnemonic.take(wordsPerColumn)
                    val column2 = mnemonic.drop(wordsPerColumn)
                    
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (is24Words) 8.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
                    ) {
                        // First column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
                        ) {
                            column1.forEachIndexed { index, word ->
                                val wordNumber = index + 1
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = "$wordNumber. $word",
                                        modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        // Second column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
                        ) {
                            column2.forEachIndexed { index, word ->
                                val wordNumber = wordsPerColumn + index + 1
                                Card(
                                    colors = CardDefaults.cardColors(
                                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                                    )
                                ) {
                                    Text(
                                        text = "$wordNumber. $word",
                                        modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                    }
                }

                Button(
                    onClick = { showSeedPhrase = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hide Seed Phrase")
                }
            } else if (showXPub) {
                // Show XPUB
                val context = LocalContext.current

                // Generate QR code only once when this view is shown
                // Using LaunchedEffect to prevent regeneration on every recomposition,
                // which was causing choppy back navigation animations
                LaunchedEffect(xpub) {
                    if (xpub.isNotEmpty()) {
                        xpubQR = QRCodeUtils.generateQRCode(xpub)
                    }
                }

                Text(
                    text = "Extended Public Key ($keyPrefix)",
                    style = MaterialTheme.typography.titleMedium
                )

                xpubQR?.let { qr ->
                    Card(
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                            .clickable {
                                // Copy xpub to clipboard with auto-clear after 20 seconds
                                SecurityUtils.copyToClipboardWithAutoClear(
                                    context = context,
                                    label = "Extended Public Key",
                                    text = xpub,
                                    delayMs = 20_000
                                )
                                Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "xPub QR Code - Tap to copy",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Text(
                    text = "Tap QR code to copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = xpub,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }

                Button(
                    onClick = { showXPub = false },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Hide Extended Public Key")
                }
            } else {
                // Show options
                Text(
                    text = "Export Options",
                    style = MaterialTheme.typography.headlineSmall
                )

                ElevatedCard(
                    onClick = { showWarningDialog = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_key),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "View Seed Phrase",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Show your recovery seed phrase",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }

                ElevatedCard(
                    onClick = { showXPub = true },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_visibility),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "View Account Public Key",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Export extended public key (watch-only)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text("Your seed phrase is the master key to your funds. Never share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWarningDialog = false
                        showPasswordDialog = true
                    }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = { Icon(Icons.Default.Warning, contentDescription = null) }
        )
    }

    if (showPasswordDialog) {
        var password by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = {
                showPasswordDialog = false
                passwordError = ""
            },
            title = { Text("Authentication Required") },
            text = {
                Column {
                    Text("Enter your password to view the seed phrase.")
                    Spacer(modifier = Modifier.height(8.dp))
                    SecureOutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = ""
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
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
                        val isDecoy = wallet.isDecoyMode
                        val isValid = if (isDecoy) {
                            secureStorage.isDecoyPassword(password)
                        } else {
                            secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                        }

                        if (isValid) {
                            showPasswordDialog = false
                            showSeedPhrase = true
                        } else {
                            passwordError = "Incorrect password"
                        }
                    }
                ) {
                    Text("Confirm")
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showPasswordDialog = false
                    passwordError = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}