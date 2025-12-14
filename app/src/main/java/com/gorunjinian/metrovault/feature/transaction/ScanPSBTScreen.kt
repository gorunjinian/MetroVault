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
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.domain.service.PsbtDetails
import com.gorunjinian.metrovault.feature.transaction.components.PSBTScannerView
import com.gorunjinian.metrovault.feature.transaction.components.SignedPSBTDisplay
import com.gorunjinian.metrovault.feature.transaction.components.TransactionConfirmation
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
@SuppressLint("DefaultLocale")
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
    
    // Animated QR display state (for signed output)
    var currentDisplayFrame by remember { mutableIntStateOf(0) }
    var selectedOutputFormat by remember { mutableStateOf(QRCodeUtils.OutputFormat.UR_PSBT) }
    var isQRPaused by remember { mutableStateOf(false) }
    
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
        signedPSBT = null
        signedQRResult = null
        errorMessage = ""
        animatedScanner.reset()
        scanProgress = 0
        isAnimatedScan = false
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
                        isPaused = isQRPaused,
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
                            // Regenerate QR in new format - keep previous result if generation fails
                            val previousResult = signedQRResult
                            scope.launch {
                                val newQR = withContext(Dispatchers.Default) {
                                    QRCodeUtils.generateSmartPSBTQR(
                                        signedPSBT!!,
                                        format = newFormat
                                    )
                                }
                                // Only update if generation succeeded
                                if (newQR != null) {
                                    signedQRResult = newQR
                                } else {
                                    // Keep previous result and revert format selection
                                    signedQRResult = previousResult
                                    android.util.Log.w("ScanPSBTScreen", "QR generation failed for format $newFormat, keeping previous")
                                }
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
                            scannedPSBT = assembledPSBT
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
                                barcodeView?.resume()
                            }
                        },
                        onScanError = { message ->
                            errorMessage = message
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