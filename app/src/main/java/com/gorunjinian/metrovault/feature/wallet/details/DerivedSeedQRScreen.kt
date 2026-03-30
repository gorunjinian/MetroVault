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
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.lib.qrtools.QRModuleData
import com.gorunjinian.metrovault.lib.qrtools.SeedQRUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * DerivedSeedQRScreen - Displays a BIP85-derived seed phrase as a SeedQR code.
 * 
 * Supports Standard SeedQR, CompactSeedQR, and Generic (plain text) formats.
 * Same design as SeedQRScreen but for derived seeds.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DerivedSeedQRScreen(
    derivedMnemonic: List<String>,
    onBack: () -> Unit
) {
    val context = LocalContext.current

    // Format state: 0 = Standard SeedQR (default), 1 = Compact, 2 = Generic
    var selectedFormat by remember { mutableIntStateOf(0) }

    // Module data for Canvas rendering (SeedQR / Compact formats)
    var moduleData by remember { mutableStateOf<QRModuleData?>(null) }

    // Bitmap fallback for Generic format
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

    // Grid toggle state
    var showGrid by remember { mutableStateOf(true) }

    val isGridCapable = selectedFormat != 2

    // Security: Clear sensitive QR data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            qrBitmap?.recycle()
            qrBitmap = null
            moduleData = null
            System.gc() // Hint to garbage collector
        }
    }

    // Generate QR code when format changes
    LaunchedEffect(derivedMnemonic, selectedFormat) {
        moduleData = null
        qrBitmap = null
        withContext(Dispatchers.IO) {
            when (selectedFormat) {
                1 -> { // Compact SeedQR: binary module data
                    val bytes = SeedQRUtils.mnemonicToCompactSeedQR(derivedMnemonic, context)
                    moduleData = bytes?.let { QRCodeUtils.extractBinaryModuleData(it) }
                }
                2 -> { // Generic: plain space-separated words (bitmap only)
                    qrBitmap = QRCodeUtils.generateQRCode(derivedMnemonic.joinToString(" "), size = 512)
                }
                else -> { // Standard SeedQR: digit string module data
                    val digitString = SeedQRUtils.mnemonicToStandardSeedQR(derivedMnemonic, context)
                    moduleData = digitString?.let { QRCodeUtils.extractModuleData(it) }
                }
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Derived SeedQR") },
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

            // Grid overlay toggle (only for SeedQR/Compact)
            if (isGridCapable) {
                GridToggleRow(
                    showGrid = showGrid,
                    onToggle = { showGrid = it }
                )
            }

            // Security warning card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "This QR contains a derived seed phrase.\nAnyone who scans it can access its funds.",
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
                when {
                    moduleData != null -> {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                        ) {
                            SeedQRViewer(
                                moduleData = moduleData!!,
                                showGrid = showGrid,
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    qrBitmap != null -> {
                        Card(
                            modifier = Modifier.fillMaxSize(),
                            colors = CardDefaults.cardColors(
                                containerColor = Color.White
                            )
                        ) {
                            Image(
                                bitmap = qrBitmap!!.asImageBitmap(),
                                contentDescription = "Derived SeedQR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                    }
                    else -> {
                        CircularProgressIndicator()
                    }
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
            
            // Back button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Back to Derived Seed")
            }
        }
    }
}
