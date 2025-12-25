package com.gorunjinian.metrovault.feature.transaction.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils

/**
 * Display component for signed PSBT QR codes.
 * 
 * Supports:
 * - Single-frame QR codes
 * - Animated multi-frame QR codes with playback controls
 * - Multiple output formats (BC-UR, BBQr, Base64)
 */
@Composable
fun SignedPSBTDisplay(
    signedPSBT: String,
    signedQRResult: QRCodeUtils.AnimatedQRResult,
    currentFrame: Int,
    selectedFormat: QRCodeUtils.OutputFormat,
    isPaused: Boolean,
    isLoading: Boolean = false,
    onPauseToggle: (Boolean) -> Unit,
    onPreviousFrame: () -> Unit,
    onNextFrame: () -> Unit,
    onFormatChange: (QRCodeUtils.OutputFormat) -> Unit,
    onScanAnother: () -> Unit,
    onDone: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
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

        Spacer(modifier = Modifier.height(12.dp))

        // QR Code Container - using square corners to avoid clipping QR edges
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(1f),
            shape = RectangleShape,
            color = Color.White
        ) {
            // Wrap in Box to ensure single root composable and fix UiComposable warning
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (isLoading) {
                    // Show loading indicator while regenerating QR for new format
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp),
                        color = MaterialTheme.colorScheme.surfaceContainer
                    )
                } else {
                    // Clamp frame index to valid bounds (handles race condition when switching formats)
                    val safeFrameIndex = currentFrame.coerceIn(0, signedQRResult.frames.lastIndex.coerceAtLeast(0))
                    val displayBitmap = signedQRResult.frames.getOrNull(safeFrameIndex)
                    if (displayBitmap != null) {
                        Image(
                            bitmap = displayBitmap.asImageBitmap(),
                            contentDescription = "Signed PSBT QR Code",
                            modifier = Modifier.fillMaxSize(),
                            contentScale = ContentScale.FillBounds
                        )
                    } else {
                        // Placeholder while loading or if frame is null
                        CircularProgressIndicator(
                            modifier = Modifier.size(48.dp),
                            color = MaterialTheme.colorScheme.primary
                        )
                    }
                }
            }
        }
        
        // Frame counter and playback controls for animated QR
        if (signedQRResult.isAnimated) {
            Spacer(modifier = Modifier.height(8.dp))
            
            // Playback controls row
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Previous frame button
                IconButton(
                    onClick = onPreviousFrame,
                    enabled = isPaused
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowLeft,
                        contentDescription = "Previous Frame",
                        modifier = Modifier.size(32.dp),
                        tint = if (isPaused) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
                
                // Pause/Play button
                FilledIconButton(
                    onClick = { onPauseToggle(!isPaused) },
                    modifier = Modifier.size(56.dp),
                    colors = IconButtonDefaults.filledIconButtonColors(
                        containerColor = if (isPaused) MaterialTheme.colorScheme.primary
                                        else MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Icon(
                        painter = painterResource(if (isPaused) R.drawable.ic_play_arrow else R.drawable.ic_pause),
                        contentDescription = if (isPaused) "Play" else "Pause",
                        modifier = Modifier.size(32.dp),
                        tint = if (isPaused) MaterialTheme.colorScheme.onPrimary
                               else MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
                
                // Next frame button
                IconButton(
                    onClick = onNextFrame,
                    enabled = isPaused
                ) {
                    Icon(
                        imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                        contentDescription = "Next Frame",
                        modifier = Modifier.size(32.dp),
                        tint = if (isPaused) MaterialTheme.colorScheme.primary 
                               else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                    )
                }
            }
            
            Spacer(modifier = Modifier.height(4.dp))
            
            // Frame counter
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
            text = if (signedQRResult.isAnimated) {
                if (isPaused) "Paused - use arrows to step through frames"
                else "Tap pause to step through frames manually"
            } else {
                "Scan this QR code with your online wallet to broadcast"
            },
            style = MaterialTheme.typography.bodyMedium,
            textAlign = TextAlign.Center,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))

        OutlinedButton(
            onClick = onScanAnother,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Sign Another Transaction")
        }

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = onDone,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Done")
        }
    }
}
