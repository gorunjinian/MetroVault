package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/**
 * Content format options for multisig export
 */
enum class ContentFormat(val displayName: String) {
    DESCRIPTOR("Descriptor"),
    BSMS("BSMS")
}

/**
 * ExportMultiSigScreen - Displays the multisig wallet descriptor as QR code.
 * 
 * Supports two toggles:
 * 1. Content format: Descriptor (raw) or BSMS (formatted per BSMS 1.0 spec)
 * 2. QR encoding format: BC-UR v1, BBQr, BC-UR v2
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportMultiSigScreen(
    descriptor: String,
    walletName: String,
    firstAddress: String,
    onBack: () -> Unit
) {
    // Content format state: Descriptor or BSMS
    var selectedContentFormat by remember { mutableStateOf(ContentFormat.DESCRIPTOR) }
    
    // QR encoding format state
    var selectedQRFormat by remember { mutableStateOf(QRCodeUtils.OutputFormat.BBQR) }
    
    // QR code result state
    var qrResult by remember { mutableStateOf<QRCodeUtils.AnimatedQRResult?>(null) }
    var isLoading by remember { mutableStateOf(true) }
    
    // Animation state for multi-frame QR
    var currentFrame by remember { mutableIntStateOf(0) }
    var isPaused by remember { mutableStateOf(false) }
    
    // Prepare content based on selected content format
    val contentToEncode = remember(descriptor, selectedContentFormat, firstAddress) {
        when (selectedContentFormat) {
            ContentFormat.DESCRIPTOR -> descriptor
            ContentFormat.BSMS -> formatAsBSMS(descriptor, firstAddress)
        }
    }
    
    // Security: Clear QR data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            qrResult?.frames?.forEach { it.recycle() }
            qrResult = null
            System.gc()
        }
    }
    
    // Generate QR code when content or QR format changes
    LaunchedEffect(contentToEncode, selectedQRFormat) {
        isLoading = true
        currentFrame = 0
        qrResult = withContext(Dispatchers.IO) {
            generateDescriptorQR(contentToEncode, selectedQRFormat)
        }
        isLoading = false
    }
    
    // Auto-advance frames for animated QR
    LaunchedEffect(qrResult, isPaused) {
        val result = qrResult ?: return@LaunchedEffect
        if (!result.isAnimated || isPaused) return@LaunchedEffect
        
        while (true) {
            delay(result.recommendedFrameDelayMs)
            currentFrame = (currentFrame + 1) % result.frames.size
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export Descriptor") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
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
                .padding(padding)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            
            // Content format toggle: Descriptor / BSMS
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                ContentFormat.entries.forEach { format ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selectedContentFormat == format) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { selectedContentFormat = format }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = format.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedContentFormat == format) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // QR encoding format toggle: BC-UR v1 / BBQr / BC-UR v2
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                QRCodeUtils.OutputFormat.entries.forEach { format ->
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (selectedQRFormat == format) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { selectedQRFormat = format }
                            .padding(vertical = 10.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = format.displayName,
                            style = MaterialTheme.typography.labelLarge,
                            color = if (selectedQRFormat == format) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
            
            // Info card about the descriptor
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp)
                ) {
                    Text(
                        text = walletName,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = "Multisig wallet configuration. Import this descriptor into your coordinator wallet to watch or spend from this wallet.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            // QR Code display
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                if (isLoading) {
                    CircularProgressIndicator()
                } else if (qrResult != null && qrResult!!.frames.isNotEmpty()) {
                    val safeFrame = currentFrame.coerceIn(0, qrResult!!.frames.lastIndex)
                    Card(
                        modifier = Modifier.fillMaxSize(),
                        colors = CardDefaults.cardColors(
                            containerColor = Color.White
                        )
                    ) {
                        Image(
                            bitmap = qrResult!!.frames[safeFrame].asImageBitmap(),
                            contentDescription = "Descriptor QR Code",
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
            
            // Playback controls for animated QR
            if (qrResult?.isAnimated == true) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.Center,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Previous frame button
                    IconButton(
                        onClick = {
                            val total = qrResult!!.frames.size
                            currentFrame = (currentFrame - 1 + total) % total
                        },
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
                        onClick = {
                            val total = qrResult!!.frames.size
                            currentFrame = (currentFrame + 1) % total
                        },
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
                
                // Frame counter
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.secondaryContainer
                    )
                ) {
                    Text(
                        text = "Frame ${currentFrame + 1}/${qrResult!!.totalParts}",
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                        style = MaterialTheme.typography.labelMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
            
            // Done button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Done")
            }
        }
    }
}

private fun formatAsBSMS(descriptor: String, firstAddress: String): String {
    // Remove the checksum (everything after and including the last #)
    val descriptorWithoutChecksum = if (descriptor.contains("#")) {
        descriptor.substringBeforeLast("#")
    } else {
        descriptor
    }
    
    return buildString {
        appendLine("BSMS 1.0")
        appendLine(descriptorWithoutChecksum)
        appendLine("/0/*,/1/*")
        append(firstAddress)
    }
}

/**
 * Generate QR code for descriptor based on selected QR encoding format
 */
private fun generateDescriptorQR(
    content: String,
    format: QRCodeUtils.OutputFormat
): QRCodeUtils.AnimatedQRResult? {
    return when (format) {
        QRCodeUtils.OutputFormat.UR_LEGACY -> generateDescriptorURv1(content)
        QRCodeUtils.OutputFormat.BBQR -> generateDescriptorBBQr(content)
        QRCodeUtils.OutputFormat.UR_MODERN -> generateDescriptorURv2(content)
    }
}

/**
 * Generate BC-UR v1 (ur:crypto-output) encoded descriptor QR
 */
private fun generateDescriptorURv1(content: String): QRCodeUtils.AnimatedQRResult? {
    return try {
        // For BC-UR v1, we use ur:bytes encoding
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        
        val ur = com.gorunjinian.metrovault.lib.qrtools.UR.fromBytes("bytes", contentBytes)
        val encoder = com.gorunjinian.metrovault.lib.qrtools.UREncoder(ur, 250, 50, 0)
        
        if (encoder.isSinglePart) {
            val urString = encoder.nextPart()
            val bitmap = QRCodeUtils.generateQRCode(urString.uppercase(), size = 512)
            bitmap?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = listOf(it),
                    totalParts = 1,
                    isAnimated = false,
                    format = QRCodeUtils.OutputFormat.UR_LEGACY
                )
            }
        } else {
            val seqLen = encoder.seqLen
            val frameStrings = mutableListOf<String>()
            repeat(seqLen) {
                frameStrings.add(encoder.nextPart().uppercase())
            }
            
            val bitmaps = QRCodeUtils.generateConsistentQRCodes(frameStrings, size = 512)
            bitmaps?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = true,
                    recommendedFrameDelayMs = 500,
                    format = QRCodeUtils.OutputFormat.UR_LEGACY
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BC-UR v1 generation failed: ${e.message}")
        // Fall back to plain text
        val bitmap = QRCodeUtils.generateQRCode(content, size = 512)
        bitmap?.let {
            QRCodeUtils.AnimatedQRResult(
                frames = listOf(it),
                totalParts = 1,
                isAnimated = false,
                format = QRCodeUtils.OutputFormat.UR_LEGACY
            )
        }
    }
}

/**
 * Generate BBQr encoded descriptor QR
 * BBQr format: B$[encoding][type][total:2 base36][part:2 base36][data]
 * - encoding: H=hex, 2=zlib+base32, Z=zlib+base32 (legacy), U=uncompressed UTF-8
 * - type: U=Unicode/UTF-8 text, P=PSBT, T=Transaction, J=JSON, C=CBOR
 * - total/part: 2-char base36 encoded (0-indexed for part)
 * For descriptors, we use "UU" = Uncompressed UTF-8 text
 */
@Suppress("KDocUnresolvedReference")
private fun generateDescriptorBBQr(descriptor: String): QRCodeUtils.AnimatedQRResult? {
    return try {
        val maxQrChars = 500
        // BBQr header overhead: "B$UU" + 2 char total + 2 char part = 8 chars
        val headerOverhead = 8
        val maxPayload = maxQrChars - headerOverhead
        
        if (descriptor.length <= maxPayload) {
            // Single frame BBQr
            // Format: B$UU[total:2 base36][part:2 base36][descriptor]
            // total=1, part=0 (0-indexed)
            val totalBase36 = 1.toString(36).padStart(2, '0').uppercase()
            val partBase36 = 0.toString(36).padStart(2, '0').uppercase()
            val bbqrContent = $$"B$UU$${totalBase36}$${partBase36}$$descriptor"
            val bitmap = QRCodeUtils.generateQRCode(bbqrContent, size = 512)
            bitmap?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = listOf(it),
                    totalParts = 1,
                    isAnimated = false,
                    format = QRCodeUtils.OutputFormat.BBQR
                )
            }
        } else {
            // Multi-frame BBQr encoding
            val chunks = descriptor.chunked(maxPayload)
            val total = chunks.size
            
            val frameContents = chunks.mapIndexed { index, chunk ->
                // BBQr header format: B$UU[total:2 base36][part:2 base36][data]
                // Part is 0-indexed per BBQr spec
                val totalBase36 = total.toString(36).padStart(2, '0').uppercase()
                val partBase36 = index.toString(36).padStart(2, '0').uppercase()
                $$"B$UU$${totalBase36}$${partBase36}$$chunk"
            }
            
            val bitmaps = QRCodeUtils.generateConsistentQRCodes(frameContents, size = 512)
            bitmaps?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = it.size > 1,
                    recommendedFrameDelayMs = 500,
                    format = QRCodeUtils.OutputFormat.BBQR
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BBQr generation failed: ${e.message}")
        null
    }
}

/**
 * Generate BC-UR v2 encoded descriptor QR
 */
private fun generateDescriptorURv2(descriptor: String): QRCodeUtils.AnimatedQRResult? {
    return try {
        // For descriptors, we use simple ur:bytes encoding
        // Convert descriptor to bytes and encode as UR
        val descBytes = descriptor.toByteArray(Charsets.UTF_8)
        
        val ur = com.gorunjinian.metrovault.lib.qrtools.UR.fromBytes("bytes", descBytes)
        val encoder = com.gorunjinian.metrovault.lib.qrtools.UREncoder(ur, 250, 50, 0)
        
        if (encoder.isSinglePart) {
            val urString = encoder.nextPart()
            val bitmap = QRCodeUtils.generateQRCode(urString.uppercase(), size = 512)
            bitmap?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = listOf(it),
                    totalParts = 1,
                    isAnimated = false,
                    format = QRCodeUtils.OutputFormat.UR_MODERN
                )
            }
        } else {
            val seqLen = encoder.seqLen
            val frameStrings = mutableListOf<String>()
            repeat(seqLen) {
                frameStrings.add(encoder.nextPart().uppercase())
            }
            
            val bitmaps = QRCodeUtils.generateConsistentQRCodes(frameStrings, size = 512)
            bitmaps?.let {
                QRCodeUtils.AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = true,
                    recommendedFrameDelayMs = 500,
                    format = QRCodeUtils.OutputFormat.UR_MODERN
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BC-UR v2 generation failed: ${e.message}")
        // Fall back to plain text
        val bitmap = QRCodeUtils.generateQRCode(descriptor, size = 512)
        bitmap?.let {
            QRCodeUtils.AnimatedQRResult(
                frames = listOf(it),
                totalParts = 1,
                isAnimated = false,
                format = QRCodeUtils.OutputFormat.UR_MODERN
            )
        }
    }
}
