package com.gorunjinian.metrovault.core.ui.components

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Single-frame or animated QR display with playback controls (pause, step back/forward) and a
 * frame counter. Frame advancement is self-contained and resets whenever [qrResult] changes;
 * emits its pieces as siblings, so the parent Column's spacing applies between them.
 */
@Composable
fun AnimatedQrDisplay(
    qrResult: AnimatedQRResult?,
    contentDescription: String,
    modifier: Modifier = Modifier
) {
    var currentFrame by remember(qrResult) { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }

    // Auto-advance frames for animated QR
    LaunchedEffect(qrResult, isPaused) {
        val result = qrResult ?: return@LaunchedEffect
        if (!result.isAnimated || isPaused) return@LaunchedEffect

        while (true) {
            delay(result.recommendedFrameDelayMs.milliseconds)
            currentFrame = (currentFrame + 1) % result.frames.size
        }
    }

    Box(
        contentAlignment = Alignment.Center,
        modifier = modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        if (qrResult != null && qrResult.frames.isNotEmpty()) {
            val safeFrame = currentFrame.coerceIn(0, qrResult.frames.lastIndex)
            Card(
                modifier = Modifier.fillMaxSize(),
                colors = CardDefaults.cardColors(containerColor = Color.White)
            ) {
                Image(
                    bitmap = qrResult.frames[safeFrame].asImageBitmap(),
                    contentDescription = contentDescription,
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            Text(
                text = "Failed to generate QR code",
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    if (qrResult?.isAnimated == true) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Previous frame button
            IconButton(
                onClick = {
                    val total = qrResult.frames.size
                    currentFrame = (currentFrame - 1 + total) % total
                },
                enabled = isPaused
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_left),
                    contentDescription = "Previous Frame",
                    modifier = Modifier.size(32.dp),
                    tint = if (isPaused) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }

            // Pause/Play button
            FilledIconButton(
                onClick = { isPaused = !isPaused },
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
                onClick = { currentFrame = (currentFrame + 1) % qrResult.frames.size },
                enabled = isPaused
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_chevron_right),
                    contentDescription = "Next Frame",
                    modifier = Modifier.size(32.dp),
                    tint = if (isPaused) MaterialTheme.colorScheme.primary
                           else MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                )
            }
        }

        // Frame counter
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Text(
                    text = "Frame ${currentFrame + 1}/${qrResult.totalParts}",
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.Bold
                )
            }
        }
    }
}
