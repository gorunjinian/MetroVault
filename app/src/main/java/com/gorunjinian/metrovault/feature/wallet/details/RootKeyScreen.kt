package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
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
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * RootKeyScreen - Displays the wallet's BIP32 root private key (xprv/tprv).
 * This screen should only be navigated to after password confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RootKeyScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    onBackToExportOptions: () -> Unit
) {
    val context = LocalContext.current
    
    // Get the BIP32 root key
    val rootKey = remember { wallet.getBIP32RootKey() }
    
    // QR code bitmap
    var currentQR by remember { mutableStateOf<Bitmap?>(null) }
    
    // Security: Clear sensitive data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            currentQR?.recycle()
            currentQR = null
            System.gc() // Hint to garbage collector
        }
    }
    
    // Generate QR code
    LaunchedEffect(rootKey) {
        if (rootKey.isNotEmpty()) {
            currentQR = null // Show loading
            withContext(Dispatchers.IO) {
                currentQR = QRCodeUtils.generateQRCode(rootKey)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BIP32 Root Key") },
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
            
            // Warning card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "This is your BIP32 master private key",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "This key can derive all private keys for your wallet. Anyone with this key has complete control over your funds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
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
                                    label = "BIP32 Root Key",
                                    text = rootKey,
                                    delayMs = 20_000
                                )
                                Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Image(
                            bitmap = currentQR!!.asImageBitmap(),
                            contentDescription = "BIP32 Root Key QR Code - Tap to copy",
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

            // Key text display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                )
            ) {
                Text(
                    text = rootKey,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            
            // Hide button
            Button(
                onClick = onBackToExportOptions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Hide Root Key")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
