package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.QRModuleData
import kotlin.math.ceil
import kotlin.math.min

/** Which quadrant is zoomed into, or null for full view. */
enum class Quadrant { TOP_LEFT, TOP_RIGHT, BOTTOM_LEFT, BOTTOM_RIGHT }

/**
 * Canvas-based SeedQR viewer with grid overlay and quadrant zoom.
 *
 * Renders QR module data directly on a Compose Canvas, enabling:
 * - Grid lines between every module (thin) and every 5 modules (thick)
 * - Double-tap to zoom into one of four quadrants
 * - Close button to return to full view
 */
@Composable
fun SeedQRViewer(
    moduleData: QRModuleData,
    showGrid: Boolean,
    modifier: Modifier = Modifier
) {
    var zoomedQuadrant by remember { mutableStateOf<Quadrant?>(null) }

    // Reset zoom when module data changes (format switch)
    LaunchedEffect(moduleData.moduleCount) {
        zoomedQuadrant = null
    }

    val mc = moduleData.moduleCount
    val half = ceil(mc / 2.0).toInt()

    // Compute visible module range based on zoom state
    val startCol: Int
    val endCol: Int
    val startRow: Int
    val endRow: Int

    when (zoomedQuadrant) {
        Quadrant.TOP_LEFT -> {
            startCol = 0; endCol = half; startRow = 0; endRow = half
        }
        Quadrant.TOP_RIGHT -> {
            startCol = mc - half; endCol = mc; startRow = 0; endRow = half
        }
        Quadrant.BOTTOM_LEFT -> {
            startCol = 0; endCol = half; startRow = mc - half; endRow = mc
        }
        Quadrant.BOTTOM_RIGHT -> {
            startCol = mc - half; endCol = mc; startRow = mc - half; endRow = mc
        }
        null -> {
            startCol = 0; endCol = mc; startRow = 0; endRow = mc
        }
    }

    Box(modifier = modifier.aspectRatio(1f)) {
        Canvas(
            modifier = Modifier
                .fillMaxSize()
                .pointerInput(mc) {
                    detectTapGestures(
                        onDoubleTap = { offset ->
                            if (zoomedQuadrant != null) {
                                zoomedQuadrant = null
                            } else {
                                val midX = size.width / 2f
                                val midY = size.height / 2f
                                zoomedQuadrant = when {
                                    offset.x < midX && offset.y < midY -> Quadrant.TOP_LEFT
                                    offset.x >= midX && offset.y < midY -> Quadrant.TOP_RIGHT
                                    offset.x < midX && offset.y >= midY -> Quadrant.BOTTOM_LEFT
                                    else -> Quadrant.BOTTOM_RIGHT
                                }
                            }
                        }
                    )
                }
        ) {
            val colCount = endCol - startCol
            val rowCount = endRow - startRow
            val cellSize = min(size.width / colCount, size.height / rowCount)

            // Center the QR within available space
            val offsetX = (size.width - cellSize * colCount) / 2f
            val offsetY = (size.height - cellSize * rowCount) / 2f

            // White background
            drawRect(Color.White, Offset.Zero, size)

            // Draw dark modules
            for (row in startRow until endRow) {
                for (col in startCol until endCol) {
                    if (moduleData.isDark(col, row)) {
                        drawRect(
                            color = Color.Black,
                            topLeft = Offset(
                                offsetX + (col - startCol) * cellSize,
                                offsetY + (row - startRow) * cellSize
                            ),
                            size = Size(cellSize, cellSize)
                        )
                    }
                }
            }

            // Draw grid overlay
            if (showGrid) {
                val thinWidth = (cellSize * 0.03f).coerceAtLeast(0.5f)
                val thickWidth = (cellSize * 0.08f).coerceAtLeast(1.5f)
                val thinColor = Color.Gray.copy(alpha = 0.5f)
                val thickColor = Color.Red.copy(alpha = 0.6f)

                // Vertical lines
                for (i in 0..colCount) {
                    val globalCol = startCol + i
                    val x = offsetX + i * cellSize
                    val isThick = globalCol % 5 == 0
                    drawLine(
                        color = if (isThick) thickColor else thinColor,
                        start = Offset(x, offsetY),
                        end = Offset(x, offsetY + rowCount * cellSize),
                        strokeWidth = if (isThick) thickWidth else thinWidth
                    )
                }

                // Horizontal lines
                for (i in 0..rowCount) {
                    val globalRow = startRow + i
                    val y = offsetY + i * cellSize
                    val isThick = globalRow % 5 == 0
                    drawLine(
                        color = if (isThick) thickColor else thinColor,
                        start = Offset(offsetX, y),
                        end = Offset(offsetX + colCount * cellSize, y),
                        strokeWidth = if (isThick) thickWidth else thinWidth
                    )
                }
            }
        }

        // Close button when zoomed
        if (zoomedQuadrant != null) {
            IconButton(
                onClick = { zoomedQuadrant = null },
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .padding(4.dp)
                    .size(32.dp)
                    .background(
                        MaterialTheme.colorScheme.surface.copy(alpha = 0.8f),
                        CircleShape
                    )
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_close),
                    contentDescription = "Close zoom",
                    tint = MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.size(18.dp)
                )
            }
        }
    }
}

/**
 * Row with label and Switch to toggle grid overlay.
 */
@Composable
fun GridToggleRow(
    showGrid: Boolean,
    onToggle: (Boolean) -> Unit,
    modifier: Modifier = Modifier
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = "Show Grid Overlay",
            style = MaterialTheme.typography.bodyMedium
        )
        Switch(
            checked = showGrid,
            onCheckedChange = onToggle
        )
    }
}
