package com.gorunjinian.metrovault.feature.wallet.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.BitcoinService
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressDetailScreen(
    wallet: Wallet,
    address: String,
    addressIndex: Int,
    isChange: Boolean,
    onBack: () -> Unit,
    onSignMessage: (String) -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Show Keys dialog states
    var showWarningDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var passwordInput by remember { mutableStateOf("") }
    var passwordError by remember { mutableStateOf("") }
    var addressKeys by remember { mutableStateOf<BitcoinService.AddressKeyPair?>(null) }

    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }



    // Generate QR code on background thread to avoid blocking UI animation
    LaunchedEffect(address) {
        withContext(Dispatchers.IO) {
            val bitmap = QRCodeUtils.generateAddressQRCode(address)
            qrBitmap = bitmap
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Address Details") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 16.dp)
                .padding(top = 32.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Top
        ) {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.size(320.dp)
            ) {
                if (qrBitmap != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                // Copy address to clipboard
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Bitcoin Address", address)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "QR Code - Tap to copy address",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }

            Spacer(modifier = Modifier.height(16.dp))

            Text(
                text = "Tap QR code to copy address",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = buildAnnotatedString {
                    // Normal text for all but last 5 characters
                    if (address.length > 5) {
                        append(address.dropLast(5))
                    }
                    // Bold style for the last 5 characters
                    withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                        append(address.takeLast(5))
                    }
                },
                style = MaterialTheme.typography.bodyMedium
            )

            Spacer(modifier = Modifier.height(24.dp))
            
            // Sign Message button
            Button(
                onClick = { onSignMessage(address) },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Sign Message")
            }
            
            Spacer(modifier = Modifier.height(8.dp))

            // Show Keys button
            OutlinedButton(
                onClick = { showWarningDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Keys")
            }
            
            // Address information
            Spacer(modifier = Modifier.height(16.dp))
            
            val derivationPath = wallet.getActiveDerivationPath()?.let { basePath ->
                val changeIndex = if (isChange) 1 else 0
                "$basePath/$changeIndex/$addressIndex"
            } ?: "Unknown"
            
            Text(
                text = "Derivation Path: $derivationPath",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = "Type: ${if (isChange) "Change" else "Receive"}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
    
    // Security Warning Dialog
    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text("The private key can spend all funds sent to this address.\n\nNever share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showWarningDialog = false
                        passwordError = ""
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
            icon = { Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null) }
        )
    }
    
    // Password Confirmation Dialog
    if (showPasswordDialog) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                passwordError = ""
            },
            onConfirm = { password ->
                if (secureStorage.verifyPasswordSimple(password) && 
                    !secureStorage.isDecoyPassword(password)) {
                    // Password correct - get the keys
                    addressKeys = wallet.getAddressKeys(
                        index = addressIndex,
                        isChange = isChange
                    )
                    showPasswordDialog = false
                    showKeysDialog = true
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }
    
    // Keys Display Dialog
    if (showKeysDialog && addressKeys != null) {
        androidx.compose.ui.window.Dialog(
            onDismissRequest = { 
                showKeysDialog = false
                addressKeys = null
            }
        ) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp, vertical = 16.dp),
                shape = MaterialTheme.shapes.extraLarge
            ) {
                Column(
                    modifier = Modifier.padding(24.dp),
                    verticalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Text(
                        text = "Address Keys",
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.Bold
                    )
                    
                    // Public Key
                    Column {
                        Text(
                            text = "Public Key",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Public Key", addressKeys!!.publicKey)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Public key copied", Toast.LENGTH_SHORT).show()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.secondaryContainer
                            )
                        ) {
                            Text(
                                text = addressKeys!!.publicKey,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }
                    
                    // Private Key (WIF)
                    Column {
                        Text(
                            text = "Private Key",
                            style = MaterialTheme.typography.titleSmall,
                            color = MaterialTheme.colorScheme.error
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Card(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                    val clip = ClipData.newPlainText("Private Key", addressKeys!!.privateKeyWIF)
                                    clipboard.setPrimaryClip(clip)
                                    Toast.makeText(context, "Private key copied (auto-clears in 20s)", Toast.LENGTH_SHORT).show()
                                },
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = addressKeys!!.privateKeyWIF,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyLarge,
                                fontFamily = FontFamily.Monospace,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                    
                    // Close button
                    TextButton(
                        onClick = { 
                            showKeysDialog = false
                            addressKeys = null
                        },
                        modifier = Modifier.align(Alignment.End)
                    ) {
                        Text("Close")
                    }
                }
            }
        }
    }
}
