package com.gorunjinian.metrovault.feature.transaction.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.gorunjinian.metrovault.lib.qrtools.AnimatedQRScanner
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * QR code scanner component for scanning PSBT QR codes.
 * 
 * Supports:
 * - Single-frame QR codes
 * - Animated multi-frame QR codes with progress tracking
 * - Camera permission handling
 */
@Suppress("AssignedValueIsNeverRead")
@Composable
fun PSBTScannerView(
    hasCameraPermission: Boolean,
    animatedScanner: AnimatedQRScanner,
    scanProgress: Int,
    isAnimatedScan: Boolean,
    errorMessage: String,
    onRequestPermission: () -> Unit,
    onScanProgress: (progress: Int, isAnimated: Boolean) -> Unit,
    onScanComplete: (psbt: String) -> Unit,
    onBarcodeViewCreated: (CompoundBarcodeView) -> Unit,
    modifier: Modifier = Modifier
) {
    // Track last scanned frame to avoid duplicates
    var lastScannedFrame by remember { mutableStateOf("") }
    var frameJustCaptured by remember { mutableStateOf(false) }
    
    // State for pending result processing (to move heavy work off camera thread)
    var pendingScanComplete by remember { mutableStateOf(false) }
    var barcodeViewRef by remember { mutableStateOf<CompoundBarcodeView?>(null) }
    
    // State for pending progress updates (ensures UI recomposition from camera thread)
    var pendingProgress by remember { mutableStateOf<Pair<Int, Boolean>?>(null) }
    
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
    
    // Process scan result in LaunchedEffect (runs on Main thread, can use withContext)
    LaunchedEffect(pendingScanComplete) {
        if (pendingScanComplete) {
            // Do heavy work on background thread
            val assembledPSBT = withContext(Dispatchers.Default) {
                animatedScanner.getResult()
            }
            
            if (assembledPSBT != null) {
                onScanComplete(assembledPSBT)
            } else {
                barcodeViewRef?.resume()
            }
            pendingScanComplete = false
        }
    }
    
    // Process pending progress updates on main thread (ensures UI recomposition)
    LaunchedEffect(pendingProgress) {
        pendingProgress?.let { (progress, isAnimated) ->
            onScanProgress(progress, isAnimated)
            pendingProgress = null
        }
    }
    
    if (hasCameraPermission) {
        Column(modifier = modifier) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { context ->
                        CompoundBarcodeView(context).apply {
                            barcodeViewRef = this
                            onBarcodeViewCreated(this)
                            setStatusText("")
                            decodeContinuous { result ->
                                result.text?.let { text ->
                                    // Skip duplicate frames
                                    if (text == lastScannedFrame) return@let
                                    lastScannedFrame = text
                                    
                                    // Process the frame (lightweight)
                                    val progress = animatedScanner.processFrame(text)
                                    
                                    if (progress != null) {
                                        frameJustCaptured = true
                                        
                                        // Check if this is an animated scan based on detected format
                                        // AnimatedQRScanner detects: "ur-psbt", "ur", "bbqr", "simple", "single"
                                        val detectedFormat = animatedScanner.getDetectedFormat()
                                        val isAnimated = detectedFormat != null && detectedFormat != "single"
                                        // Use pending state to trigger main thread update via LaunchedEffect
                                        pendingProgress = Pair(progress, isAnimated)
                                        
                                        // Check if scan is complete - trigger async processing
                                        if (animatedScanner.isComplete()) {
                                            pause()
                                            pendingScanComplete = true
                                        }
                                    }
                                }
                            }
                            resume()
                        }
                    },
                    modifier = Modifier.fillMaxSize()
                )

                // Viewfinder overlay - scanning frame indicator (centered)
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        modifier = Modifier.size(250.dp),
                        colors = CardDefaults.cardColors(
                            containerColor = scannerBackgroundColor
                        )
                    ) {}
                }
                
                // Floating progress indicator at top of viewfinder
                if (isAnimatedScan && scanProgress > 0 && scanProgress < 100) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        contentAlignment = Alignment.TopCenter
                    ) {
                        Card(
                            colors = CardDefaults.cardColors(
                                containerColor = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f)
                            ),
                            elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
                        ) {
                            Column(
                                modifier = Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
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

            if (errorMessage.isNotEmpty()) {
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
        }
    } else {
        // No camera permission
        Column(
            modifier = modifier.fillMaxSize(),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera permission is required to scan PSBT QR codes",
                style = MaterialTheme.typography.bodyLarge
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = onRequestPermission) {
                Text("Grant Permission")
            }
        }
    }
}
