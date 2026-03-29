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
import com.gorunjinian.metrovault.lib.qrtools.configureForQRScanning
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
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

    // Channel for queueing scanned frames for background processing.
    // The decodeContinuous callback runs on the main thread (via Handler), so heavy
    // work like BBQr Zlib decompression must be moved off the main thread to avoid ANR.
    val frameChannel = remember { Channel<String>(Channel.UNLIMITED) }
    
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
    
    // Process scanned frames on a background thread to avoid blocking the main thread
    // during heavy operations (BBQr Base32 decode + Zlib decompression, UR fountain codes)
    LaunchedEffect(Unit) {
        withContext(Dispatchers.Default) {
            for (frame in frameChannel) {
                try {
                    val progress = animatedScanner.processFrame(frame)

                    if (progress != null) {
                        withContext(Dispatchers.Main) {
                            frameJustCaptured = true
                            val detectedFormat = animatedScanner.getDetectedFormat()
                            val isAnimated = detectedFormat != null && detectedFormat != "single"
                            onScanProgress(progress, isAnimated)

                            if (animatedScanner.isComplete()) {
                                barcodeViewRef?.pause()
                                pendingScanComplete = true
                            }
                        }
                    }
                } catch (e: Exception) {
                    android.util.Log.e("PSBTScanner", "Frame processing failed: ${e.message}", e)
                }
            }
        }
    }

    // Process completed scan result on background thread, then deliver to caller
    LaunchedEffect(pendingScanComplete) {
        if (pendingScanComplete) {
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
    
    if (hasCameraPermission) {
        Column(modifier = modifier) {
            Box(modifier = Modifier.weight(1f).fillMaxWidth()) {
                AndroidView(
                    factory = { context ->
                        CompoundBarcodeView(context).apply {
                            barcodeViewRef = this
                            onBarcodeViewCreated(this)
                            configureForQRScanning()
                            setStatusText("")
                            decodeContinuous { result ->
                                result.text?.let { text ->
                                    // Debug: Log raw QR content header for BBQr frames
                                    if (text.startsWith("B$") && text.length >= 8) {
                                        val header = text.take(8)
                                        android.util.Log.d("PSBTScanner", "Raw BBQr header: $header")
                                    }

                                    // Skip duplicate frames
                                    if (text == lastScannedFrame) return@let
                                    lastScannedFrame = text

                                    // Queue for background processing — do NOT process on
                                    // the main thread as decoding (BBQr Zlib, UR fountain
                                    // codes) can be expensive and cause ANR.
                                    frameChannel.trySend(text)
                                }
                            }
                            // Note: Don't call resume() here - the parent lifecycle observer handles this
                            // to avoid double initialization (which causes camera freeze)
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
