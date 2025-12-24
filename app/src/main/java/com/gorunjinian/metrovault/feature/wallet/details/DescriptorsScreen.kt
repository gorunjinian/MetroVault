package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils

/**
 * DescriptorsScreen - Displays wallet output descriptors with QR codes.
 * Supports public/private toggle with password confirmation for private descriptors.
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptorsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    // For descriptors view: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }
    
    // QR code bitmap
    var currentQR by remember { mutableStateOf<Bitmap?>(null) }
    
    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }
    
    // Wallet data
    val publicDescriptor = remember(wallet) { wallet.getActiveUnifiedDescriptor() ?: "" }
    val privateDescriptor = remember(wallet) { wallet.getActivePrivateDescriptor() ?: "" }
    
    val displayDescriptor = if (showPrivate) privateDescriptor else publicDescriptor
    val descriptorType = if (showPrivate) "Spending" else "Watch-Only"
    
    // Generate QR code when display data changes
    LaunchedEffect(displayDescriptor) {
        if (displayDescriptor.isNotEmpty()) {
            currentQR = null // Show loading
            withContext(Dispatchers.IO) {
                currentQR = QRCodeUtils.generateQRCode(displayDescriptor)
            }
        }
    }
    
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Descriptors") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))
            
            // Public/Private Toggle
            PublicPrivateToggle(
                showPrivate = showPrivate,
                onSelectPublic = { showPrivate = false },
                onSelectPrivate = {
                    passwordError = ""
                    showPasswordDialog = true
                }
            )

            Spacer(modifier = Modifier.height(8.dp))

            // Title
            Text(
                text = "$descriptorType Descriptor",
                style = MaterialTheme.typography.titleMedium
            )

            // Info/Warning card
            if (showPrivate) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = "This descriptor contains private keys.\nAnyone with it can spend your UTXOs",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Import this to an online wallet as a watch-only wallet.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            // QR Code
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (currentQR != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .clickable {
                                SecurityUtils.copyToClipboardWithAutoClear(
                                    context = context,
                                    label = "$descriptorType Descriptor",
                                    text = displayDescriptor,
                                    delayMs = 20_000
                                )
                                Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Image(
                            bitmap = currentQR!!.asImageBitmap(),
                            contentDescription = "$descriptorType Descriptor QR Code - Tap to copy",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }

            Text(
                text = "Tap QR code to copy",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // Descriptor text display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (showPrivate) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Text(
                    text = displayDescriptor,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    
    // Password confirmation dialog
    if (showPasswordDialog) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                passwordError = ""
            },
            onConfirm = { password ->
                val isDecoy = wallet.isDecoyMode
                val isValid = if (isDecoy) {
                    secureStorage.isDecoyPassword(password)
                } else {
                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                }

                if (isValid) {
                    showPasswordDialog = false
                    showPrivate = true
                    passwordError = ""
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }
}
