package com.gorunjinian.metrovault.feature.scan.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.qr.byteSegment
import com.gorunjinian.metrovault.core.qr.configureForQRScanning
import com.gorunjinian.metrovault.domain.service.scan.CompletedScan
import com.gorunjinian.metrovault.domain.service.scan.ScanSession
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.time.Duration.Companion.milliseconds

private const val TAG = "ScannerView"

/**
 * The camera scanner behind every scanning screen. It owns the camera permission and lifecycle,
 * joins animated QRs, shows their progress, and hands over each finished scan already classified;
 * a screen only decides what to do with the payload.
 *
 * The camera runs while this is in the composition: show it to scan, remove it to stop.
 *
 * @param onScan Called on the main thread with each finished scan. Return true to accept it, which
 *   stops the camera, or false to keep scanning (say why in [errorMessage] or elsewhere on screen).
 * @param errorMessage Shown below the camera when not empty
 * @param showViewfinder Draw the square that flashes as frames are captured
 */
@Composable
fun ScannerView(
    onScan: (CompletedScan) -> Boolean,
    modifier: Modifier = Modifier,
    errorMessage: String = "",
    showViewfinder: Boolean = true
) {
    val context = LocalContext.current
    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) permissionLauncher.launch(Manifest.permission.CAMERA)
    }

    if (hasCameraPermission) {
        Column(modifier = modifier) {
            CameraPreview(
                onScan = onScan,
                showViewfinder = showViewfinder,
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            )

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
        Column(
            modifier = modifier
                .fillMaxSize()
                .padding(16.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = "Camera permission is required to scan QR codes",
                style = MaterialTheme.typography.bodyLarge,
                textAlign = TextAlign.Center
            )
            Spacer(modifier = Modifier.height(16.dp))
            Button(onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }) {
                Text("Grant Permission")
            }
        }
    }
}

/**
 * [ScannerView] as a card inside a form, with a button to close it.
 */
@Composable
fun InlineScanner(
    onScan: (CompletedScan) -> Boolean,
    onCancel: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier
            .fillMaxWidth()
            .height(250.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            ScannerView(
                onScan = onScan,
                modifier = Modifier.fillMaxSize(),
                showViewfinder = false
            )

            Button(
                onClick = onCancel,
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .padding(16.dp)
            ) {
                Text("Cancel Scan")
            }
        }
    }
}

/** A decoded QR on its way from the camera callback to the scan session. */
private class CapturedFrame(val text: String, val byteSegment: ByteArray?)

@Composable
private fun CameraPreview(
    onScan: (CompletedScan) -> Boolean,
    showViewfinder: Boolean,
    modifier: Modifier = Modifier
) {
    // The frame loop below runs once; read the latest handler through state.
    val currentOnScan by rememberUpdatedState(onScan)

    val session = remember { ScanSession() }
    // The camera callback runs on the main thread, where joining a frame (BBQr decompression,
    // fountain codes) could stall the UI, so frames queue here for the loop below.
    val frames = remember { Channel<CapturedFrame>(Channel.UNLIMITED) }

    var barcodeView by remember { mutableStateOf<CompoundBarcodeView?>(null) }
    var isResumed by remember { mutableStateOf(false) }
    var accepted by remember { mutableStateOf(false) }
    var progress by remember { mutableStateOf<ScanSession.Update.Progress?>(null) }
    var frameJustCaptured by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        for (frame in frames) {
            // Frames still queued when a scan is accepted belong to nothing.
            if (accepted) continue
            val update = withContext(Dispatchers.Default) { session.receive(frame.text, frame.byteSegment) }
            when (update) {
                is ScanSession.Update.Ignored -> {}
                is ScanSession.Update.Progress -> {
                    frameJustCaptured = true
                    progress = update
                }
                is ScanSession.Update.Complete -> {
                    frameJustCaptured = true
                    progress = null
                    accepted = currentOnScan(update.scan)
                }
            }
        }
    }

    LaunchedEffect(frameJustCaptured) {
        if (frameJustCaptured) {
            delay(200.milliseconds)
            frameJustCaptured = false
        }
    }

    // The camera is resumed only here, once the view exists and the screen is resumed. Resuming
    // it from the view factory as well initializes it twice, which freezes the preview.
    LaunchedEffect(barcodeView, isResumed, accepted) {
        val view = barcodeView ?: return@LaunchedEffect
        if (isResumed && !accepted) view.resumeSafely() else view.pauseSafely()
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            when (event) {
                Lifecycle.Event.ON_RESUME -> isResumed = true
                Lifecycle.Event.ON_PAUSE -> {
                    isResumed = false
                    // Release the camera now rather than on the next recomposition.
                    barcodeView?.pauseSafely()
                }
                else -> {}
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            barcodeView?.pauseSafely()
            // Drops any frame still queued; it may hold part of a seed.
            frames.cancel()
            session.reset()
        }
    }

    Box(modifier = modifier) {
        AndroidView(
            factory = { context ->
                CompoundBarcodeView(context).apply {
                    configureForQRScanning()
                    setStatusText("")
                    var lastFrame: String? = null
                    decodeContinuous { result ->
                        val text = result.text ?: return@decodeContinuous
                        // The camera decodes the code it is held at many times over.
                        if (text == lastFrame) return@decodeContinuous
                        lastFrame = text
                        frames.trySend(CapturedFrame(text, result.result.byteSegment()))
                    }
                    barcodeView = this
                }
            },
            modifier = Modifier.fillMaxSize()
        )

        if (showViewfinder) {
            val viewfinderColor by animateColorAsState(
                targetValue = if (frameJustCaptured) {
                    MaterialTheme.colorScheme.primary.copy(alpha = 0.3f)
                } else {
                    MaterialTheme.colorScheme.surface.copy(alpha = 0.1f)
                },
                animationSpec = tween(150),
                label = "scanFlash"
            )
            Card(
                modifier = Modifier
                    .align(Alignment.Center)
                    .size(250.dp),
                colors = CardDefaults.cardColors(containerColor = viewfinderColor)
            ) {}
        }

        progress?.takeIf { it.percent > 0 }?.let {
            ScanProgressCard(
                progress = it,
                modifier = Modifier
                    .align(Alignment.TopCenter)
                    .padding(16.dp)
            )
        }
    }
}

/** Progress of an animated QR, floating over the camera. */
@Composable
private fun ScanProgressCard(
    progress: ScanSession.Update.Progress,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier,
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
                progress = { progress.percent / 100f },
                modifier = Modifier
                    .width(200.dp)
                    .height(8.dp)
                    .clip(RoundedCornerShape(4.dp)),
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = progress.label,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}

private fun CompoundBarcodeView.resumeSafely() {
    try {
        resume()
    } catch (e: Exception) {
        AppLog.e(TAG, e) { "Failed to resume camera" }
    }
}

private fun CompoundBarcodeView.pauseSafely() {
    try {
        pause()
    } catch (e: Exception) {
        AppLog.e(TAG, e) { "Failed to pause camera" }
    }
}
