package com.gorunjinian.metrovault.feature.wallet.details

import android.Manifest
import android.content.ClipData
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboard
import androidx.compose.ui.platform.toClipEntry
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.Block
import com.gorunjinian.metrovault.lib.bitcoin.MessageSigning
import com.journeyapps.barcodescanner.CompoundBarcodeView
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Sign/Verify Message Screen
 * 
 * Allows users to:
 * - Sign messages with their wallet's private key (BIP-137)
 * - Verify signatures against an address and message
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SignMessageScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    prefilledAddress: String? = null
) {
    var addressInput by remember { mutableStateOf(prefilledAddress ?: "") }
    var messageInput by remember { mutableStateOf("") }
    var signatureInput by remember { mutableStateOf("") }
    
    var isProcessing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf("") }
    var successMessage by remember { mutableStateOf("") }
    
    var isScanning by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    
    // Signature format: Electrum (default) or BIP137
    var signatureFormat by remember { mutableStateOf(MessageSigning.SignatureFormat.ELECTRUM) }
    
    // Signature QR code display
    var signatureQRBitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var showSignatureQR by remember { mutableStateOf(false) }
    
    val scope = rememberCoroutineScope()
    val clipboard = LocalClipboard.current

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
        if (granted) {
            isScanning = true
        }
    }
    
    // Track what we're scanning for: "address" or "message"
    var scanMode by remember { mutableStateOf("address") }
    
    // Determine button state: Sign if address+message only, Verify if all three
    val canSign = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isBlank()
    val canVerify = addressInput.isNotBlank() && messageInput.isNotBlank() && signatureInput.isNotBlank()
    
    // Handle back gesture when scanning - close scanner instead of navigating back
    BackHandler(enabled = isScanning) {
        isScanning = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { 
                    Text(
                        if (isScanning) {
                            if (scanMode == "address") "Scan Address QR" else "Scan Message QR"
                        } else {
                            "Sign/Verify Message"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { 
                        if (isScanning) {
                            isScanning = false
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        // Signature QR Dialog
        if (showSignatureQR && signatureQRBitmap != null) {
            Dialog(onDismissRequest = { showSignatureQR = false }) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surface
                    )
                ) {
                    Column(
                        modifier = Modifier.padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            text = "Signature QR Code",
                            style = MaterialTheme.typography.titleLarge
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Card(
                            modifier = Modifier.size(280.dp)
                        ) {
                            Image(
                                bitmap = signatureQRBitmap!!.asImageBitmap(),
                                contentDescription = "Signature QR Code",
                                modifier = Modifier.fillMaxSize()
                            )
                        }
                        Spacer(modifier = Modifier.height(16.dp))
                        Text(
                            text = "Scan to verify the signature",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(16.dp))
                        Button(
                            onClick = { showSignatureQR = false },
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            Text("Close")
                        }
                    }
                }
            }
        }
        
        if (isScanning) {
            // QR Scanner View (inside Scaffold)
            QRScannerContent(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                onScanned = { result ->
                    when (scanMode) {
                        "address" -> {
                            // Extract address from bitcoin: URI if present
                            val address = if (result.startsWith("bitcoin:", ignoreCase = true)) {
                                result.substringAfter(":").substringBefore("?")
                            } else {
                                result
                            }
                            addressInput = address
                        }
                        "message" -> {
                            // Check for Sparrow-style signmessage format: "signmessage <path> ascii:<message>"
                            if (result.startsWith("signmessage ", ignoreCase = true)) {
                                val parsed = parseSignMessageQR(result, wallet)
                                if (parsed != null) {
                                    addressInput = parsed.first
                                    messageInput = parsed.second
                                } else {
                                    // Couldn't parse, show error
                                    errorMessage = "Could not parse signmessage QR or derive address"
                                }
                            } else {
                                // Plain message QR
                                messageInput = result
                            }
                        }
                    }
                    isScanning = false
                }
            )
        } else {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
            // Info Card
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Sign messages to prove ownership of an address, or verify signatures from others.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
            
            // Signature Format Toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Signature Format",
                    style = MaterialTheme.typography.bodyMedium
                )
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            androidx.compose.foundation.shape.RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    MessageSigning.SignatureFormat.entries.forEach { format ->
                        Box(
                            modifier = Modifier
                                .clip(androidx.compose.foundation.shape.RoundedCornerShape(6.dp))
                                .background(
                                    if (signatureFormat == format) MaterialTheme.colorScheme.primary
                                    else Color.Transparent
                                )
                                .clickable { signatureFormat = format }
                                .padding(horizontal = 12.dp, vertical = 6.dp)
                        ) {
                            Text(
                                text = format.displayName,
                                style = MaterialTheme.typography.labelMedium,
                                color = if (signatureFormat == format) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
            
            // Address Input with QR Scanner
            SecureOutlinedTextField(
                value = addressInput,
                onValueChange = { 
                    addressInput = it
                    errorMessage = ""
                    successMessage = ""
                },
                label = { Text("Bitcoin Address") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                enabled = !isProcessing,
                trailingIcon = {
                    IconButton(
                        onClick = {
                            scanMode = "address"
                            permissionLauncher.launch(Manifest.permission.CAMERA)
                        },
                        enabled = !isProcessing
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_qr_code_scanner),
                            contentDescription = "Scan QR"
                        )
                    }
                }
            )
            
            // Message Input (larger)
            SecureOutlinedTextField(
                value = messageInput,
                onValueChange = { 
                    messageInput = it
                    errorMessage = ""
                    successMessage = ""
                },
                label = { Text("Message") },
                modifier = Modifier
                    .fillMaxWidth()
                    .heightIn(min = 120.dp),
                minLines = 4,
                maxLines = 10,
                enabled = !isProcessing
            )
            
            // Signature Input
            SecureOutlinedTextField(
                value = signatureInput,
                onValueChange = { 
                    signatureInput = it
                    errorMessage = ""
                    successMessage = ""
                },
                label = { Text("Signature") },
                modifier = Modifier.fillMaxWidth(),
                minLines = 2,
                maxLines = 4,
                enabled = !isProcessing,
                trailingIcon = {
                    if (signatureInput.isNotBlank()) {
                        IconButton(
                            onClick = {
                                scope.launch {
                                    val clipData = ClipData.newPlainText("signature", signatureInput)
                                    clipboard.setClipEntry(clipData.toClipEntry())
                                }
                            }
                        ) {
                            Icon(
                                painter = painterResource(R.drawable.ic_copy),
                                contentDescription = "Copy signature"
                            )
                        }
                    }
                }
            )
            
            // Sign by QR Button
            OutlinedButton(
                onClick = {
                    scanMode = "message"
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isProcessing
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(18.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign by QR")
            }
            
            // Show Signature QR Button (only when signature is present)
            if (signatureInput.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        scope.launch {
                            signatureQRBitmap = withContext(Dispatchers.Default) {
                                com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils.generateQRCode(signatureInput)
                            }
                            showSignatureQR = true
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_2),
                        contentDescription = null,
                        modifier = Modifier.size(18.dp)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Show Signature QR")
                }
            }
            
            // Error message
            if (errorMessage.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = errorMessage,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }
            
            // Success message
            if (successMessage.isNotEmpty()) {
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = successMessage,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
            
            Spacer(modifier = Modifier.weight(1f))
            
            // Sign/Verify Button
            Button(
                onClick = {
                    scope.launch {
                        isProcessing = true
                        errorMessage = ""
                        successMessage = ""
                        
                        try {
                            if (canSign) {
                                // Sign the message
                                val result = withContext(Dispatchers.Default) {
                                    signMessage(wallet, addressInput, messageInput, signatureFormat)
                                }
                                result.fold(
                                    onSuccess = { signature ->
                                        signatureInput = signature
                                        successMessage = "Message signed successfully ✓"
                                    },
                                    onFailure = { error ->
                                        errorMessage = error.message ?: "Failed to sign message"
                                    }
                                )
                            } else if (canVerify) {
                                // Verify the signature
                                val isValid = withContext(Dispatchers.Default) {
                                    MessageSigning.verifyMessage(
                                        message = messageInput,
                                        signatureBase64 = signatureInput.trim(),
                                        address = addressInput.trim(),
                                        chainHash = Block.LivenetGenesisBlock.hash
                                    )
                                }
                                successMessage = if (isValid) {
                                    "Signature is valid ✓"
                                } else {
                                    errorMessage = "Signature is invalid ✗"
                                    ""
                                }
                            }
                        } catch (e: Exception) {
                            errorMessage = e.message ?: "An error occurred"
                        } finally {
                            isProcessing = false
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = (canSign || canVerify) && !isProcessing
            ) {
                if (isProcessing) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(24.dp),
                        color = MaterialTheme.colorScheme.onPrimary
                    )
                } else {
                    Text(if (canVerify) "Verify" else "Sign")
                }
            }
            
            // Clear button
            if (addressInput.isNotBlank() || messageInput.isNotBlank() || signatureInput.isNotBlank()) {
                OutlinedButton(
                    onClick = {
                        addressInput = ""
                        messageInput = ""
                        signatureInput = ""
                        errorMessage = ""
                        successMessage = ""
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isProcessing
                ) {
                    Text("Clear All")
                }
            }
            }
        }
    }
}

/**
 * Sign a message with the private key corresponding to the given address.
 */
private fun signMessage(
    wallet: Wallet,
    address: String,
    message: String,
    format: MessageSigning.SignatureFormat = MessageSigning.SignatureFormat.ELECTRUM
): Result<String> {
    // First, check if the address belongs to this wallet and get its index
    val addressCheck = wallet.checkAddressBelongsToWallet(address.trim())
        ?: return Result.failure(Exception("Could not check address"))
    
    if (!addressCheck.belongs) {
        return Result.failure(Exception("Address does not belong to this wallet"))
    }
    
    val index = addressCheck.index
        ?: return Result.failure(Exception("Could not determine address index"))
    val isChange = addressCheck.isChange ?: false
    
    // Get the wallet state and derive the private key
    val walletState = wallet.getActiveWalletState()
        ?: return Result.failure(Exception("No active wallet"))
    
    val masterPrivateKey = walletState.getMasterPrivateKey()
        ?: return Result.failure(Exception("Master private key not available"))
    
    // Derive the private key at the address's path
    // Account key is already derived, we just need to derive change/index
    val changeIndex = if (isChange) 1L else 0L
    val addressPrivateKey = masterPrivateKey
        .derivePrivateKey(walletState.derivationPath)
        .derivePrivateKey(changeIndex)
        .derivePrivateKey(index.toLong())
    
    // Determine address type for BIP137 format
    val addressType = when {
        address.startsWith("bc1q") || address.startsWith("tb1q") -> MessageSigning.AddressType.P2WPKH
        address.startsWith("bc1p") || address.startsWith("tb1p") -> MessageSigning.AddressType.P2TR
        address.startsWith("3") || address.startsWith("2") -> MessageSigning.AddressType.P2SH_P2WPKH
        else -> MessageSigning.AddressType.P2PKH
    }
    
    // Sign the message
    return try {
        val signature = MessageSigning.signMessage(message, addressPrivateKey.privateKey, format, addressType)
        Result.success(signature)
    } catch (e: Exception) {
        Result.failure(Exception("Failed to sign message: ${e.message}"))
    }
}

/**
 * Parse a Sparrow-style signmessage QR code.
 * Format: "signmessage m/84h/0h/0h/0/0 ascii:<message>"
 * 
 * @return Pair of (address, message) or null if parsing fails
 */
private fun parseSignMessageQR(qrContent: String, wallet: Wallet): Pair<String, String>? {
    return try {
        // Remove "signmessage " prefix
        val content = qrContent.removePrefix("signmessage ").removePrefix("SIGNMESSAGE ")
        
        // Find the space between path and message format
        val parts = content.split(" ", limit = 2)
        if (parts.size < 2) return null
        
        val pathString = parts[0]  // e.g., "m/84h/0h/0h/0/0"
        val messageWithFormat = parts[1]  // e.g., "ascii:test"
        
        // Extract message (remove format prefix like "ascii:")
        val message = when {
            messageWithFormat.startsWith("ascii:", ignoreCase = true) -> 
                messageWithFormat.removePrefix("ascii:").removePrefix("ASCII:")
            messageWithFormat.startsWith("utf8:", ignoreCase = true) -> 
                messageWithFormat.removePrefix("utf8:").removePrefix("UTF8:")
            messageWithFormat.startsWith("hex:", ignoreCase = true) -> {
                // Decode hex to string
                val hex = messageWithFormat.removePrefix("hex:").removePrefix("HEX:")
                String(hex.chunked(2).map { it.toInt(16).toByte() }.toByteArray())
            }
            else -> messageWithFormat // Assume plain text
        }
        
        // Parse the derivation path and derive the address
        val address = deriveAddressFromPath(pathString, wallet) ?: return null
        
        Pair(address, message)
    } catch (_: Exception) {
        null
    }
}

/**
 * Derive a Bitcoin address from a derivation path string.
 * Supports formats like "m/84h/0h/0h/0/0" or "m/84'/0'/0'/0/0"
 */
private fun deriveAddressFromPath(pathString: String, wallet: Wallet): String? {
    return try {
        val walletState = wallet.getActiveWalletState() ?: return null
        val masterPrivateKey = walletState.getMasterPrivateKey() ?: return null
        
        // Parse the path - handle both h and ' for hardened indicators
        val normalized = pathString
            .replace("'", "h")
            .removePrefix("m/")
        
        val pathComponents = normalized.split("/").mapNotNull { component ->
            val isHardened = component.endsWith("h")
            val indexStr = component.removeSuffix("h")
            val index = indexStr.toLongOrNull() ?: return@mapNotNull null
            if (isHardened) {
                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(index)
            } else {
                index
            }
        }
        
        if (pathComponents.isEmpty()) return null
        
        // Derive the key at the full path
        val derivedKey = masterPrivateKey.derivePrivateKey(pathComponents)
        val publicKey = derivedKey.publicKey
        
        // Determine address type from path (84h = P2WPKH, 49h = P2SH-P2WPKH, 44h = P2PKH, 86h = P2TR)
        val purposeIndex = pathComponents.getOrNull(0) ?: return null
        val chainHash = Block.LivenetGenesisBlock.hash
        
        when (purposeIndex) {
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(84) -> 
                publicKey.p2wpkhAddress(chainHash)
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(49) -> 
                publicKey.p2shOfP2wpkhAddress(chainHash)
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(44) -> 
                publicKey.p2pkhAddress(chainHash)
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(86) -> 
                publicKey.xOnly().p2trAddress(chainHash)
            else -> publicKey.p2wpkhAddress(chainHash) // Default to P2WPKH
        }
    } catch (_: Exception) {
        null
    }
}

/**
 * QR Scanner content composable for scanning addresses or messages.
 * This is a simple scanner view without its own title bar (the parent Scaffold handles that).
 */
@Suppress("COMPOSE_APPLIER_CALL_MISMATCH")
@Composable
private fun QRScannerContent(
    modifier: Modifier = Modifier,
    onScanned: (String) -> Unit
) {
    var hasScanned by remember { mutableStateOf(false) }
    val lifecycleOwner = LocalLifecycleOwner.current
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }
    
    // Track lifecycle resume state to coordinate with view readiness
    var isLifecycleResumed by remember { mutableStateOf(false) }
    
    // Resume camera when BOTH: view is ready AND lifecycle is resumed
    // This avoids double initialization (factory + lifecycle observer) that caused camera freeze
    LaunchedEffect(barcodeView, isLifecycleResumed) {
        if (barcodeView != null && isLifecycleResumed) {
            barcodeView?.resume()
        }
    }
    
    // Handle lifecycle for camera resume/pause
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
            barcodeView?.pause()
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }
    
    AndroidView(
        factory = { context ->
            CompoundBarcodeView(context).apply {
                barcodeView = this
                setStatusText("")
                decodeContinuous { result ->
                    if (!hasScanned && result.text != null) {
                        hasScanned = true
                        pause()
                        onScanned(result.text)
                    }
                }
                // Note: Don't call resume() here - the LaunchedEffect handles this
                // to avoid double initialization (which causes camera freeze)
            }
        },
        modifier = modifier
    )
}
