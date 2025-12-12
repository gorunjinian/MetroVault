package com.gorunjinian.metrovault.feature.transaction

import android.Manifest
import android.graphics.Bitmap
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.PsbtDetails
import com.gorunjinian.metrovault.domain.service.PsbtOutput
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PSBT Scanning and Signing Screen with animated QR support.
 * 
 * Supports:
 * - Single-frame QR codes (small PSBTs)
 * - Animated QR codes (large PSBTs with multiple frames)
 * - Both "p1/10 data" and "ur:bytes/1-10/data" formats
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScanPSBTScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    // ==================== State ====================
    var scannedPSBT by remember { mutableStateOf<String?>(null) }
    var psbtDetails by remember { mutableStateOf<PsbtDetails?>(null) }
    var signedPSBT by remember { mutableStateOf<String?>(null) }
    var signedQRResult by remember { mutableStateOf<QRCodeUtils.AnimatedQRResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    
    // Animated QR scanning state
    val animatedScanner = remember { QRCodeUtils.AnimatedQRScanner() }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isAnimatedScan by remember { mutableStateOf(false) }
    var lastScannedFrame by remember { mutableStateOf("") }
    var frameJustCaptured by remember { mutableStateOf(false) }
    
    // Animated QR display state (for signed output)
    var currentDisplayFrame by remember { mutableIntStateOf(0) }
    var selectedOutputFormat by remember { mutableStateOf(QRCodeUtils.OutputFormat.UR_PSBT) }
    var forceAnimatedOutput by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()

    // ==================== Permission ====================
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    // ==================== Lifecycle ====================
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> if (signedPSBT == null && psbtDetails == null) barcodeView?.resume()
                Lifecycle.Event.ON_PAUSE -> barcodeView?.pause()
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ==================== Animated QR Display Timer ====================
    LaunchedEffect(signedQRResult) {
        signedQRResult?.let { result ->
            if (result.isAnimated && result.frames.size > 1) {
                while (true) {
                    delay(result.recommendedFrameDelayMs)
                    currentDisplayFrame = (currentDisplayFrame + 1) % result.frames.size
                }
            }
        }
    }
    
    // Flash effect when frame is captured
    val scannerBackgroundColor by animateColorAsState(
        targetValue = if (frameJustCaptured) 
            MaterialTheme.colorScheme.primary.copy(alpha = 0.3f) 
        else 
            MaterialTheme.colorScheme.surface.copy(alpha = 0.1f),
        animationSpec = tween(150),
        label = "scanFlash"
    )
    
    LaunchedEffect(frameJustCaptured) {
        if (frameJustCaptured) {
            delay(200)
            frameJustCaptured = false
        }
    }

    // ==================== Reset Function ====================
    fun resetScanner() {
        scannedPSBT = null
        psbtDetails = null
        signedPSBT = null
        signedQRResult = null
        errorMessage = ""
        animatedScanner.reset()
        scanProgress = 0
        isAnimatedScan = false
        lastScannedFrame = ""
        currentDisplayFrame = 0
        barcodeView?.resume()
    }

    // ==================== UI ====================
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign PSBT") },
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
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                // ==================== SIGNED TRANSACTION DISPLAY ====================
                signedPSBT != null && signedQRResult != null -> {
                    SignedPSBTDisplay(
                        signedPSBT = signedPSBT!!,
                        signedQRResult = signedQRResult!!,
                        currentFrame = currentDisplayFrame,
                        selectedFormat = selectedOutputFormat,
                        forceAnimated = forceAnimatedOutput,
                        onFormatChange = { newFormat ->
                            selectedOutputFormat = newFormat
                            currentDisplayFrame = 0
                            // Regenerate QR in new format
                            scope.launch {
                                val newQR = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateSmartPSBTQR(
                                        signedPSBT!!,
                                        format = newFormat,
                                        forceAnimated = forceAnimatedOutput
                                    )
                                }
                                signedQRResult = newQR
                            }
                        },
                        onAnimatedToggle = { animated ->
                            forceAnimatedOutput = animated
                            currentDisplayFrame = 0
                            // Regenerate QR with new animated setting
                            scope.launch {
                                val newQR = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateSmartPSBTQR(
                                        signedPSBT!!,
                                        format = selectedOutputFormat,
                                        forceAnimated = animated
                                    )
                                }
                                signedQRResult = newQR
                            }
                        },
                        onScanAnother = { resetScanner() },
                        onDone = onBack
                    )
                }

                // ==================== TRANSACTION DETAILS CONFIRMATION ====================
                psbtDetails != null && scannedPSBT != null -> {
                    TransactionConfirmation(
                        wallet = wallet,
                        psbtDetails = psbtDetails!!,
                        isProcessing = isProcessing,
                        errorMessage = errorMessage,
                        onSign = {
                            scope.launch {
                                isProcessing = true
                                errorMessage = ""

                                try {
                                    val signed = withContext(Dispatchers.Default) {
                                        wallet.signPsbt(scannedPSBT!!)
                                    }

                                    if (signed != null) {
                                        // Use smart QR generation for animated support
                                        val qrResult = withContext(Dispatchers.Default) {
                                            QRCodeUtils.generateSmartPSBTQR(signed)
                                        }

                                        signedPSBT = signed
                                        signedQRResult = qrResult
                                        currentDisplayFrame = 0

                                        if (qrResult == null) {
                                            errorMessage = "Failed to generate QR code"
                                        }
                                    } else {
                                        errorMessage = "Failed to sign PSBT. Ensure wallet is loaded and keys are available."
                                    }
                                } catch (e: Exception) {
                                    errorMessage = "Signing error: ${e.message}"
                                } finally {
                                    isProcessing = false
                                }
                            }
                        },
                        onCancel = { resetScanner() }
                    )
                }

                // ==================== CAMERA SCANNING VIEW ====================
                else -> {
                    if (hasCameraPermission) {
                        Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                            AndroidView(
                                factory = { context ->
                                    CompoundBarcodeView(context).apply {
                                        barcodeView = this
                                        setStatusText("")
                                        decodeContinuous { result ->
                                            result.text?.let { text ->
                                                // Skip if we already have a complete PSBT
                                                if (scannedPSBT != null || psbtDetails != null) return@let
                                                
                                                // Skip duplicate frames
                                                if (text == lastScannedFrame) return@let
                                                lastScannedFrame = text
                                                
                                                // Process the frame
                                                val progress = animatedScanner.processFrame(text)
                                                
                                                if (progress != null) {
                                                    scanProgress = progress
                                                    frameJustCaptured = true
                                                    
                                                    // Check if this is an animated scan
                                                    if (QRCodeUtils.parseAnimatedFrame(text) != null) {
                                                        isAnimatedScan = true
                                                    }
                                                    
                                                    // Check if scan is complete
                                                    if (animatedScanner.isComplete()) {
                                                        val assembledPSBT = animatedScanner.getResult()
                                                        if (assembledPSBT != null) {
                                                            scannedPSBT = assembledPSBT
                                                            pause()
                                                            
                                                            // Parse PSBT details
                                                            val details = wallet.getPsbtDetails(assembledPSBT)
                                                            if (details != null) {
                                                                psbtDetails = details
                                                            } else {
                                                                errorMessage = "Failed to parse PSBT. Invalid format or unsupported transaction type."
                                                                scannedPSBT = null
                                                                animatedScanner.reset()
                                                                scanProgress = 0
                                                                isAnimatedScan = false
                                                                resume()
                                                            }
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                        resume()
                                    }
                                },
                                modifier = Modifier.fillMaxSize()
                            )

                            // Viewfinder overlay with progress
                            Box(
                                modifier = Modifier.fillMaxSize(),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(
                                    horizontalAlignment = Alignment.CenterHorizontally
                                ) {
                                    // Scanning frame indicator
                                    Card(
                                        modifier = Modifier.size(250.dp),
                                        colors = CardDefaults.cardColors(
                                            containerColor = scannerBackgroundColor
                                        )
                                    ) {}
                                    
                                    Spacer(modifier = Modifier.height(16.dp))
                                    
                                    // Progress indicator for animated QR
                                    if (isAnimatedScan && scanProgress > 0 && scanProgress < 100) {
                                        Card(
                                            colors = CardDefaults.cardColors(
                                                containerColor = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.9f)
                                            )
                                        ) {
                                            Column(
                                                modifier = Modifier.padding(16.dp),
                                                horizontalAlignment = Alignment.CenterHorizontally
                                            ) {
                                                Text(
                                                    text = "Scanning Animated QR",
                                                    style = MaterialTheme.typography.labelMedium,
                                                    fontWeight = FontWeight.Bold
                                                )
                                                Spacer(modifier = Modifier.height(8.dp))
                                                LinearProgressIndicator(
                                                    progress = { scanProgress / 100f },
                                                    modifier = Modifier
                                                        .width(200.dp)
                                                        .height(8.dp)
                                                        .clip(RoundedCornerShape(4.dp)),
                                                )
                                                Spacer(modifier = Modifier.height(4.dp))
                                                Text(
                                                    text = animatedScanner.getProgressString(),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        if (errorMessage.isNotEmpty() && psbtDetails == null) {
                            Card(
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer
                                ),
                                modifier = Modifier.padding(16.dp)
                            ) {
                                Text(
                                    text = errorMessage,
                                    modifier = Modifier.padding(16.dp),
                                    color = MaterialTheme.colorScheme.onErrorContainer
                                )
                            }
                        }
                    } else {
                        // No camera permission
                        Column(
                            modifier = Modifier.fillMaxSize(),
                            verticalArrangement = Arrangement.Center,
                            horizontalAlignment = Alignment.CenterHorizontally
                        ) {
                            Text(
                                text = "Camera permission is required to scan PSBT QR codes",
                                style = MaterialTheme.typography.bodyLarge
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = {
                                permissionLauncher.launch(Manifest.permission.CAMERA)
                            }) {
                                Text("Grant Permission")
                            }
                        }
                    }
                }
            }
        }
    }
}

// ==================== Signed PSBT Display ====================
@Composable
private fun SignedPSBTDisplay(
    signedPSBT: String,
    signedQRResult: QRCodeUtils.AnimatedQRResult,
    currentFrame: Int,
    selectedFormat: QRCodeUtils.OutputFormat,
    forceAnimated: Boolean,
    onFormatChange: (QRCodeUtils.OutputFormat) -> Unit,
    onAnimatedToggle: (Boolean) -> Unit,
    onScanAnother: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp, vertical = 16.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Top
    ) {
        // Title at top center
        Text(
            text = "Transaction Signed",
            style = MaterialTheme.typography.headlineMedium,
            color = MaterialTheme.colorScheme.primary,
            fontWeight = FontWeight.Bold
        )

        Spacer(modifier = Modifier.height(12.dp))

        // Format selector toggle - bigger and centered
        Row(
            modifier = Modifier
                .background(
                    MaterialTheme.colorScheme.surfaceVariant,
                    RoundedCornerShape(12.dp)
                )
                .padding(6.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            QRCodeUtils.OutputFormat.entries.forEach { format ->
                Box(
                    modifier = Modifier
                        .clip(RoundedCornerShape(8.dp))
                        .background(
                            if (selectedFormat == format) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { onFormatChange(format) }
                        .padding(horizontal = 16.dp, vertical = 10.dp)
                ) {
                    Text(
                        text = format.displayName,
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = if (selectedFormat == format) FontWeight.Bold else FontWeight.Medium,
                        color = if (selectedFormat == format) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(8.dp))

        // Animated QR toggle
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Animated QR",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurface
            )
            Spacer(modifier = Modifier.width(12.dp))
            androidx.compose.material3.Switch(
                checked = forceAnimated || signedQRResult.isAnimated,
                onCheckedChange = { onAnimatedToggle(it) },
                enabled = !signedQRResult.isAnimated // Disable if already animated due to size
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        // QR Code Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f)
        ) {
            val displayBitmap = signedQRResult.frames.getOrNull(currentFrame)
            if (displayBitmap != null) {
                Image(
                    bitmap = displayBitmap.asImageBitmap(),
                    contentDescription = "Signed PSBT QR Code",
                    modifier = Modifier.fillMaxSize()
                )
            }
        }
        
        // Frame counter for animated QR
        if (signedQRResult.isAnimated) {
            Spacer(modifier = Modifier.height(8.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "Frame ${currentFrame + 1}/${signedQRResult.totalParts}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
            text = if (signedQRResult.isAnimated)
                "Keep the QR code visible until all frames are scanned"
            else
                "Scan this QR code with your online wallet to broadcast",
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = onScanAnother,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign Another Transaction")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}

// ==================== Transaction Confirmation ====================
@Composable
private fun TransactionConfirmation(
    wallet: Wallet,
    psbtDetails: PsbtDetails,
    isProcessing: Boolean,
    errorMessage: String,
    onSign: () -> Unit,
    onCancel: () -> Unit
) {
    // Determine output types:
    // - Change: belongs to wallet AND is on change derivation path (m/.../1/x)
    // - Self-send: belongs to wallet BUT is on receive path (m/.../0/x)
    // - External: doesn't belong to wallet
    val outputsWithType = remember(psbtDetails) {
        psbtDetails.outputs.map { output ->
            val checkResult = wallet.checkAddressBelongsToWallet(output.address)
            val belongsToWallet = checkResult?.belongs == true
            // Check if it's specifically on the change derivation path
            val isOnChangePath = checkResult?.isChange == true
            OutputWithType(output, belongsToWallet, isOnChangePath)
        }
    }
    
    // Unit toggle state: true = sats, false = BTC
    var showInSats by remember { mutableStateOf(true) }
    
    // Input format toggle: true = addresses, false = UTXO (tx hash:index)
    var showAddresses by remember { mutableStateOf(true) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header with title and unit toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transaction Details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            // Settings toggles column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Address/UTXO toggle
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Address option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (showAddresses) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showAddresses = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Addr",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (showAddresses) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // UTXO option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (!showAddresses) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showAddresses = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "UTXO",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!showAddresses) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Sats/BTC toggle
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sats option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (showInSats) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showInSats = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "sats",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (showInSats) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // BTC option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (!showInSats) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showInSats = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "BTC",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!showInSats) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ==================== INPUTS SECTION ====================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Inputs (${psbtDetails.inputs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                psbtDetails.inputs.forEachIndexed { index, input ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Show input address or UTXO based on toggle
                            Text(
                                text = if (showAddresses) input.address 
                                       else "${input.prevTxHash.take(8)}...${input.prevTxHash.takeLast(8)}:${input.prevTxIndex}",
                                style = MaterialTheme.typography.bodySmall,
                                maxLines = 1
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatAmount(input.value, showInSats),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (index < psbtDetails.inputs.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==================== OUTPUTS SECTION ====================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Outputs (${psbtDetails.outputs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                outputsWithType.forEachIndexed { index, outputWithType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Horizontally scrollable address container
                                Row(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = outputWithType.output.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (outputWithType.isChangeAddress == true) 
                                            MaterialTheme.colorScheme.onSurfaceVariant 
                                            else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                // Change badge
                                if (outputWithType.isChangeAddress == true) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "CHANGE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatAmount(outputWithType.output.value, showInSats),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (outputWithType.isChangeAddress == true) 
                                MaterialTheme.colorScheme.onSurfaceVariant 
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (index < outputsWithType.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Fee section
        psbtDetails.fee?.let { fee ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Network Fee:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = formatAmount(fee, showInSats),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Total amount (only count payments going out, not change returning to us)
        val totalSent = outputsWithType.filter { it.isChangeAddress != true }.sumOf { it.output.value }
        val totalAmount = totalSent + (psbtDetails.fee ?: 0)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total Amount:",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatAmount(totalAmount, showInSats),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Sign button
        Button(
            onClick = onSign,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isProcessing
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Sign Transaction", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}

/**
 * Format satoshis to the specified unit with comma separators for thousands
 * @param satoshis The amount in satoshis
 * @param showInSats If true, display as sats with comma separators; if false, display as BTC
 * @return Formatted string with appropriate unit suffix
 */
private fun formatAmount(satoshis: Long, showInSats: Boolean): String {
    return if (showInSats) {
        // Format as sats with comma separators
        "%,d sats".format(satoshis)
    } else {
        // Format as BTC with proper decimal places
        val btc = satoshis / 100_000_000.0
        val formatted = String.format("%.8f", btc).trimEnd('0').trimEnd('.')
        "$formatted BTC"
    }
}

/**
 * Legacy format function for BTC display (used in signed display)
 */
private fun formatSatoshis(satoshis: Long): String {
    val btc = satoshis / 100_000_000.0
    return String.format("%.8f", btc).trimEnd('0').trimEnd('.')
}

/**
 * Helper data class to track output type during display
 */
private data class OutputWithType(
    val output: PsbtOutput,
    val isOurAddress: Boolean,
    val isChangeAddress: Boolean?
)