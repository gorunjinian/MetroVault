package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.CopyableValueCard
import com.gorunjinian.metrovault.core.ui.dialogs.PasswordGatedWarningDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RevealedKeysDialog
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.data.model.WalletFeature
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressDetailScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    address: String,
    addressIndex: Int,
    isChange: Boolean,
    onBack: () -> Unit,
    onSignMessage: (String) -> Unit
) {
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Show Keys dialog states
    var showRevealGate by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var addressKeys by remember { mutableStateOf<AddressService.AddressKeyPair?>(null) }

    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()
    val canSignMessage = remember {
        wallet.getActiveWalletProfile()?.supports(WalletFeature.SIGN_MESSAGE) == true
    }


    // Generate QR code on background thread to avoid blocking UI animation
    LaunchedEffect(address) {
        withContext(Dispatchers.IO) {
            val bitmap = QRCodeUtils.generateAddressQRCode(address)
            qrBitmap = bitmap
        }
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Address Details",
                onBack = onBack
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
                            .then(
                                if (tapToCopyEnabled) {
                                    Modifier.clickable {
                                        SecurityUtils.copyToClipboard(
                                            context = context,
                                            label = "Bitcoin Address",
                                            text = address,
                                            sensitive = false
                                        )
                                        Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                                    }
                                } else Modifier
                            )
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

            if (tapToCopyEnabled) {
                Text(
                    text = "Tap QR code to copy address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
            
            if (canSignMessage) {
                Button(
                    onClick = { onSignMessage(address) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Message")
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Show Keys button
            OutlinedButton(
                onClick = { showRevealGate = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Show Keys")
            }
            
            Spacer(modifier = Modifier.height(8.dp))
            
            // Back to Addresses button
            OutlinedButton(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Addresses")
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
    
    // Show Keys: security warning, then password
    if (showRevealGate) {
        PasswordGatedWarningDialog(
            title = "Security Warning",
            confirmLabel = "I Understand",
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { showRevealGate = false },
            onVerified = {
                addressKeys = wallet.getAddressKeys(
                    index = addressIndex,
                    isChange = isChange
                )
                showRevealGate = false
                showKeysDialog = true
            },
            destructive = false
        ) {
            Text("The private key can spend all funds sent to this address.\n\nNever share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
        }
    }

    // Keys Display Dialog
    val keys = addressKeys
    if (showKeysDialog && keys != null) {
        RevealedKeysDialog(
            title = "Address Keys",
            onDismiss = {
                showKeysDialog = false
                addressKeys = null
            },
            spacing = 20.dp
        ) {
            // Public Key
            CopyableValueCard(
                value = keys.publicKey,
                clipboardLabel = "Public Key",
                label = "Public Key",
                sensitive = false,
                textStyle = MaterialTheme.typography.bodyLarge
            )

            // Private Key (WIF) — sensitive, so the clipboard entry self-clears
            CopyableValueCard(
                value = keys.privateKeyWIF,
                clipboardLabel = "Private Key",
                label = "Private Key",
                sensitive = true,
                textStyle = MaterialTheme.typography.bodyLarge
            )
        }
    }
}
