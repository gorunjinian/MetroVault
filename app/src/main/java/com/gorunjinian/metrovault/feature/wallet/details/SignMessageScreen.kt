package com.gorunjinian.metrovault.feature.wallet.details

import android.Manifest
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.QRDensity
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.feature.transaction.components.PSBTScannerView
import com.gorunjinian.metrovault.feature.transaction.components.SignedPSBTDisplay
import com.gorunjinian.vaultovich.MessageSigning
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Sign/Verify Message Screen.
 *
 * Sign messages with the wallet's keys (Electrum / BIP-137 / BIP-322) or verify signatures.
 * Silent-payment wallets are BIP-322 only, and sign via the watching wallet's message-PSBT QR:
 * scanning it only fills in the request (address + message) — the user reviews and presses Sign —
 * and the signed PSBT is then presented with [SignedPSBTDisplay], the same animated multi-format
 * QR display used by the Sign PSBT flow. All flow state and protocol calls live in
 * [SignMessageViewModel]; this file is UI plus camera plumbing only.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignMessageScreen(
    viewModel: SignMessageViewModel = viewModel(),
    onBack: () -> Unit,
    prefilledAddress: String? = null
) {
    val uiState by viewModel.uiState.collectAsState()

    var hasCameraPermission by remember { mutableStateOf(false) }
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }
    var isLifecycleResumed by remember { mutableStateOf(false) }
    var showDensityMenu by remember { mutableStateOf(false) }

    val context = LocalContext.current

    LaunchedEffect(Unit) {
        viewModel.applyPrefilledAddress(prefilledAddress)
    }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            viewModel.startScanning()
        }
    }

    // Camera lifecycle (mirrors ScanPSBTScreen): resume only when the view exists, the lifecycle
    // is resumed, and the scanner is on screen.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isLifecycleResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    isLifecycleResumed = false
                    barcodeView?.pause()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            barcodeView?.pause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    LaunchedEffect(barcodeView, isLifecycleResumed, uiState.isScanning) {
        if (barcodeView != null && isLifecycleResumed && uiState.isScanning) {
            barcodeView?.resume()
        }
    }
    LaunchedEffect(uiState.isScanning) {
        if (!uiState.isScanning) {
            barcodeView?.pause()
            barcodeView = null
        }
    }

    /** Advance the animated signed-PSBT QR while it's visible. */
    LaunchedEffect(uiState.signedQRResult, uiState.isQRPaused, uiState.showSignedPsbtDisplay) {
        val result = uiState.signedQRResult ?: return@LaunchedEffect
        if (uiState.showSignedPsbtDisplay && result.isAnimated && result.frames.size > 1 && !uiState.isQRPaused) {
            while (true) {
                delay(result.recommendedFrameDelayMs.milliseconds)
                viewModel.advanceDisplayFrame()
            }
        }
    }

    // Back gesture: close the scanner or the signed-PSBT display before leaving the screen
    BackHandler(enabled = uiState.isScanning || uiState.showSignedPsbtDisplay) {
        if (uiState.isScanning) viewModel.stopScanning() else viewModel.setShowSignedPsbtDisplay(false)
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = if (uiState.isScanning) {
                    if (uiState.scanTarget == SignMessageViewModel.ScanTarget.ADDRESS) {
                        "Scan Address QR"
                    } else {
                        "Scan Message QR"
                    }
                } else {
                    "Sign/Verify Message"
                },
                onBack = {
                        when {
                            uiState.isScanning -> viewModel.stopScanning()
                            uiState.showSignedPsbtDisplay -> viewModel.setShowSignedPsbtDisplay(false)
                            else -> onBack()
                        }
                    },
                actions = {
                    // QR density control, only while the signed message PSBT is displayed
                    if (uiState.showSignedPsbtDisplay && uiState.signedQRResult != null) {
                        Box {
                            IconButton(onClick = { showDensityMenu = true }) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_density),
                                    contentDescription = "QR Density"
                                )
                            }
                            DropdownMenu(
                                expanded = showDensityMenu,
                                onDismissRequest = { showDensityMenu = false }
                            ) {
                                QRDensity.entries.forEach { density ->
                                    DropdownMenuItem(
                                        text = { Text(density.displayName) },
                                        onClick = {
                                            viewModel.setDensity(density)
                                            showDensityMenu = false
                                        },
                                        leadingIcon = if (uiState.selectedDensity == density) {
                                            {
                                                Icon(
                                                    painter = painterResource(R.drawable.ic_check),
                                                    contentDescription = null
                                                )
                                            }
                                        } else null
                                    )
                                }
                            }
                        }
                    }
                }
            )
        }
    ) { padding ->
        val signatureQRBitmap = uiState.signatureQRBitmap
        if (uiState.showSignatureQR && signatureQRBitmap != null) {
            SignatureQrDialog(
                bitmap = signatureQRBitmap,
                onDismiss = { viewModel.dismissSignatureQr() }
            )
        }

        when {
            uiState.isScanning -> {
                // The exact Scan PSBT viewfinder (frame flash + animated-QR progress card), with
                // plain payloads (addresses, messages) delivered as a single-shot scan.
                PSBTScannerView(
                    hasCameraPermission = hasCameraPermission,
                    animatedScanner = viewModel.animatedScanner,
                    scanProgress = uiState.scanProgress,
                    isAnimatedScan = uiState.isAnimatedScan,
                    errorMessage = "",
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onScanProgress = { progress, animated ->
                        viewModel.onScanProgress(progress, animated)
                    },
                    onScanComplete = { psbt, _ ->
                        viewModel.onScanComplete(psbt)
                    },
                    onBarcodeViewCreated = { barcodeView = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onPlainScan = { result ->
                        viewModel.onPlainScan(result)
                    }
                )
            }

            // The signed message PSBT, with the full format/playback controls of the PSBT flow.
            uiState.showSignedPsbtDisplay && uiState.signedQRResult != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    SignedPSBTDisplay(
                        signedQRResult = uiState.signedQRResult!!,
                        currentFrame = uiState.currentDisplayFrame,
                        selectedFormat = uiState.selectedOutputFormat,
                        isPaused = uiState.isQRPaused,
                        isLoading = uiState.isRegeneratingQR,
                        title = "Message Signed",
                        scanAnotherLabel = "Scan Another Message PSBT",
                        onPauseToggle = { viewModel.setQRPaused(it) },
                        onPreviousFrame = { viewModel.previousDisplayFrame() },
                        onNextFrame = { viewModel.advanceDisplayFrame() },
                        onFormatChange = { viewModel.setOutputFormat(it) },
                        onScanAnother = {
                            viewModel.prepareScanAnother()
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onDone = { viewModel.setShowSignedPsbtDisplay(false) }
                    )
                }
            }

            else -> {
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                        .padding(24.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    // Info Card
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = if (uiState.isSilentPayment) {
                                "Verify signatures, or sign messages for your silent payment addresses by " +
                                "scanning the message-signing QR from the watching wallet."
                            } else {
                                "Sign messages to prove ownership of an address, or verify signatures from others."
                            },
                            modifier = Modifier.padding(16.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }

                    // Signature Format Toggle. SP wallets can only use BIP-322 — the ECDSA formats
                    // can't bind to their one-off taproot addresses.
                    val formats = MessageSigning.SignatureFormat.entries
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Signature Format",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        SegmentedToggle(
                            options = formats.map { it.displayName },
                            selectedIndex = formats.indexOf(uiState.signatureFormat),
                            onSelect = { viewModel.setSignatureFormat(formats[it]) },
                            compact = true,
                            itemEnabled = { index ->
                                !uiState.isSilentPayment || formats[index] == MessageSigning.SignatureFormat.BIP322
                            }
                        )
                    }

                    // A staged message-signing request awaiting the user's consent to sign
                    if (uiState.pendingMessagePsbt != null) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.tertiaryContainer
                            )
                        ) {
                            Text(
                                text = "Message-signing request from the watching wallet. Review the " +
                                       "address and message below, then press Sign.",
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onTertiaryContainer
                            )
                        }
                    }

                    // Address Input with QR Scanner
                    SecureOutlinedTextField(
                        value = uiState.addressInput,
                        onValueChange = { viewModel.setAddressInput(it) },
                        label = { Text("Bitcoin Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !uiState.isProcessing,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    viewModel.prepareScan(SignMessageViewModel.ScanTarget.ADDRESS)
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                enabled = !uiState.isProcessing
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                                    contentDescription = "Scan QR"
                                )
                            }
                        }
                    )

                    // Message Input (larger)
                    SecureOutlinedTextField(
                        value = uiState.messageInput,
                        onValueChange = { viewModel.setMessageInput(it) },
                        label = { Text("Message") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        minLines = 4,
                        maxLines = 10,
                        enabled = !uiState.isProcessing
                    )

                    // Signature Input
                    SecureOutlinedTextField(
                        value = uiState.signatureInput,
                        onValueChange = { viewModel.setSignatureInput(it) },
                        label = { Text("Signature") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !uiState.isProcessing,
                        trailingIcon = {
                            if (uiState.signatureInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        // Signatures are public — no auto-clear
                                        SecurityUtils.copyToClipboard(
                                            context = context,
                                            label = "Signature",
                                            text = uiState.signatureInput,
                                            sensitive = false
                                        )
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_copy),
                                        contentDescription = "Copy signature"
                                    )
                                }
                            }
                        }
                    )

                    IconTextButton(
                        text = "Sign by QR",
                        iconRes = R.drawable.ic_qr_code_scanner,
                        enabled = !uiState.isProcessing,
                        onClick = {
                            viewModel.prepareScan(SignMessageViewModel.ScanTarget.MESSAGE)
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )

                    if (uiState.signatureInput.isNotBlank()) {
                        IconTextButton(
                            text = "Show Signature QR",
                            iconRes = R.drawable.ic_qr_code_2,
                            enabled = !uiState.isProcessing,
                            onClick = { viewModel.showSignatureQr() }
                        )
                    }

                    // After signing a scanned message PSBT, the watching wallet needs the PSBT back.
                    if (uiState.signedQRResult != null) {
                        IconTextButton(
                            text = "Show Signed PSBT QR",
                            iconRes = R.drawable.ic_qr_code_2,
                            enabled = !uiState.isProcessing,
                            onClick = { viewModel.setShowSignedPsbtDisplay(true) }
                        )
                    }

                    if (uiState.errorMessage.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = uiState.errorMessage,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (uiState.successMessage.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = uiState.successMessage,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Sign/Verify Button
                    Button(
                        onClick = { viewModel.signOrVerify() },
                        modifier = Modifier.fillMaxWidth(),
                        enabled = (uiState.canSign || uiState.canVerify) && !uiState.isProcessing
                    ) {
                        if (uiState.isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (uiState.canVerify) "Verify" else "Sign")
                        }
                    }

                    // Clear button
                    if (uiState.addressInput.isNotBlank() || uiState.messageInput.isNotBlank() ||
                        uiState.signatureInput.isNotBlank()
                    ) {
                        OutlinedButton(
                            onClick = { viewModel.clearAll() },
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !uiState.isProcessing
                        ) {
                            Text("Clear All")
                        }
                    }
                }
            }
        }
    }
}

/** A full-width outlined button with a leading icon (the screen's recurring action shape). */
@Composable
private fun IconTextButton(
    text: String,
    iconRes: Int,
    enabled: Boolean,
    onClick: () -> Unit
) {
    OutlinedButton(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        enabled = enabled
    ) {
        Icon(
            painter = painterResource(iconRes),
            contentDescription = null,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(text)
    }
}

/** A dialog presenting the bare message signature as a QR code. */
@Composable
private fun SignatureQrDialog(
    bitmap: android.graphics.Bitmap,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surface
            )
        ) {
            Column(
                modifier = Modifier.padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Signature QR Code",
                    style = MaterialTheme.typography.titleLarge
                )
                Spacer(modifier = Modifier.height(16.dp))
                Card(
                    modifier = Modifier.size(280.dp)
                ) {
                    Image(
                        bitmap = bitmap.asImageBitmap(),
                        contentDescription = "Signature QR Code",
                        modifier = Modifier.fillMaxSize()
                    )
                }
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "Scan to verify the signature",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(16.dp))
                Button(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Close")
                }
            }
        }
    }
}
