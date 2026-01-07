package com.gorunjinian.metrovault.feature.transaction

import android.Manifest
import android.annotation.SuppressLint
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.feature.transaction.components.OutputWithType
import com.gorunjinian.metrovault.feature.transaction.components.PSBTScannerView
import com.gorunjinian.metrovault.feature.transaction.components.SignedPSBTDisplay
import com.gorunjinian.metrovault.feature.transaction.components.TransactionConfirmation
import com.gorunjinian.metrovault.lib.qrtools.AnimatedQRResult
import com.gorunjinian.metrovault.lib.qrtools.AnimatedQRScanner
import com.gorunjinian.metrovault.lib.qrtools.OutputFormat
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.lib.qrtools.QRDensity
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
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
@SuppressLint("DefaultLocale")
fun ScanPSBTScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    // ==================== State ====================
    var scannedPSBT by remember { mutableStateOf<String?>(null) }
    var psbtDetails by remember { mutableStateOf<PsbtDetails?>(null) }
    var outputsWithType by remember { mutableStateOf<List<OutputWithType>?>(null) }
    var signedPSBT by remember { mutableStateOf<String?>(null) }
    var signedQRResult by remember { mutableStateOf<AnimatedQRResult?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var isProcessing by remember { mutableStateOf(false) }
    var alternativePathsUsed by remember { mutableStateOf<List<String>>(emptyList()) }  // Track alternative paths
    
    // Animated QR scanning state
    val animatedScanner = remember { AnimatedQRScanner() }
    var scanProgress by remember { mutableIntStateOf(0) }
    var isAnimatedScan by remember { mutableStateOf(false) }
    var isParsingPSBT by remember { mutableStateOf(false) }
    
    // Animated QR display state (for signed output)
    var currentDisplayFrame by remember { mutableIntStateOf(0) }
    var selectedOutputFormat by remember { mutableStateOf(OutputFormat.UR_LEGACY) }
    var selectedDensity by remember { mutableStateOf(QRDensity.HIGH) }
    var isQRPaused by remember { mutableStateOf(false) }
    var isRegeneratingQR by remember { mutableStateOf(false) }
    
    // Finalization state (single-sig or fully-signed multi-sig)
    var canFinalize by remember { mutableStateOf(false) }
    var isFinalized by remember { mutableStateOf(false) }
    var finalizedTxHex by remember { mutableStateOf<String?>(null) }
    
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
    
    // Track lifecycle resume state to coordinate with view readiness
    var isLifecycleResumed by remember { mutableStateOf(false) }
    
    // Resume camera when BOTH: view is ready AND lifecycle is resumed AND we're in scanning mode
    // This avoids double initialization (factory + lifecycle observer) that caused the camera freeze
    LaunchedEffect(barcodeView, isLifecycleResumed, signedPSBT, psbtDetails) {
        if (barcodeView != null && isLifecycleResumed && signedPSBT == null && psbtDetails == null) {
            barcodeView?.resume()
        }
    }

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
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    // ==================== Animated QR Display Timer ====================
    LaunchedEffect(signedQRResult, isQRPaused) {
        signedQRResult?.let { result ->
            if (result.isAnimated && result.frames.size > 1 && !isQRPaused) {
                while (true) {
                    delay(result.recommendedFrameDelayMs)
                    if (!isQRPaused) {
                        currentDisplayFrame = (currentDisplayFrame + 1) % result.frames.size
                    }
                }
            }
        }
    }

    // ==================== Reset Function ====================
    fun resetScanner() {
        scannedPSBT = null
        psbtDetails = null
        outputsWithType = null
        signedPSBT = null
        signedQRResult = null
        errorMessage = ""
        alternativePathsUsed = emptyList()
        animatedScanner.reset()
        scanProgress = 0
        isAnimatedScan = false
        currentDisplayFrame = 0
        canFinalize = false
        isFinalized = false
        finalizedTxHex = null
        barcodeView?.resume()
    }

    // State for density dropdown menu in TopAppBar
    var showDensityMenu by remember { mutableStateOf(false) }


    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Sign PSBT") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Only show density button when signed transaction is displayed
                    if (signedPSBT != null && signedQRResult != null) {
                        // Finalized badge
                        if (isFinalized) {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = androidx.compose.foundation.shape.RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = "Finalized",
                                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = androidx.compose.ui.text.font.FontWeight.SemiBold
                                )
                            }
                            Spacer(modifier = Modifier.width(8.dp))
                        }
                        
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
                                            // Trigger density change
                                            if (density != selectedDensity) {
                                                selectedDensity = density
                                                currentDisplayFrame = 0
                                                isRegeneratingQR = true
                                                val previousResult = signedQRResult
                                                scope.launch {
                                                    val newQR = withContext(Dispatchers.Default) {
                                                        // Generate appropriate QR based on finalization state
                                                        if (isFinalized && finalizedTxHex != null) {
                                                            QRCodeUtils.generateRawTxQR(
                                                                finalizedTxHex!!,
                                                                format = selectedOutputFormat,
                                                                density = density
                                                            )
                                                        } else {
                                                            QRCodeUtils.generateSmartPSBTQR(
                                                                signedPSBT!!,
                                                                format = selectedOutputFormat,
                                                                density = density
                                                            )
                                                        }
                                                    }
                                                    if (newQR != null) {
                                                        signedQRResult = newQR
                                                    } else {
                                                        signedQRResult = previousResult
                                                        android.util.Log.w("ScanPSBTScreen", "QR generation failed for density $density, keeping previous")
                                                    }
                                                    isRegeneratingQR = false
                                                }
                                            }
                                            showDensityMenu = false
                                        },
                                        leadingIcon = if (selectedDensity == density) {
                                            { Icon(painterResource(
                                                R.drawable.ic_check),
                                                contentDescription = null)
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            when {
                // ==================== PARSING PSBT LOADING STATE ====================
                isParsingPSBT -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            verticalArrangement = Arrangement.Center
                        ) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(64.dp),
                                color = MaterialTheme.colorScheme.primary
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Text(
                                text = "Parsing transaction...",
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                
                // ==================== SIGNED TRANSACTION DISPLAY ====================
                signedPSBT != null && signedQRResult != null -> {
                    SignedPSBTDisplay(
                        signedQRResult = signedQRResult!!,
                        currentFrame = currentDisplayFrame,
                        selectedFormat = selectedOutputFormat,
                        isPaused = isQRPaused,
                        isLoading = isRegeneratingQR,
                        alternativePathsUsed = alternativePathsUsed,
                        canFinalize = canFinalize,
                        isFinalized = isFinalized,
                        onFinalize = {
                            // Finalize the PSBT and generate raw tx QR
                            isRegeneratingQR = true
                            scope.launch {
                                val result = withContext(Dispatchers.Default) {
                                    wallet.finalizePsbt(signedPSBT!!)
                                }
                                when (result) {
                                    is com.gorunjinian.metrovault.domain.service.psbt.PsbtService.FinalizePsbtResult.Success -> {
                                        finalizedTxHex = result.txHex
                                        val txQR = withContext(Dispatchers.Default) {
                                            QRCodeUtils.generateRawTxQR(
                                                result.txHex,
                                                format = selectedOutputFormat,
                                                density = selectedDensity
                                            )
                                        }
                                        if (txQR != null) {
                                            signedQRResult = txQR
                                            isFinalized = true
                                            currentDisplayFrame = 0
                                        }
                                    }
                                    is com.gorunjinian.metrovault.domain.service.psbt.PsbtService.FinalizePsbtResult.Failure -> {
                                        android.util.Log.e("ScanPSBTScreen", "Finalization failed: ${result.message}")
                                    }
                                }
                                isRegeneratingQR = false
                            }
                        },
                        onToggleView = {
                            // Toggle between finalized tx and signed PSBT view
                            isRegeneratingQR = true
                            scope.launch {
                                val newQR = withContext(Dispatchers.Default) {
                                    if (isFinalized) {
                                        // Switch to signed PSBT view
                                        QRCodeUtils.generateSmartPSBTQR(
                                            signedPSBT!!,
                                            format = selectedOutputFormat,
                                            density = selectedDensity
                                        )
                                    } else {
                                        // Switch to finalized tx view
                                        finalizedTxHex?.let {
                                            QRCodeUtils.generateRawTxQR(
                                                it,
                                                format = selectedOutputFormat,
                                                density = selectedDensity
                                            )
                                        }
                                    }
                                }
                                if (newQR != null) {
                                    signedQRResult = newQR
                                    isFinalized = !isFinalized
                                    currentDisplayFrame = 0
                                }
                                isRegeneratingQR = false
                            }
                        },
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
                            currentDisplayFrame = 0
                            isRegeneratingQR = true
                            // Regenerate QR in new format - keep previous result if generation fails
                            val previousResult = signedQRResult
                            scope.launch {
                                val newQR = withContext(Dispatchers.Default) {
                                    // Generate appropriate QR based on finalization state
                                    if (isFinalized && finalizedTxHex != null) {
                                        QRCodeUtils.generateRawTxQR(
                                            finalizedTxHex!!,
                                            format = newFormat,
                                            density = selectedDensity
                                        )
                                    } else {
                                        QRCodeUtils.generateSmartPSBTQR(
                                            signedPSBT!!,
                                            format = newFormat,
                                            density = selectedDensity
                                        )
                                    }
                                }
                                // Only update if generation succeeded
                                if (newQR != null) {
                                    signedQRResult = newQR
                                } else {
                                    // Keep previous result and revert format selection
                                    signedQRResult = previousResult
                                    android.util.Log.w("ScanPSBTScreen", "QR generation failed for format $newFormat, keeping previous")
                                }
                                isRegeneratingQR = false
                            }
                        },
                        onScanAnother = { resetScanner() },
                        onDone = onBack
                    )
                }

                // ==================== TRANSACTION DETAILS CONFIRMATION ====================
                psbtDetails != null && scannedPSBT != null && outputsWithType != null -> {
                    TransactionConfirmation(
                        psbtDetails = psbtDetails!!,
                        outputsWithType = outputsWithType!!,
                        isProcessing = isProcessing,
                        errorMessage = errorMessage,
                        onSign = {
                            scope.launch {
                                isProcessing = true
                                errorMessage = ""

                                try {
                                    val signingResult = withContext(Dispatchers.Default) {
                                        wallet.signPsbt(scannedPSBT!!)
                                    }

                                    when (signingResult) {
                                        is Wallet.PsbtSigningResult.Success -> {
                                            val signed = signingResult.signedPsbt

                                            // Track alternative paths used
                                            alternativePathsUsed = signingResult.alternativePathsUsed

                                            // Check if this PSBT can be finalized (all required signatures present)
                                            canFinalize = withContext(Dispatchers.Default) {
                                                wallet.canFinalize(signed)
                                            }

                                            // Use smart QR generation for animated support
                                            val qrResult = withContext(Dispatchers.Default) {
                                                QRCodeUtils.generateSmartPSBTQR(
                                                    signed,
                                                    density = selectedDensity
                                                )
                                            }

                                            signedPSBT = signed
                                            signedQRResult = qrResult
                                            currentDisplayFrame = 0

                                            if (qrResult == null) {
                                                errorMessage = "Failed to generate QR code"
                                            }
                                        }
                                        is Wallet.PsbtSigningResult.Failure -> {
                                            errorMessage = signingResult.message
                                        }
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
                    PSBTScannerView(
                        hasCameraPermission = hasCameraPermission,
                        animatedScanner = animatedScanner,
                        scanProgress = scanProgress,
                        isAnimatedScan = isAnimatedScan,
                        errorMessage = if (psbtDetails == null) errorMessage else "",
                        onRequestPermission = {
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        onScanProgress = { progress, isAnimated ->
                            scanProgress = progress
                            isAnimatedScan = isAnimated
                        },
                        onScanComplete = { assembledPSBT ->
                            // Set state immediately - callback is already on Main thread
                            // (called from LaunchedEffect in PSBTScannerView)
                            scannedPSBT = assembledPSBT
                            isParsingPSBT = true

                            // Parse PSBT details and compute output types on background thread
                            // to avoid blocking the Main thread during composition
                            scope.launch(Dispatchers.IO) {
                                val details = wallet.getPsbtDetails(assembledPSBT)

                                // Also pre-compute output types on background thread
                                // (this involves expensive address scanning)
                                val computedOutputsWithType = details?.outputs?.map { output ->
                                    val checkResult = wallet.checkAddressBelongsToWallet(output.address)
                                    val belongsToWallet = checkResult?.belongs == true
                                    val isOnChangePath = checkResult?.isChange == true
                                    OutputWithType(output, belongsToWallet, isOnChangePath)
                                }

                                // Switch back to Main thread for state updates
                                withContext(Dispatchers.Main) {
                                    if (details != null && computedOutputsWithType != null) {
                                        psbtDetails = details
                                        outputsWithType = computedOutputsWithType
                                    } else {
                                        errorMessage = "Failed to parse PSBT. Invalid format or unsupported transaction type."
                                        scannedPSBT = null
                                        animatedScanner.reset()
                                        scanProgress = 0
                                        isAnimatedScan = false
                                        barcodeView?.resume()
                                    }
                                    isParsingPSBT = false
                                }
                            }
                        },
                        onBarcodeViewCreated = { view ->
                            barcodeView = view
                        },
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    }
}