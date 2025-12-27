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
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils

/**
 * AccountKeysScreen - Displays extended public and private keys with QR codes.
 * Supports public/private toggle with password confirmation for private keys.
 * Includes account selector to export keys for any account.
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountKeysScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    // For keys view: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }
    
    // QR code bitmap
    var currentQR by remember { mutableStateOf<Bitmap?>(null) }
    
    // Security: Clear sensitive data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            currentQR?.recycle()
            currentQR = null
            showPrivate = false
            System.gc() // Hint to garbage collector
        }
    }
    
    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }
    
    // Account selection state
    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    val activeWalletMetadata = remember(walletsList, walletId) {
        walletsList.find { it.id == walletId }
    }
    val accounts = activeWalletMetadata?.accounts?.sorted() ?: listOf(0)
    val currentActiveAccount = activeWalletMetadata?.activeAccountNumber ?: 0
    var selectedAccountNumber by remember { mutableIntStateOf(currentActiveAccount) }
    var accountDropdownExpanded by remember { mutableStateOf(false) }
    
    // Base derivation path for key generation
    val baseDerivationPath = activeWalletMetadata?.derivationPath ?: ""
    
    // Compute keys for selected account using Wallet's public methods
    val xpub = remember(selectedAccountNumber, baseDerivationPath) {
        if (baseDerivationPath.isNotEmpty()) {
            wallet.getXpubForAccount(baseDerivationPath, selectedAccountNumber)
        } else ""
    }
    val xpriv = remember(selectedAccountNumber, baseDerivationPath) {
        if (baseDerivationPath.isNotEmpty()) {
            wallet.getXprivForAccount(baseDerivationPath, selectedAccountNumber)
        } else ""
    }
    
    // Key prefixes for display
    val publicKeyPrefix = when {
        xpub.startsWith("zpub") -> "zpub"
        xpub.startsWith("ypub") -> "ypub"
        xpub.startsWith("vpub") -> "vpub"
        xpub.startsWith("upub") -> "upub"
        xpub.startsWith("tpub") -> "tpub"
        else -> "xpub"
    }
    val privateKeyPrefix = when {
        xpriv.startsWith("zprv") -> "zprv"
        xpriv.startsWith("yprv") -> "yprv"
        xpriv.startsWith("vprv") -> "vprv"
        xpriv.startsWith("uprv") -> "uprv"
        xpriv.startsWith("tprv") -> "tprv"
        else -> "xprv"
    }
    
    val displayKey = if (showPrivate) xpriv else xpub
    val keyType = if (showPrivate) "Private" else "Public"
    val prefix = if (showPrivate) privateKeyPrefix else publicKeyPrefix
    
    // Display name for selected account
    val selectedAccountName = activeWalletMetadata?.getAccountDisplayName(selectedAccountNumber)
        ?: "Account $selectedAccountNumber"
    
    // Generate QR code when display data changes
    LaunchedEffect(displayKey) {
        if (displayKey.isNotEmpty()) {
            currentQR = null // Show loading
            withContext(Dispatchers.IO) {
                currentQR = QRCodeUtils.generateQRCode(displayKey)
            }
        }
    }
    
    val context = LocalContext.current
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Keys") },
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
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))
            
            // Account Selector Dropdown
            ExposedDropdownMenuBox(
                expanded = accountDropdownExpanded,
                onExpandedChange = { accountDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedAccountName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Extended $keyType Key ($prefix)") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = accountDropdownExpanded,
                    onDismissRequest = { accountDropdownExpanded = false }
                ) {
                    accounts.forEach { accountNum ->
                        val accountName = activeWalletMetadata?.getAccountDisplayName(accountNum)
                            ?: "Account $accountNum"
                        val accountPath = DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = accountName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = accountPath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedAccountNumber = accountNum
                                accountDropdownExpanded = false
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        if (accountNum != accounts.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            // Public/Private Toggle
            PublicPrivateToggle(
                showPrivate = showPrivate,
                onSelectPublic = { showPrivate = false },
                onSelectPrivate = {
                    passwordError = ""
                    showPasswordDialog = true
                }
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
                if (currentQR != null) {
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
                            bitmap = currentQR!!.asImageBitmap(),
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
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
    
    // Password confirmation dialog
    if (showPasswordDialog) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
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
                    showPrivate = true
                    passwordError = ""
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }
}

// ==================== Public/Private Toggle ====================

@Composable
internal fun PublicPrivateToggle(
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
