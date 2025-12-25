package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.lib.qrtools.SeedQRUtils
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SeedQRScreen - Displays the wallet's seed phrase as a SeedQR code.
 * 
 * Supports both Standard SeedQR and CompactSeedQR formats with a toggle switch.
 * Standard SeedQR is the default format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedQRScreen(
    mnemonic: List<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    
    // Handle system back gesture to go to Export Options (same as top bar back)
    BackHandler { onBack() }
    
    // Toggle state: false = Standard (default), true = Compact
    var isCompactFormat by remember { mutableStateOf(false) }
    
    // QR code bitmap state (null = loading)
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Generate QR code when format changes
    LaunchedEffect(mnemonic, isCompactFormat) {
        qrBitmap = null // Show loading immediately
        qrBitmap = withContext(Dispatchers.IO) {
            if (isCompactFormat) {
                // CompactSeedQR: binary data - use binary QR generator
                val bytes = SeedQRUtils.mnemonicToCompactSeedQR(mnemonic, context)
                if (bytes != null) {
                    // Use generateBinaryQRCode for proper binary encoding with ISO-8859-1
                    QRCodeUtils.generateBinaryQRCode(bytes, size = 512)
                } else null
            } else {
                // Standard SeedQR: digit string - use regular QR generator
                val digitString = SeedQRUtils.mnemonicToStandardSeedQR(mnemonic, context)
                if (digitString != null) {
                    QRCodeUtils.generateQRCode(digitString, size = 512)
                } else null
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("SeedQR") },
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
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))
            
            // Format toggle (same design as CreateWalletScreen seed length toggle)
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Standard SeedQR option (default)
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (!isCompactFormat) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { isCompactFormat = false }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "SeedQR",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (!isCompactFormat) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                
                // Compact SeedQR option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (isCompactFormat) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { isCompactFormat = true }
                        .padding(vertical = 10.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "Compact",
                        style = MaterialTheme.typography.labelLarge,
                        color = if (isCompactFormat) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            // Security warning card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "This QR contains your seed phrase.\nAnyone who scans it can steal your funds.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
            
            // QR Code display
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (qrBitmap != null) {
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Image(
                            bitmap = qrBitmap!!.asImageBitmap(),
                            contentDescription = "SeedQR Code",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else {
                    CircularProgressIndicator()
                }
            }
            
            // Info card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = if (isCompactFormat) {
                        "CompactSeedQR uses binary encoding for a smaller QR code. Requires a compatible reader."
                    } else {
                        "Standard SeedQR encodes word indices as digits. Can be decoded with any QR reader."
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Back button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Export Options")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
