package com.gorunjinian.metrovault.feature.wallet.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.feature.scan.components.ScannerView

/**
 * Screen for importing a multisig wallet by scanning its output descriptor.
 * 
 * Flow:
 * 1. Scan QR code containing multisig descriptor
 * 2. Parse descriptor and match against local wallets
 * 3. Show confirmation with cosigner details
 * 4. Import as multisig wallet
 *
 * @param takeScan Returns, once, the scan another screen opened this one with, which starts the
 *   flow at step 2; null when there is none
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportMultisigScreen(
    viewModel: ImportMultisigViewModel = viewModel(),
    onBack: () -> Unit,
    onWalletImported: (walletId: String) -> Unit,
    takeScan: (() -> CompletedScan?)? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(Unit) {
        takeScan?.invoke()?.let { viewModel.onScan(it) }
    }

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportMultisigViewModel.ImportEvent.WalletImported -> onWalletImported(event.walletId)
                is ImportMultisigViewModel.ImportEvent.NavigateBack -> onBack()
            }
        }
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Import Multisig Wallet",
                onBack = onBack,
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
        ) {
            when (uiState.screenState) {
                ImportMultisigViewModel.ScreenState.INITIAL,
                ImportMultisigViewModel.ScreenState.SCANNING -> {
                    InitialScreen(
                        isScanning = uiState.isScanning,
                        onStartScanning = { viewModel.startScanning() },
                        onScan = { scan ->
                            // A payload that isn't a multisig setup ends on the error screen.
                            viewModel.onScan(scan)
                            true
                        },
                        onCancelScanning = { viewModel.cancelScanning() }
                    )
                }

                ImportMultisigViewModel.ScreenState.PARSED -> {
                    ParsedDescriptorScreen(
                        parsedConfig = uiState.parsedConfig!!,
                        walletName = uiState.walletName,
                        isImporting = uiState.isImporting,
                        isBsmsFormat = uiState.isBsmsFormat,
                        addressVerified = uiState.addressVerified,
                        onWalletNameChange = { viewModel.setWalletName(it) },
                        onConfirmImport = { viewModel.importMultisigWallet() },
                        onCancel = { viewModel.resetToInitial() }
                    )
                }
                
                ImportMultisigViewModel.ScreenState.ERROR -> {
                    ErrorScreen(
                        errorMessage = uiState.errorMessage,
                        onRetry = { viewModel.resetToInitial() }
                    )
                }
            }
        }
    }
}

@Composable
private fun InitialScreen(
    isScanning: Boolean,
    onStartScanning: () -> Unit,
    onScan: (CompletedScan) -> Boolean,
    onCancelScanning: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(modifier = Modifier.height(32.dp))

        // Info card
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Import an Existing Multi-Sig Wallet",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Scan the multisig wallet's output descriptor QR code from the online wallet or coordinator.",
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Supported formats hint
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = "Supported Formats",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Medium
                )
                Spacer(modifier = Modifier.height(8.dp))
                Text(

                    text = "• Plain text descriptor\n• UR-encoded descriptor\n• BSMS QR code\n• ColdCard multisig setup",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // Inline QR Scanner (shown when scanning)
        if (isScanning) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                ScannerView(
                    onScan = onScan,
                    modifier = Modifier.fillMaxSize(),
                    showViewfinder = false
                )
            }

            Spacer(modifier = Modifier.height(16.dp))
        } else {
            // Empty space when not scanning
            Spacer(modifier = Modifier.weight(1f))
        }

        // Scan/Cancel button
        if (isScanning) {
            OutlinedButton(
                onClick = onCancelScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Cancel")
            }
        } else {
            Button(
                onClick = onStartScanning,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Descriptor QR Code")
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ParsedDescriptorScreen(
    parsedConfig: com.gorunjinian.metrovault.data.model.MultisigConfig,
    walletName: String,
    isImporting: Boolean,
    isBsmsFormat: Boolean = false,
    addressVerified: Boolean? = null,
    onWalletNameChange: (String) -> Unit,
    onConfirmImport: () -> Unit,
    onCancel: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
    ) {
        Spacer(modifier = Modifier.height(16.dp))
        
        // Success header
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "${parsedConfig.m}-of-${parsedConfig.n} Multisig",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = "Descriptor scanned successfully",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.8f)
                )
            }
        }

        // BSMS verification status (if applicable)
        if (isBsmsFormat) {
            Spacer(modifier = Modifier.height(12.dp))
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = when (addressVerified) {
                        true -> MaterialTheme.colorScheme.secondaryContainer
                        false -> MaterialTheme.colorScheme.errorContainer
                        null -> MaterialTheme.colorScheme.surfaceVariant
                    }
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Icon(
                        painter = painterResource(
                            when (addressVerified) {
                                true -> R.drawable.ic_check_circle
                                false -> R.drawable.ic_warning
                                null -> R.drawable.ic_check_circle
                            }
                        ),
                        contentDescription = null,
                        modifier = Modifier.size(24.dp),
                        tint = when (addressVerified) {
                            true -> MaterialTheme.colorScheme.primary
                            false -> MaterialTheme.colorScheme.error
                            null -> MaterialTheme.colorScheme.onSurfaceVariant
                        }
                    )
                    Column {
                        Text(
                            text = "BSMS Format",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = when (addressVerified) {
                                true -> "Address verified successfully"
                                false -> "Address verification failed - proceed with caution"
                                null -> "No verification address provided"
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = when (addressVerified) {
                                true -> MaterialTheme.colorScheme.onSecondaryContainer
                                false -> MaterialTheme.colorScheme.onErrorContainer
                                null -> MaterialTheme.colorScheme.onSurfaceVariant
                            }
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))
        
        // Wallet name input
        OutlinedTextField(
            value = walletName,
            onValueChange = onWalletNameChange,
            label = { Text("Wallet Name") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // Cosigners list
        Text(
            text = "Cosigners",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        parsedConfig.cosigners.forEachIndexed { index, cosigner ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (cosigner.isLocal) 
                        MaterialTheme.colorScheme.secondaryContainer 
                    else 
                        MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Key ${index + 1}",
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.Medium
                        )
                        Text(
                            text = cosigner.fingerprint.uppercase(),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Normal
                        )
                        Text(
                            text = cosigner.xpub.take(20) + "...",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    if (cosigner.isLocal) {
                        Surface(
                            color = MaterialTheme.colorScheme.primary,
                            contentColor = MaterialTheme.colorScheme.onPrimary,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Local",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
            }
            
            if (index < parsedConfig.cosigners.size - 1) {
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
        
        Spacer(modifier = Modifier.height(24.dp))
        
        // Local keys count info
        val localCount = parsedConfig.localKeyFingerprints.size
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.tertiaryContainer
            )
        ) {
            Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "$localCount of ${parsedConfig.n} keys available locally for signing",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onTertiaryContainer
                )
            }
        }
        
        Spacer(modifier = Modifier.weight(1f))
        Spacer(modifier = Modifier.height(16.dp))
        
        // Import button
        Button(
            onClick = onConfirmImport,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isImporting && walletName.isNotBlank()
        ) {
            if (isImporting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(modifier = Modifier.width(8.dp))
            }
            Text(if (isImporting) "Importing..." else "Import Multisig Wallet")
        }
        
        Spacer(modifier = Modifier.height(8.dp))
        
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isImporting
        ) {
            Text("Cancel")
        }
        
        Spacer(modifier = Modifier.height(24.dp))
    }
}

@Composable
private fun ErrorScreen(
    errorMessage: String,
    onRetry: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            painter = painterResource(R.drawable.ic_warning),
            contentDescription = null,
            modifier = Modifier.size(64.dp),
            tint = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        Text(
            text = "Import Failed",
            style = MaterialTheme.typography.headlineSmall,
            color = MaterialTheme.colorScheme.error
        )
        
        Spacer(modifier = Modifier.height(8.dp))
        
        InfoCard(
            text = errorMessage,
            tone = InfoTone.Danger,
            textStyle = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center
        )
        
        Spacer(modifier = Modifier.height(24.dp))
        
        Button(
            onClick = onRetry,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Try Again")
        }
    }
}
