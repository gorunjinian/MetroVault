package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.core.util.SecurityUtils

/**
 * ExportOptionsScreen - Redesigned with 3 export cards:
 * 1. View Account Keys - Public/Private toggle (opens to public by default)
 * 2. View Descriptors - Public/Private toggle (opens to public by default)
 * 3. View Seed Phrase - Requires password confirmation
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    // Main view state: "options", "keys", "descriptors", "seed"
    var currentView by remember { mutableStateOf("options") }
    
    // For keys and descriptors views: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }
    
    // QR code bitmaps
    var currentQR by remember { mutableStateOf<Bitmap?>(null) }
    
    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordAction by remember { mutableStateOf<String?>(null) }  // "keys_private", "descriptors_private", "seed"
    var passwordError by remember { mutableStateOf("") }
    
    // Warning dialog for seed phrase
    var showSeedWarningDialog by remember { mutableStateOf(false) }
    
    // Wallet data
    val xpub = remember(wallet) { wallet.getActiveXpub() ?: "" }
    val xpriv = remember(wallet) { wallet.getActiveXpriv() ?: "" }
    val publicDescriptor = remember(wallet) { wallet.getActiveUnifiedDescriptor() ?: "" }
    val privateDescriptor = remember(wallet) { wallet.getActivePrivateDescriptor() ?: "" }
    
    // Key prefixes for display
    val publicKeyPrefix = when {
        xpub.startsWith("zpub") -> "zpub"
        xpub.startsWith("ypub") -> "ypub"
        else -> "xpub"
    }
    val privateKeyPrefix = when {
        xpriv.startsWith("zprv") -> "zprv"
        xpriv.startsWith("yprv") -> "yprv"
        else -> "xprv"
    }
    
    // Determine the data to display based on current view and showPrivate state
    val displayData = when (currentView) {
        "keys" -> if (showPrivate) xpriv else xpub
        "descriptors" -> if (showPrivate) privateDescriptor else publicDescriptor
        else -> ""
    }
    
    // Generate QR code when display data changes
    LaunchedEffect(displayData) {
        if (displayData.isNotEmpty()) {
            currentQR = null // Show loading
            withContext(Dispatchers.IO) {
                currentQR = QRCodeUtils.generateQRCode(displayData)
            }
        }
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(when (currentView) {
                        "keys" -> "Account Keys"
                        "descriptors" -> "Descriptors"
                        "seed" -> "Seed Phrase"
                        else -> "Export"
                    })
                },
                navigationIcon = {
                    IconButton(onClick = {
                        when (currentView) {
                            "options" -> onBack()
                            else -> {
                                currentView = "options"
                                showPrivate = false  // Reset to public when going back
                                currentQR = null
                            }
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
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))  // Top padding handled by scaffold
            
            when (currentView) {
                "options" -> ExportOptionsCards(
                    onViewAccountKeys = { currentView = "keys" },
                    onViewDescriptors = { currentView = "descriptors" },
                    onViewSeedPhrase = { showSeedWarningDialog = true }
                )
                
                "keys" -> AccountKeysView(
                    showPrivate = showPrivate,
                    xpub = xpub,
                    xpriv = xpriv,
                    qrBitmap = currentQR,
                    publicKeyPrefix = publicKeyPrefix,
                    privateKeyPrefix = privateKeyPrefix,
                    onToggleToPublic = { showPrivate = false },
                    onToggleToPrivate = {
                        passwordAction = "keys_private"
                        passwordError = ""
                        showPasswordDialog = true
                    }
                )
                
                "descriptors" -> DescriptorsView(
                    showPrivate = showPrivate,
                    publicDescriptor = publicDescriptor,
                    privateDescriptor = privateDescriptor,
                    qrBitmap = currentQR,
                    onToggleToPublic = { showPrivate = false },
                    onToggleToPrivate = {
                        passwordAction = "descriptors_private"
                        passwordError = ""
                        showPasswordDialog = true
                    }
                )
                
                "seed" -> SeedPhraseView(
                    wallet = wallet,
                    onHide = { 
                        currentView = "options" 
                    }
                )
            }
            
            Spacer(modifier = Modifier.height(24.dp))  // Bottom padding
        }
    }
    
    // Seed phrase warning dialog
    if (showSeedWarningDialog) {
        AlertDialog(
            onDismissRequest = { showSeedWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text("Your seed phrase is the master key to your funds. Never share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSeedWarningDialog = false
                        passwordAction = "seed"
                        passwordError = ""
                        showPasswordDialog = true
                    }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSeedWarningDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = {
                Icon(painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            }
        )
    }
    
    // Unified password confirmation dialog
    if (showPasswordDialog && passwordAction != null) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                passwordAction = null
                passwordError = ""
            },
            onConfirm = { password ->
                val isDecoy = wallet.isDecoyMode
                val isValid = if (isDecoy) {
                    secureStorage.isDecoyPassword(password)
                } else {
                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                }

                if (isValid) {
                    showPasswordDialog = false
                    when (passwordAction) {
                        "keys_private" -> showPrivate = true
                        "descriptors_private" -> showPrivate = true
                        "seed" -> currentView = "seed"
                    }
                    passwordAction = null
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }
}

// ==================== Export Options Cards ====================

@Composable
private fun ExportOptionsCards(
    onViewAccountKeys: () -> Unit,
    onViewDescriptors: () -> Unit,
    onViewSeedPhrase: () -> Unit
) {
    Text(
        text = "Export Options",
        style = MaterialTheme.typography.headlineSmall
    )

    // Card 1: View Account Keys
    ElevatedCard(
        onClick = onViewAccountKeys,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_key),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = "View Account Keys",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Extended public & private keys",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Card 2: View Descriptors
    ElevatedCard(
        onClick = onViewDescriptors,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_desciption),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.primary
            )
            Column {
                Text(
                    text = "View Output Descriptors",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Public & spending descriptors",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }

    // Card 3: View Seed Phrase
    ElevatedCard(
        onClick = onViewSeedPhrase,
        modifier = Modifier.fillMaxWidth()
    ) {
        Row(
            modifier = Modifier.padding(16.dp),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_privacy_tip),
                contentDescription = null,
                modifier = Modifier.size(40.dp),
                tint = MaterialTheme.colorScheme.error
            )
            Column {
                Text(
                    text = "View Seed Phrase",
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = "Show your recovery seed phrase",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error
                )
            }
        }
    }
}

// ==================== Public/Private Toggle ====================

@Composable
private fun PublicPrivateToggle(
    showPrivate: Boolean,
    onSelectPublic: () -> Unit,
    onSelectPrivate: () -> Unit
) {
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
        // Public option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (!showPrivate) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onSelectPublic() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Public",
                style = MaterialTheme.typography.labelLarge,
                color = if (!showPrivate) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Private option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (showPrivate) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onSelectPrivate() }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Private",
                style = MaterialTheme.typography.labelLarge,
                color = if (showPrivate) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

// ==================== Account Keys View ====================

@Composable
private fun AccountKeysView(
    showPrivate: Boolean,
    xpub: String,
    xpriv: String,
    qrBitmap: Bitmap?,
    publicKeyPrefix: String,
    privateKeyPrefix: String,
    onToggleToPublic: () -> Unit,
    onToggleToPrivate: () -> Unit
) {
    val context = LocalContext.current
    val displayKey = if (showPrivate) xpriv else xpub
    val keyType = if (showPrivate) "Private" else "Public"
    val prefix = if (showPrivate) privateKeyPrefix else publicKeyPrefix

    // Public/Private Toggle
    PublicPrivateToggle(
        showPrivate = showPrivate,
        onSelectPublic = onToggleToPublic,
        onSelectPrivate = onToggleToPrivate
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Title
    Text(
        text = "Extended $keyType Key ($prefix)",
        style = MaterialTheme.typography.titleMedium
    )

    // Security warning for private key
    if (showPrivate) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "This key can spend all funds in this account.\nNever share it!",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

    // QR Code
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        if (qrBitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        SecurityUtils.copyToClipboardWithAutoClear(
                            context = context,
                            label = "Extended $keyType Key",
                            text = displayKey,
                            delayMs = 20_000
                        )
                        Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "$keyType Key QR Code - Tap to copy",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            CircularProgressIndicator()
        }
    }

    Text(
        text = "Tap QR code to copy",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Key text display
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (showPrivate) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Text(
            text = displayKey,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ==================== Descriptors View ====================

@Composable
private fun DescriptorsView(
    showPrivate: Boolean,
    publicDescriptor: String,
    privateDescriptor: String,
    qrBitmap: Bitmap?,
    onToggleToPublic: () -> Unit,
    onToggleToPrivate: () -> Unit
) {
    val context = LocalContext.current
    val displayDescriptor = if (showPrivate) privateDescriptor else publicDescriptor
    val descriptorType = if (showPrivate) "Spending" else "Watch-Only"

    // Public/Private Toggle
    PublicPrivateToggle(
        showPrivate = showPrivate,
        onSelectPublic = onToggleToPublic,
        onSelectPrivate = onToggleToPrivate
    )

    Spacer(modifier = Modifier.height(8.dp))

    // Title
    Text(
        text = "$descriptorType Descriptor",
        style = MaterialTheme.typography.titleMedium
    )

    // Info/Warning card
    if (showPrivate) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = "This descriptor contains private keys.\nAnyone with it can spend your UTXOs",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    } else {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.surfaceVariant
            )
        ) {
            Text(
                text = "Import this to an online wallet as a watch-only wallet.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium
            )
        }
    }

    // QR Code
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) {
        if (qrBitmap != null) {
            Card(
                modifier = Modifier
                    .fillMaxSize()
                    .clickable {
                        SecurityUtils.copyToClipboardWithAutoClear(
                            context = context,
                            label = "$descriptorType Descriptor",
                            text = displayDescriptor,
                            delayMs = 20_000
                        )
                        Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                    }
            ) {
                Image(
                    bitmap = qrBitmap.asImageBitmap(),
                    contentDescription = "$descriptorType Descriptor QR Code - Tap to copy",
                    modifier = Modifier.fillMaxSize()
                )
            }
        } else {
            CircularProgressIndicator()
        }
    }

    Text(
        text = "Tap QR code to copy",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    // Descriptor text display
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = if (showPrivate) {
            CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
            )
        } else {
            CardDefaults.cardColors()
        }
    ) {
        Text(
            text = displayDescriptor,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall
        )
    }
}

// ==================== Seed Phrase View ====================

@Composable
private fun SeedPhraseView(
    wallet: Wallet,
    onHide: () -> Unit
) {
    val mnemonic = wallet.getActiveMnemonic() ?: emptyList()
    val is24Words = mnemonic.size == 24

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = "Keep your seed phrase secret and secure!",
            modifier = Modifier.padding(if (is24Words) 12.dp else 16.dp),
            style = if (is24Words) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
        )
    }

    Card(
        modifier = Modifier.fillMaxWidth()
    ) {
        // Display words top-to-bottom in two columns
        val wordsPerColumn = mnemonic.size / 2
        val column1 = mnemonic.take(wordsPerColumn)
        val column2 = mnemonic.drop(wordsPerColumn)
        
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(if (is24Words) 8.dp else 16.dp),
            horizontalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
        ) {
            // First column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
            ) {
                column1.forEachIndexed { index, word ->
                    val wordNumber = index + 1
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "$wordNumber. $word",
                            modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
            // Second column
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
            ) {
                column2.forEachIndexed { index, word ->
                    val wordNumber = wordsPerColumn + index + 1
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "$wordNumber. $word",
                            modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                    }
                }
            }
        }
    }

    Button(
        onClick = onHide,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Hide Seed Phrase")
    }
}
