package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import androidx.compose.foundation.Image
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
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.lib.qrtools.SeedQRUtils
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SeedQRScreen - Displays the wallet's seed phrase as a SeedQR code.
 * 
 * Supports Standard SeedQR, CompactSeedQR, and Generic (plain text) formats.
 * Standard SeedQR is the default format.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SeedQRScreen(
    mnemonic: List<String>,
    onBack: () -> Unit,
    onBackToExportOptions: () -> Unit
) {
    val context = LocalContext.current
    
    // Format state: 0 = Standard SeedQR (default), 1 = Compact, 2 = Generic
    var selectedFormat by remember { mutableIntStateOf(0) }
    
    // QR code bitmap state (null = loading)
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    
    // Security: Clear sensitive QR data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            qrBitmap?.recycle()
            qrBitmap = null
            System.gc() // Hint to garbage collector
        }
    }
    
    // Generate QR code when format changes
    LaunchedEffect(mnemonic, selectedFormat) {
        qrBitmap = null // Show loading immediately
        qrBitmap = withContext(Dispatchers.IO) {
            when (selectedFormat) {
                1 -> { // Compact SeedQR: binary data
                    val bytes = SeedQRUtils.mnemonicToCompactSeedQR(mnemonic, context)
                    bytes?.let { QRCodeUtils.generateBinaryQRCode(it, size = 512) }
                }
                2 -> { // Generic: plain space-separated words
                    QRCodeUtils.generateQRCode(mnemonic.joinToString(" "), size = 512)
                }
                else -> { // Standard SeedQR: digit string
                    val digitString = SeedQRUtils.mnemonicToStandardSeedQR(mnemonic, context)
                    digitString?.let { QRCodeUtils.generateQRCode(it, size = 512) }
                }
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
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Format toggle with 3 options
            SegmentedToggle(
                options = listOf("SeedQR", "Compact", "Generic"),
                selectedIndex = selectedFormat,
                onSelect = { selectedFormat = it },
                modifier = Modifier.fillMaxWidth()
            )
            
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
            
            // Info card with format-specific text
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = when (selectedFormat) {
                        1 -> "CompactSeedQR uses binary encoding for a smaller QR code. Requires a compatible reader."
                        2 -> "Generic format contains the seed words separated by spaces. Compatible with most wallets."
                        else -> "Standard SeedQR encodes word indices as digits. Can be decoded with any QR reader."
                    },
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Back button - goes directly to Export Options (skips SeedPhraseScreen)
            Button(
                onClick = onBackToExportOptions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Export Options")
            }
        }
    }
}
