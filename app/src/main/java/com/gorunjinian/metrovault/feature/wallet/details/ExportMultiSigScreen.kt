package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.domain.service.multisig.BSMS
import com.gorunjinian.metrovault.lib.qrtools.AnimatedQRResult
import com.gorunjinian.metrovault.lib.qrtools.OutputFormat
import com.gorunjinian.metrovault.lib.qrtools.QRCodeGenerator
import com.gorunjinian.metrovault.lib.qrtools.thirdparty.UR
import com.gorunjinian.metrovault.lib.qrtools.thirdparty.UREncoder
import com.gorunjinian.metrovault.lib.qrtools.registry.UROutputDescriptor
import com.gorunjinian.metrovault.domain.service.psbt.PSBTDecoder
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
    firstAddress: String,
    onBack: () -> Unit
) {
    // Content format state: Descriptor or BSMS
    var selectedContentFormat by remember { mutableStateOf(ContentFormat.DESCRIPTOR) }
    
    // QR encoding format state
    var selectedQRFormat by remember { mutableStateOf(OutputFormat.BBQR) }
    
    // QR code result state
    var qrResult by remember { mutableStateOf<AnimatedQRResult?>(null) }
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
    LaunchedEffect(contentToEncode, selectedQRFormat, selectedContentFormat) {
        isLoading = true
        currentFrame = 0
        qrResult = withContext(Dispatchers.IO) {
            generateDescriptorQR(contentToEncode, selectedQRFormat, selectedContentFormat)
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
            SegmentedToggle(
                options = ContentFormat.entries.map { it.displayName },
                selectedIndex = ContentFormat.entries.indexOf(selectedContentFormat),
                onSelect = { index -> selectedContentFormat = ContentFormat.entries[index] },
                modifier = Modifier.fillMaxWidth()
            )
            
            // QR encoding format toggle: BC-UR v1 / BBQr / BC-UR v2
            SegmentedToggle(
                options = OutputFormat.entries.map { it.displayName },
                selectedIndex = OutputFormat.entries.indexOf(selectedQRFormat),
                onSelect = { index -> selectedQRFormat = OutputFormat.entries[index] },
                modifier = Modifier.fillMaxWidth()
            )
            
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

/**
 * Format descriptor as BSMS (BIP-0129) Descriptor Record.
 * Uses centralized BSMS module for proper path restriction extraction.
 *
 * @param descriptor The output descriptor
 * @param firstAddress The first receive address for verification
 * @return BSMS formatted string (4 lines, LF separated)
 */
private fun formatAsBSMS(descriptor: String, firstAddress: String): String {
    return BSMS.formatDescriptor(descriptor, firstAddress)
}

/**
 * Generate QR code for descriptor based on selected QR and content format.
 *
 * @param content The content to encode (descriptor or BSMS formatted)
 * @param format The QR encoding format
 * @param contentFormat The content format (DESCRIPTOR or BSMS)
 */
private fun generateDescriptorQR(
    content: String,
    format: OutputFormat,
    contentFormat: ContentFormat
): AnimatedQRResult? {
    return when (format) {
        OutputFormat.UR_LEGACY -> generateDescriptorURv1(content)
        OutputFormat.BBQR -> generateDescriptorBBQr(content)
        OutputFormat.UR_MODERN -> generateDescriptorURv2(content, contentFormat)
    }
}

/**
 * Generate BC-UR v1 encoded descriptor QR.
 * Uses ur:bytes/ encoding for broad compatibility with legacy wallets.
 * This format wraps raw UTF-8 bytes in CBOR and encodes with fountain codes.
 */
private fun generateDescriptorURv1(content: String): AnimatedQRResult? {
    return try {
        // For BC-UR v1, we use ur:bytes encoding
        val contentBytes = content.toByteArray(Charsets.UTF_8)
        
        val ur = UR.fromBytes("bytes", contentBytes)
        val encoder = UREncoder(ur, 250, 50, 0)
        
        if (encoder.isSinglePart) {
            val urString = encoder.nextPart()
            val bitmap = QRCodeGenerator.generateQRCode(urString.uppercase(), size = 512)
            bitmap?.let {
                AnimatedQRResult(
                    frames = listOf(it),
                    totalParts = 1,
                    isAnimated = false,
                    format = OutputFormat.UR_LEGACY
                )
            }
        } else {
            val seqLen = encoder.seqLen
            val frameStrings = mutableListOf<String>()
            repeat(seqLen) {
                frameStrings.add(encoder.nextPart().uppercase())
            }
            
            val bitmaps = QRCodeGenerator.generateConsistentQRCodes(frameStrings, size = 512)
            bitmaps?.let {
                AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = true,
                    recommendedFrameDelayMs = 500,
                    format = OutputFormat.UR_LEGACY
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BC-UR v1 generation failed: ${e.message}")
        // Fall back to plain text
        val bitmap = QRCodeGenerator.generateQRCode(content, size = 512)
        bitmap?.let {
            AnimatedQRResult(
                frames = listOf(it),
                totalParts = 1,
                isAnimated = false,
                format = OutputFormat.UR_LEGACY
            )
        }
    }
}

/**
 * Generate BBQr encoded descriptor QR.
 * BBQr format: B$[encoding][type][total:2 base36][part:2 base36][data]
 * - encoding: H=hex, 2=base32, Z=zlib+base32, U=raw UTF-8
 * - type: U=Unicode/UTF-8 text, P=PSBT, T=Transaction, J=JSON, C=CBOR
 * - total/part: 2-char base36 encoded (0-indexed for part)
 * 
 * For descriptors, we use "2U" = Base32-encoded UTF-8 text.
 * Note: Sparrow/Coldcard expect Base32 encoding, NOT raw UTF-8.
 * 
 * For even visual distribution (like Sparrow), we:
 * 1. Calculate minimum frames needed at max capacity
 * 2. Distribute data evenly across all frames
 */
@Suppress("KDocUnresolvedReference")
private fun generateDescriptorBBQr(descriptor: String): AnimatedQRResult? {
    return try {
        // Convert descriptor to UTF-8 bytes for Base32 encoding
        val descriptorBytes = descriptor.toByteArray(Charsets.UTF_8)
        
        // Use Base32 encoding (not raw UTF-8) for Sparrow compatibility
        val encoding = '2'  // Base32 encoding
        val dataType = 'U'  // Unicode text type
        
        val maxQrChars = 500
        // BBQr header overhead: "B$" + encoding + type + 2 char total + 2 char part = 8 chars
        val headerOverhead = 8
        val maxDataChars = maxQrChars - headerOverhead
        
        // For Base32: 8 chars = 5 bytes
        // Calculate max bytes per frame (aligned to 5-byte boundary for Base32)
        val maxBytesPerFrame = (maxDataChars / 8) * 5
        
        // Calculate minimum number of frames needed
        val totalBytes = descriptorBytes.size
        val numFrames = kotlin.math.ceil(totalBytes.toDouble() / maxBytesPerFrame).toInt()
            .coerceAtLeast(1)
        
        if (numFrames > 1295) {
            android.util.Log.e("ExportMultiSigScreen", "Descriptor too large for BBQr: $numFrames parts")
            return null
        }
        
        // Distribute bytes evenly across frames
        val baseBytesPerFrame = totalBytes / numFrames
        // Align to 5-byte boundary (for Base32 encoding without padding issues)
        val alignedBytesPerFrame = ((baseBytesPerFrame + 4) / 5) * 5
        
        android.util.Log.d("ExportMultiSigScreen", "BBQr descriptor: $totalBytes bytes in $numFrames frames (~$alignedBytesPerFrame bytes/frame)")
        
        // Split bytes into chunks with even distribution
        val chunks = mutableListOf<ByteArray>()
        var offset = 0
        for (i in 0 until numFrames) {
            val remaining = totalBytes - offset
            val chunkSize = if (i == numFrames - 1) {
                // Last frame takes whatever is left
                remaining
            } else {
                // Non-final frames: use aligned size, but don't exceed remaining
                alignedBytesPerFrame.coerceAtMost(remaining)
            }
            chunks.add(descriptorBytes.copyOfRange(offset, offset + chunkSize))
            offset += chunkSize
        }
        
        // Encode each byte chunk to Base32 and prepend BBQr header
        val frameContents = chunks.mapIndexed { index, chunk ->
            val chunkBase32 = PSBTDecoder.encodeBase32(chunk)
            val totalBase36 = numFrames.toString(36).padStart(2, '0').uppercase()
            val partBase36 = index.toString(36).padStart(2, '0').uppercase()
            $$"B$$${encoding}$${dataType}$${totalBase36}$${partBase36}$${chunkBase32}"
        }
        
        val bitmaps = if (frameContents.size > 1) {
            QRCodeGenerator.generateConsistentQRCodes(frameContents, size = 512)
        } else {
            frameContents.mapNotNull { frame ->
                QRCodeGenerator.generateQRCode(frame, size = 512)
            }
        }
        
        bitmaps?.let {
            AnimatedQRResult(
                frames = it,
                totalParts = it.size,
                isAnimated = it.size > 1,
                recommendedFrameDelayMs = 500,
                format = OutputFormat.BBQR
            )
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BBQr generation failed: ${e.message}")
        null
    }
}

/**
 * Generate BC-UR v2 encoded descriptor QR.
 *
 * For raw descriptors: Uses ur:output-descriptor/ (UR 2.0 standard)
 * For BSMS format: Uses ur:bytes/ (raw text encoding)
 *
 * @param content The content to encode
 * @param contentFormat The content format (affects UR type selection)
 */
private fun generateDescriptorURv2(content: String, contentFormat: ContentFormat): AnimatedQRResult? {
    return try {
        val ur = when (contentFormat) {
            ContentFormat.DESCRIPTOR -> {
                // Use ur:output-descriptor/ for raw descriptor - proper UR 2.0 type
                // UROutputDescriptor encodes as CBOR map with SOURCE key
                UROutputDescriptor(content).toUR()
            }
            ContentFormat.BSMS -> {
                // Use ur:bytes/ for BSMS multi-line text format
                UR.fromBytes("bytes", content.toByteArray(Charsets.UTF_8))
            }
        }

        val encoder = UREncoder(ur, 250, 50, 0)

        if (encoder.isSinglePart) {
            val urString = encoder.nextPart()
            val bitmap = QRCodeGenerator.generateQRCode(urString.uppercase(), size = 512)
            bitmap?.let {
                AnimatedQRResult(
                    frames = listOf(it),
                    totalParts = 1,
                    isAnimated = false,
                    format = OutputFormat.UR_MODERN
                )
            }
        } else {
            val seqLen = encoder.seqLen
            val frameStrings = mutableListOf<String>()
            repeat(seqLen) {
                frameStrings.add(encoder.nextPart().uppercase())
            }

            val bitmaps = QRCodeGenerator.generateConsistentQRCodes(frameStrings, size = 512)
            bitmaps?.let {
                AnimatedQRResult(
                    frames = it,
                    totalParts = it.size,
                    isAnimated = true,
                    recommendedFrameDelayMs = 500,
                    format = OutputFormat.UR_MODERN
                )
            }
        }
    } catch (e: Exception) {
        android.util.Log.e("ExportMultiSigScreen", "BC-UR v2 generation failed: ${e.message}")
        // Fall back to plain text
        val bitmap = QRCodeGenerator.generateQRCode(content, size = 512)
        bitmap?.let {
            AnimatedQRResult(
                frames = listOf(it),
                totalParts = 1,
                isAnimated = false,
                format = OutputFormat.UR_MODERN
            )
        }
    }
}
