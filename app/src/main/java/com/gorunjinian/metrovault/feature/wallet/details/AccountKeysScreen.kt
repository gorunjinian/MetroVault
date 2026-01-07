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
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle

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
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    // For keys view: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }
    
    // Export mode: false = single-sig key, true = multisig key (BIP48)
    var exportForMultisig by remember { mutableStateOf(false) }
    
    // BIP48 script type for multisig export
    var bip48ScriptType by remember { mutableStateOf(DerivationPaths.Bip48ScriptType.P2WSH) }
    
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
    
    // Compute keys based on export mode
    val displayKey = remember(selectedAccountNumber, baseDerivationPath, exportForMultisig, showPrivate, bip48ScriptType) {
        if (exportForMultisig) {
            // BIP48 multisig key export
            if (showPrivate) {
                wallet.getBip48XprivForAccount(selectedAccountNumber, bip48ScriptType)
            } else {
                wallet.getBip48XpubForAccount(selectedAccountNumber, bip48ScriptType)
            }
        } else {
            // Single-sig key export
            if (baseDerivationPath.isEmpty()) return@remember ""
            if (showPrivate) {
                wallet.getXprivForAccount(baseDerivationPath, selectedAccountNumber)
            } else {
                wallet.getXpubForAccount(baseDerivationPath, selectedAccountNumber)
            }
        }
    }
    
    // Key prefix for display label
    val keyPrefix = when {
        displayKey.startsWith("Zpub") || displayKey.startsWith("Zprv") -> if (showPrivate) "Zprv" else "Zpub"
        displayKey.startsWith("Vpub") || displayKey.startsWith("Vprv") -> if (showPrivate) "Vprv" else "Vpub"
        displayKey.startsWith("Ypub") || displayKey.startsWith("Yprv") -> if (showPrivate) "Yprv" else "Ypub"
        displayKey.startsWith("Upub") || displayKey.startsWith("Uprv") -> if (showPrivate) "Uprv" else "Upub"
        displayKey.startsWith("zpub") || displayKey.startsWith("zprv") -> if (showPrivate) "zprv" else "zpub"
        displayKey.startsWith("ypub") || displayKey.startsWith("yprv") -> if (showPrivate) "yprv" else "ypub"
        displayKey.startsWith("vpub") || displayKey.startsWith("vprv") -> if (showPrivate) "vprv" else "vpub"
        displayKey.startsWith("upub") || displayKey.startsWith("uprv") -> if (showPrivate) "uprv" else "upub"
        displayKey.startsWith("tpub") || displayKey.startsWith("tprv") -> if (showPrivate) "tprv" else "tpub"
        else -> if (showPrivate) "xprv" else "xpub"
    }
    
    val keyType = if (showPrivate) "Private" else "Public"
    val exportLabel = if (exportForMultisig) "Multisig $keyType Key" else "Extended $keyType Key"
    
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
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Account Keys") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Single-sig / Multisig Toggle in App Bar
                    SegmentedToggle(
                        firstOption = "Single-sig",
                        secondOption = "Multisig",
                        isSecondSelected = exportForMultisig,
                        onSelectFirst = { exportForMultisig = false },
                        onSelectSecond = { exportForMultisig = true },
                        modifier = Modifier.padding(end = 8.dp),
                        compact = true
                    )
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
                    label = { Text("$exportLabel ($keyPrefix)") },
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
                        val accountPath = if (exportForMultisig) {
                            DerivationPaths.bip48(accountNum, bip48ScriptType, wallet.isActiveWalletTestnet())
                        } else {
                            DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                        }
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

            // BIP48 Script Type Selector (only shown in multisig mode)
            if (exportForMultisig) {
                SegmentedToggle(
                    options = listOf(
                        DerivationPaths.Bip48ScriptType.P2WSH.displayName,
                        DerivationPaths.Bip48ScriptType.P2SH_P2WSH.displayName
                    ),
                    selectedIndex = if (bip48ScriptType == DerivationPaths.Bip48ScriptType.P2WSH) 0 else 1,
                    onSelect = { index ->
                        bip48ScriptType = if (index == 0) {
                            DerivationPaths.Bip48ScriptType.P2WSH
                        } else {
                            DerivationPaths.Bip48ScriptType.P2SH_P2WSH
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                )
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
                        text = if (exportForMultisig) {
                            "This key can sign multisig transactions.\nNever share it!"
                        } else {
                            "This key can spend all funds in this account.\nNever share it!"
                        },
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            } else if (exportForMultisig) {
                // Info card for multisig public key
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Import this key into a multisig coordinator.",
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
                if (currentQR != null) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (tapToCopyEnabled) {
                            Modifier.clickable {
                                        SecurityUtils.copyToClipboardWithAutoClear(
                                            context = context,
                                            label = exportLabel,
                                            text = displayKey,
                                            delayMs = 20_000
                                        )
                                        Toast.makeText(context, "Copied! Clipboard will clear in 20 seconds", Toast.LENGTH_SHORT).show()
                                    }
                                } else Modifier
                            )
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

            if (tapToCopyEnabled) {
                Text(
                    text = "Tap QR code to copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

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
