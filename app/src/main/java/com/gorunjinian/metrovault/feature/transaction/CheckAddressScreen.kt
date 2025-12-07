package com.gorunjinian.metrovault.feature.transaction

import android.Manifest
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * FIX: Critical Bug #1 - CheckAddressScreen claimed to scan 1000 addresses but only scanned 40.
 *
 * Changes:
 * - Now actually scans 1000 addresses (500 receive + 500 change) as promised
 * - Scans in batches of 100 to avoid memory issues
 * - Runs on IO dispatcher to prevent UI blocking
 * - Shows progress indicator with current scan status
 * - Early exit when match is found for better performance
 */

private const val ADDRESSES_TO_SCAN = 500  // Per type (receive/change) = 1000 total
private const val BATCH_SIZE = 100

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CheckAddressScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    var addressInput by remember { mutableStateOf("") }
    var isChecking by remember { mutableStateOf(false) }
    var checkResult by remember { mutableStateOf<BitcoinAddress?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var scanProgress by remember { mutableStateOf("") }
    var isScanning by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isScanning = true
        }
    }

    // Barcode scanner reference
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }

    // Lifecycle observer for scanner - manages resume/pause based on lifecycle and scanning state
    DisposableEffect(lifecycleOwner, isScanning) {
        val observer = LifecycleEventObserver { _, event ->
            // Only manage scanner lifecycle when scanning is active and view is initialized
            val scanner = barcodeView
            if (isScanning && scanner != null) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        try {
                            scanner.resume()
                        } catch (e: Exception) {
                            android.util.Log.e("CheckAddressScreen", "Failed to resume scanner", e)
                        }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        try {
                            scanner.pause()
                        } catch (e: Exception) {
                            android.util.Log.e("CheckAddressScreen", "Failed to pause scanner", e)
                        }
                    }
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)

        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            // Ensure scanner is paused when effect is disposed
            try {
                barcodeView?.pause()
            } catch (e: Exception) {
                android.util.Log.e("CheckAddressScreen", "Failed to pause scanner on dispose", e)
            }
        }
    }

    // Pause scanner when isScanning becomes false
    LaunchedEffect(isScanning) {
        if (!isScanning) {
            try {
                barcodeView?.pause()
            } catch (e: Exception) {
                android.util.Log.e("CheckAddressScreen", "Failed to pause scanner", e)
            }
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Check Address") },
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
            Text(
                text = "Verify Address Ownership",
                style = MaterialTheme.typography.headlineSmall
            )

            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Enter a Bitcoin address to check if it belongs to your wallet. This will scan the first ${ADDRESSES_TO_SCAN * 2} addresses (${ADDRESSES_TO_SCAN} receive + $ADDRESSES_TO_SCAN change).",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            SecureOutlinedTextField(
                value = addressInput,
                onValueChange = { addressInput = it },
                label = { Text("Bitcoin Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isChecking && !isScanning
            )

            OutlinedButton(
                onClick = {
                    // Request camera permission and start scanning
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isChecking && !isScanning
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan Address QR Code")
            }

            // QR Scanner view
            if (isScanning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
                ) {
                    Box(modifier = Modifier.fillMaxSize()) {
                        AndroidView(
                            factory = { context ->
                                CompoundBarcodeView(context).apply {
                                    barcodeView = this
                                    setStatusText("")
                                    decodeContinuous { result ->
                                        result.text?.let { scannedText ->
                                            // Extract address from scanned text
                                            // Handle bitcoin: URI format if present
                                            val address = if (scannedText.startsWith("bitcoin:", ignoreCase = true)) {
                                                scannedText
                                                    .removePrefix("bitcoin:")
                                                    .removePrefix("BITCOIN:")
                                                    .split("?")[0] // Remove any query parameters
                                            } else {
                                                scannedText
                                            }
                                            addressInput = address.trim()
                                            isScanning = false
                                            pause()
                                        }
                                    }
                                    resume()
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Cancel button overlay
                        Button(
                            onClick = {
                                isScanning = false
                                barcodeView?.pause()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text("Cancel Scan")
                        }
                    }
                }
            }

            if (checkResult != null) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(12.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check_circle),
                                contentDescription = null,
                                modifier = Modifier.size(32.dp),
                                tint = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = "This address belongs to your wallet",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }

                        HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

                        Text(
                            text = "Address Index: ${checkResult!!.index}",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Type: ${if (checkResult!!.isChange) "Change" else "Receive"} address",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        Text(
                            text = "Path: ${checkResult!!.derivationPath}",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            if (errorMessage.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_error),
                            contentDescription = null,
                            modifier = Modifier.size(32.dp),
                            tint = MaterialTheme.colorScheme.onErrorContainer
                        )
                        Text(
                            text = errorMessage,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onErrorContainer
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.weight(1f))

            if (isChecking) {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    LinearProgressIndicator(
                        modifier = Modifier.fillMaxWidth()
                    )
                    Text(
                        text = scanProgress.ifEmpty { "Checking address..." },
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }

            Button(
                onClick = {
                    scope.launch {
                        isChecking = true
                        checkResult = null
                        errorMessage = ""
                        scanProgress = "Starting scan..."

                        val address = addressInput.trim()

                        // FIX: Actually scan 1000 addresses as promised
                        val match = withContext(Dispatchers.Default) {
                            scanForAddress(wallet, address) { progress ->
                                scanProgress = progress
                            }
                        }

                        if (match != null) {
                            checkResult = match
                        } else {
                            errorMessage = "This address does not belong to your wallet (scanned ${ADDRESSES_TO_SCAN * 2} addresses)"
                        }

                        scanProgress = ""
                        isChecking = false
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = addressInput.isNotBlank() && !isChecking
            ) {
                Text("Check Address")
            }
        }
    }
}

/**
 * Scans receive and change addresses in batches to find a match.
 * Uses early exit for better performance when match is found.
 *
 * @param wallet The wallet to scan addresses from
 * @param targetAddress The address to search for
 * @param onProgress Callback for progress updates
 * @return The matching BitcoinAddress if found, null otherwise
 */
private suspend fun scanForAddress(
    wallet: Wallet,
    targetAddress: String,
    onProgress: suspend (String) -> Unit
): BitcoinAddress? {
    // Scan receive addresses first
    for (batchStart in 0 until ADDRESSES_TO_SCAN step BATCH_SIZE) {
        val batchEnd = minOf(batchStart + BATCH_SIZE, ADDRESSES_TO_SCAN)
        onProgress("Scanning receive addresses ${batchStart + 1}-$batchEnd...")

        val addresses = wallet.generateAddresses(
            count = BATCH_SIZE,
            offset = batchStart,
            isChange = false
        )

        val match = addresses?.find { it.address == targetAddress }
        if (match != null) {
            return match
        }
    }

    // Scan change addresses
    for (batchStart in 0 until ADDRESSES_TO_SCAN step BATCH_SIZE) {
        val batchEnd = minOf(batchStart + BATCH_SIZE, ADDRESSES_TO_SCAN)
        onProgress("Scanning change addresses ${batchStart + 1}-$batchEnd...")

        val addresses = wallet.generateAddresses(
            count = BATCH_SIZE,
            offset = batchStart,
            isChange = true
        )

        val match = addresses?.find { it.address == targetAddress }
        if (match != null) {
            return match
        }
    }

    return null
}