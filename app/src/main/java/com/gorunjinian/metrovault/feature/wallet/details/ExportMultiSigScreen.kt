package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.AnimatedQrDisplay
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.domain.service.multisig.BSMS
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import com.gorunjinian.metrovault.core.qr.ContentFormat
import com.gorunjinian.metrovault.core.qr.DescriptorQREncoder
import com.gorunjinian.metrovault.core.qr.OutputFormat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * ExportMultiSigScreen - Displays the multisig wallet descriptor as QR code.
 *
 * Supports two toggles:
 * 1. Content format: Descriptor (raw) or BSMS (formatted per BSMS 1.0 spec)
 * 2. QR encoding format: BC-UR v1, BBQr, BC-UR v2
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportMultiSigScreen(
    descriptor: String,
    firstAddress: String,
    onBack: () -> Unit
) {
    // Content format state: Descriptor or BSMS
    var selectedContentFormat by remember { mutableStateOf(ContentFormat.DESCRIPTOR) }

    // QR encoding format state
    var selectedQRFormat by remember { mutableStateOf(OutputFormat.BBQR) }

    // QR code result state
    var qrResult by remember { mutableStateOf<AnimatedQRResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }

    // Prepare content based on selected content format
    val contentToEncode = remember(descriptor, selectedContentFormat, firstAddress) {
        when (selectedContentFormat) {
            ContentFormat.DESCRIPTOR -> descriptor
            // BSMS (BIP-0129) Descriptor Record: 4 lines, LF separated
            ContentFormat.BSMS -> BSMS.formatDescriptor(descriptor, firstAddress)
        }
    }

    // Security: Clear QR data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            qrResult?.frames?.forEach { it.recycle() }
            qrResult = null
            System.gc()
        }
    }

    // Generate QR code when content or QR format changes
    LaunchedEffect(contentToEncode, selectedQRFormat, selectedContentFormat) {
        isLoading = true
        qrResult = withContext(Dispatchers.IO) {
            DescriptorQREncoder.encode(contentToEncode, selectedQRFormat, selectedContentFormat)
        }
        isLoading = false
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Export Descriptor",
                onBack = onBack
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

            // Content format toggle: Descriptor / BSMS
            SegmentedToggle(
                options = ContentFormat.entries.map { it.displayName },
                selectedIndex = ContentFormat.entries.indexOf(selectedContentFormat),
                onSelect = { index -> selectedContentFormat = ContentFormat.entries[index] },
                modifier = Modifier.fillMaxWidth()
            )

            // QR encoding format toggle: BC-UR v1 / BBQr / BC-UR v2
            SegmentedToggle(
                options = OutputFormat.entries.map { it.displayName },
                selectedIndex = OutputFormat.entries.indexOf(selectedQRFormat),
                onSelect = { index -> selectedQRFormat = OutputFormat.entries[index] },
                modifier = Modifier.fillMaxWidth()
            )

            // Info card about the descriptor
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = "Multisig wallet configuration. Import this descriptor into your coordinator wallet to watch or spend from this wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }

            // QR Code display with playback controls
            if (isLoading) {
                Box(
                    contentAlignment = Alignment.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .aspectRatio(1f)
                ) { CircularProgressIndicator() }
            } else {
                AnimatedQrDisplay(
                    qrResult = qrResult,
                    contentDescription = "Descriptor QR Code"
                )
            }

            // Done button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}
