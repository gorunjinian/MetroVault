package com.gorunjinian.metrovault.feature.wallet.details

import android.Manifest
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import com.gorunjinian.metrovault.core.qr.AnimatedQRScanner
import com.gorunjinian.metrovault.core.qr.OutputFormat
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.qr.QRDensity
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.bitcoin.WalletMessageSigner
import com.gorunjinian.metrovault.domain.service.psbt.PSBTDecoder
import com.gorunjinian.metrovault.feature.transaction.components.PSBTScannerView
import com.gorunjinian.metrovault.feature.transaction.components.SignedPSBTDisplay
import com.gorunjinian.metrovault.lib.bitcoin.MessageSigning
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/** What a single-shot scan fills in. PSBT frames are format-detected, independent of this. */
private enum class ScanTarget { ADDRESS, MESSAGE }

/**
 * Sign/Verify Message Screen.
 *
 * Sign messages with the wallet's keys (Electrum / BIP-137 / BIP-322) or verify signatures.
 * Silent-payment wallets are BIP-322 only, and sign via the watching wallet's message-PSBT QR:
 * scanning it only fills in the request (address + message) — the user reviews and presses Sign —
 * and the signed PSBT is then presented with [SignedPSBTDisplay], the same animated multi-format
 * QR display used by the Sign PSBT flow. All wallet and protocol logic lives in
 * [WalletMessageSigner]; this file is UI only.
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignMessageScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    prefilledAddress: String? = null
) {
    var addressInput by remember { mutableStateOf(prefilledAddress ?: "") }
    var messageInput by remember { mutableStateOf("") }
    var signatureInput by remember { mutableStateOf("") }

    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }

    var isScanning by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var scanTarget by remember { mutableStateOf(ScanTarget.ADDRESS) }

    // Scanner state — shares the Scan PSBT viewfinder (PSBTScannerView) so both look identical
    val animatedScanner = remember { AnimatedQRScanner(PSBTDecoder::decode) }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isAnimatedScan by remember { mutableStateOf(false) }
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }
    var isLifecycleResumed by remember { mutableStateOf(false) }

    // SP receive addresses are one-off taproot keys: only BIP-322 binds a signature to them,
    // so the ECDSA formats are disabled for silent-payment wallets.
    val isSilentPayment = DerivationPaths.getPurpose(wallet.getActiveWalletDerivationPath()) == 352

    // Signature format: Electrum (default), BIP-137, or BIP-322 (forced for SP wallets)
    var signatureFormat by remember {
        mutableStateOf(if (isSilentPayment) MessageSigning.SignatureFormat.BIP322 else MessageSigning.SignatureFormat.ELECTRUM)
    }

    // Bare signature QR dialog
    var signatureQRBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showSignatureQR by remember { mutableStateOf(false) }

    // A scanned-but-not-yet-signed BIP-322 message PSBT: signing waits for the Sign button.
    // Cleared when the user edits the request fields (the PSBT commits to the scanned values).
    var pendingMessagePsbt by remember { mutableStateOf<String?>(null) }

    // The signed message PSBT, displayed via SignedPSBTDisplay for the watching wallet to scan.
    var signedMessagePsbt by remember { mutableStateOf<String?>(null) }
    var signedQRResult by remember { mutableStateOf<AnimatedQRResult?>(null) }
    var showSignedPsbtDisplay by remember { mutableStateOf(false) }
    var currentDisplayFrame by remember { mutableIntStateOf(0) }
    var selectedOutputFormat by remember { mutableStateOf(OutputFormat.UR_LEGACY) }
    var selectedDensity by remember { mutableStateOf(QRDensity.HIGH) }
    var showDensityMenu by remember { mutableStateOf(false) }
    var isQRPaused by remember { mutableStateOf(false) }
    var isRegeneratingQR by remember { mutableStateOf(false) }

    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            isScanning = true
        }
    }

    // Camera lifecycle (mirrors ScanPSBTScreen): resume only when the view exists, the lifecycle
    // is resumed, and the scanner is on screen; reset the joiner whenever a scan session starts.
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
    LaunchedEffect(barcodeView, isLifecycleResumed, isScanning) {
        if (barcodeView != null && isLifecycleResumed && isScanning) {
            barcodeView?.resume()
        }
    }
    LaunchedEffect(isScanning) {
        if (isScanning) {
            animatedScanner.reset()
            scanProgress = 0
            isAnimatedScan = false
        } else {
            barcodeView?.pause()
            barcodeView = null
        }
    }

    // Determine button state: Sign if address+message only, Verify if all three
    val canSign = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isBlank()
    val canVerify = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isNotBlank()

    fun clearStatus() {
        errorMessage = ""
        successMessage = ""
    }

    /** Advance the animated signed-PSBT QR while it's visible. */
    LaunchedEffect(signedQRResult, isQRPaused, showSignedPsbtDisplay) {
        val result = signedQRResult ?: return@LaunchedEffect
        if (showSignedPsbtDisplay && result.isAnimated && result.frames.size > 1 && !isQRPaused) {
            while (true) {
                delay(result.recommendedFrameDelayMs)
                if (!isQRPaused) {
                    currentDisplayFrame = (currentDisplayFrame + 1) % result.frames.size
                }
            }
        }
    }

    /** Regenerate the signed-PSBT QR for the current format/density, keeping the old QR on failure. */
    fun regenerateSignedQr() {
        val psbt = signedMessagePsbt ?: return
        currentDisplayFrame = 0
        isRegeneratingQR = true
        val previousResult = signedQRResult
        scope.launch {
            val newQR = withContext(Dispatchers.Default) {
                QRCodeUtils.generateSmartPSBTQR(psbt, format = selectedOutputFormat, density = selectedDensity)
            }
            signedQRResult = newQR ?: previousResult
            isRegeneratingQR = false
        }
    }

    fun signOrVerify() {
        scope.launch {
            isProcessing = true
            clearStatus()
            try {
                if (canSign) {
                    val pending = pendingMessagePsbt
                    if (pending != null) {
                        // A scanned message-signing PSBT: sign it and show the signed PSBT QR.
                        val result = withContext(Dispatchers.Default) {
                            WalletMessageSigner.signMessagePsbt(wallet, pending)
                        }
                        result.fold(
                            onSuccess = { signed ->
                                signatureInput = signed.signature
                                successMessage = "Message signed successfully ✓"
                                signedMessagePsbt = signed.signedPsbtBase64
                                signedQRResult = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateSmartPSBTQR(
                                        signed.signedPsbtBase64,
                                        format = selectedOutputFormat,
                                        density = selectedDensity
                                    )
                                }
                                currentDisplayFrame = 0
                                if (signedQRResult != null) {
                                    showSignedPsbtDisplay = true
                                } else {
                                    errorMessage = "Failed to generate QR code"
                                }
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Failed to sign the message PSBT"
                            }
                        )
                    } else {
                        val result = withContext(Dispatchers.Default) {
                            WalletMessageSigner.signWithAddress(wallet, addressInput, messageInput, signatureFormat, isSilentPayment)
                        }
                        result.fold(
                            onSuccess = { signature ->
                                signatureInput = signature
                                successMessage = "Message signed successfully ✓"
                            },
                            onFailure = { error ->
                                errorMessage = error.message ?: "Failed to sign message"
                            }
                        )
                    }
                } else if (canVerify) {
                    val outcome = withContext(Dispatchers.Default) {
                        WalletMessageSigner.verify(addressInput, messageInput, signatureInput, signatureFormat)
                    }
                    if (outcome.isValid) {
                        val formatName = outcome.detectedFormat?.displayName ?: "Unknown"
                        successMessage = "Signature is valid ✓ (Format: $formatName)"
                        outcome.detectedFormat?.let { signatureFormat = it }
                    } else {
                        errorMessage = "Signature is invalid ✗"
                    }
                }
            } catch (e: Exception) {
                errorMessage = e.message ?: "An error occurred"
            } finally {
                isProcessing = false
            }
        }
    }

    /** A scanned BIP-322 message-signing PSBT: validate and fill the request, don't sign yet. */
    fun stageScannedMessagePsbt(psbtBase64: String) {
        scope.launch {
            isProcessing = true
            clearStatus()
            try {
                val result = withContext(Dispatchers.Default) {
                    WalletMessageSigner.parseMessagePsbtRequest(wallet, psbtBase64)
                }
                result.fold(
                    onSuccess = { request ->
                        addressInput = request.address
                        messageInput = request.message
                        signatureInput = ""
                        signatureFormat = MessageSigning.SignatureFormat.BIP322
                        pendingMessagePsbt = psbtBase64
                        successMessage = "Message-signing request."
                    },
                    onFailure = { error ->
                        errorMessage = error.message ?: "Could not read the message-signing PSBT"
                    }
                )
            } finally {
                isProcessing = false
            }
        }
    }

    fun handleSingleScan(result: String) {
        when (scanTarget) {
            ScanTarget.ADDRESS -> {
                // Extract address from bitcoin: URI if present
                addressInput = if (result.startsWith("bitcoin:", ignoreCase = true)) {
                    result.substringAfter(":").substringBefore("?")
                } else {
                    result
                }
                pendingMessagePsbt = null
            }
            ScanTarget.MESSAGE -> {
                // Sparrow-style "signmessage <path> ascii:<message>" QR, or a plain message
                if (result.startsWith("signmessage ", ignoreCase = true)) {
                    val parsed = WalletMessageSigner.parseSignMessageQr(wallet, result)
                    if (parsed != null) {
                        addressInput = parsed.first
                        messageInput = parsed.second
                        pendingMessagePsbt = null
                    } else {
                        errorMessage = "Could not parse signmessage QR or derive address"
                    }
                } else {
                    messageInput = result
                    pendingMessagePsbt = null
                }
            }
        }
    }

    fun clearAll() {
        addressInput = ""
        messageInput = ""
        signatureInput = ""
        pendingMessagePsbt = null
        clearStatus()
    }

    // Back gesture: close the scanner or the signed-PSBT display before leaving the screen
    BackHandler(enabled = isScanning || showSignedPsbtDisplay) {
        if (isScanning) isScanning = false else showSignedPsbtDisplay = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        if (isScanning) {
                            if (scanTarget == ScanTarget.ADDRESS) "Scan Address QR" else "Scan Message QR"
                        } else {
                            "Sign/Verify Message"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when {
                            isScanning -> isScanning = false
                            showSignedPsbtDisplay -> showSignedPsbtDisplay = false
                            else -> onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // QR density control, only while the signed message PSBT is displayed
                    if (showSignedPsbtDisplay && signedQRResult != null) {
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
                                            if (density != selectedDensity) {
                                                selectedDensity = density
                                                regenerateSignedQr()
                                            }
                                            showDensityMenu = false
                                        },
                                        leadingIcon = if (selectedDensity == density) {
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
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        if (showSignatureQR && signatureQRBitmap != null) {
            SignatureQrDialog(
                bitmap = signatureQRBitmap!!,
                onDismiss = { showSignatureQR = false }
            )
        }

        when {
            isScanning -> {
                // The exact Scan PSBT viewfinder (frame flash + animated-QR progress card), with
                // plain payloads (addresses, messages) delivered as a single-shot scan.
                PSBTScannerView(
                    hasCameraPermission = hasCameraPermission,
                    animatedScanner = animatedScanner,
                    scanProgress = scanProgress,
                    isAnimatedScan = isAnimatedScan,
                    errorMessage = "",
                    onRequestPermission = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                    onScanProgress = { progress, animated ->
                        scanProgress = progress
                        isAnimatedScan = animated
                    },
                    onScanComplete = { psbt, _ ->
                        isScanning = false
                        stageScannedMessagePsbt(psbt)
                    },
                    onBarcodeViewCreated = { barcodeView = it },
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding),
                    onPlainScan = { result ->
                        handleSingleScan(result)
                        isScanning = false
                    }
                )
            }

            // The signed message PSBT, with the full format/playback controls of the PSBT flow.
            showSignedPsbtDisplay && signedQRResult != null -> {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(padding)
                ) {
                    SignedPSBTDisplay(
                        signedQRResult = signedQRResult!!,
                        currentFrame = currentDisplayFrame,
                        selectedFormat = selectedOutputFormat,
                        isPaused = isQRPaused,
                        isLoading = isRegeneratingQR,
                        title = "Message Signed",
                        scanAnotherLabel = "Scan Another Message PSBT",
                        onPauseToggle = { isQRPaused = it },
                        onPreviousFrame = {
                            val totalFrames = signedQRResult!!.frames.size
                            currentDisplayFrame = (currentDisplayFrame - 1 + totalFrames) % totalFrames
                        },
                        onNextFrame = {
                            val totalFrames = signedQRResult!!.frames.size
                            currentDisplayFrame = (currentDisplayFrame + 1) % totalFrames
                        },
                        onFormatChange = { newFormat ->
                            selectedOutputFormat = newFormat
                            regenerateSignedQr()
                        },
                        onScanAnother = {
                            showSignedPsbtDisplay = false
                            signedMessagePsbt = null
                            signedQRResult = null
                            clearAll()
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onDone = { showSignedPsbtDisplay = false }
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
                            text = if (isSilentPayment) {
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
                            selectedIndex = formats.indexOf(signatureFormat),
                            onSelect = { signatureFormat = formats[it] },
                            compact = true,
                            itemEnabled = { index ->
                                !isSilentPayment || formats[index] == MessageSigning.SignatureFormat.BIP322
                            }
                        )
                    }

                    // A staged message-signing request awaiting the user's consent to sign
                    if (pendingMessagePsbt != null) {
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
                        value = addressInput,
                        onValueChange = {
                            addressInput = it
                            pendingMessagePsbt = null
                            clearStatus()
                        },
                        label = { Text("Bitcoin Address") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        enabled = !isProcessing,
                        trailingIcon = {
                            IconButton(
                                onClick = {
                                    scanTarget = ScanTarget.ADDRESS
                                    permissionLauncher.launch(Manifest.permission.CAMERA)
                                },
                                enabled = !isProcessing
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
                        value = messageInput,
                        onValueChange = {
                            messageInput = it
                            pendingMessagePsbt = null
                            clearStatus()
                        },
                        label = { Text("Message") },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 120.dp),
                        minLines = 4,
                        maxLines = 10,
                        enabled = !isProcessing
                    )

                    // Signature Input
                    SecureOutlinedTextField(
                        value = signatureInput,
                        onValueChange = {
                            signatureInput = it
                            clearStatus()
                        },
                        label = { Text("Signature") },
                        modifier = Modifier.fillMaxWidth(),
                        minLines = 2,
                        maxLines = 4,
                        enabled = !isProcessing,
                        trailingIcon = {
                            if (signatureInput.isNotBlank()) {
                                IconButton(
                                    onClick = {
                                        scope.launch {
                                            val clipData = ClipData.newPlainText("signature", signatureInput)
                                            clipboard.setClipEntry(clipData.toClipEntry())
                                        }
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
                        enabled = !isProcessing,
                        onClick = {
                            scanTarget = ScanTarget.MESSAGE
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        }
                    )

                    if (signatureInput.isNotBlank()) {
                        IconTextButton(
                            text = "Show Signature QR",
                            iconRes = R.drawable.ic_qr_code_2,
                            enabled = !isProcessing,
                            onClick = {
                                scope.launch {
                                    signatureQRBitmap = withContext(Dispatchers.Default) {
                                        QRCodeUtils.generateQRCode(signatureInput)
                                    }
                                    showSignatureQR = true
                                }
                            }
                        )
                    }

                    // After signing a scanned message PSBT, the watching wallet needs the PSBT back.
                    if (signedQRResult != null) {
                        IconTextButton(
                            text = "Show Signed PSBT QR",
                            iconRes = R.drawable.ic_qr_code_2,
                            enabled = !isProcessing,
                            onClick = { showSignedPsbtDisplay = true }
                        )
                    }

                    if (errorMessage.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.errorContainer
                            )
                        ) {
                            Text(
                                text = errorMessage,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onErrorContainer
                            )
                        }
                    }

                    if (successMessage.isNotEmpty()) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.primaryContainer
                            )
                        ) {
                            Text(
                                text = successMessage,
                                modifier = Modifier.padding(16.dp),
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }

                    Spacer(modifier = Modifier.weight(1f))

                    // Sign/Verify Button
                    Button(
                        onClick = ::signOrVerify,
                        modifier = Modifier.fillMaxWidth(),
                        enabled = (canSign || canVerify) && !isProcessing
                    ) {
                        if (isProcessing) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(24.dp),
                                color = MaterialTheme.colorScheme.onPrimary
                            )
                        } else {
                            Text(if (canVerify) "Verify" else "Sign")
                        }
                    }

                    // Clear button
                    if (addressInput.isNotBlank() || messageInput.isNotBlank() || signatureInput.isNotBlank()) {
                        OutlinedButton(
                            onClick = ::clearAll,
                            modifier = Modifier.fillMaxWidth(),
                            enabled = !isProcessing
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

