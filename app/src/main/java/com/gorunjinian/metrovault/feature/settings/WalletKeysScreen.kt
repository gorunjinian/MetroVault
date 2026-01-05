package com.gorunjinian.metrovault.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * Screen for viewing and managing all saved wallet keys.
 * Allows renaming, viewing details (with password confirmation), and deleting keys.
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WalletKeysScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // Keys list - loaded immediately since authentication was done in AdvancedSettingsScreen
    var keys by remember { mutableStateOf<List<WalletKeys>>(emptyList()) }
    
    // Edit mode state
    var isEditMode by remember { mutableStateOf(false) }
    
    // Rename dialog state
    var keyToRename by remember { mutableStateOf<WalletKeys?>(null) }
    var renameValue by remember { mutableStateOf("") }
    
    // Key details dialog state
    var keyToView by remember { mutableStateOf<WalletKeys?>(null) }
    var showKeyDetailsDialog by remember { mutableStateOf(false) }
    var showSeedPasswordDialog by remember { mutableStateOf(false) }
    var seedPasswordError by remember { mutableStateOf("") }
    var isMnemonicVisible by remember { mutableStateOf(false) }
    
    // Delete dialog state
    var keyToDelete by remember { mutableStateOf<WalletKeys?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    var showDeletePasswordDialog by remember { mutableStateOf(false) }
    var deletePasswordError by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var walletsToDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    var multisigWalletsUsingKey by remember { mutableStateOf<List<String>>(emptyList()) }
    
    // Load keys on composition
    fun loadKeys() {
        keys = secureStorage.loadAllWalletKeys(wallet.isDecoyMode)
    }
    
    // Initial load
    LaunchedEffect(Unit) {
        loadKeys()
    }
    
    // Handle back press to exit edit mode
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Saved Keys") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditMode) {
                            isEditMode = false
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                actions = {
                    if (keys.isNotEmpty()) {
                        IconButton(onClick = { isEditMode = !isEditMode }) {
                            Icon(
                                painter = painterResource(
                                    if (isEditMode) R.drawable.ic_check else R.drawable.ic_edit
                                ),
                                contentDescription = if (isEditMode) "Done" else "Edit",
                                modifier = if (isEditMode) Modifier.size(28.dp) else Modifier
                            )
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        }
    ) { padding ->
        if (keys.isEmpty()) {
            // Empty state
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_key),
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                    )
                    Text(
                        text = "No saved keys",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "Create a wallet to add a key",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                }
            }
        } else {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
                contentPadding = PaddingValues(vertical = 16.dp)
            ) {
                item {
                    Text(
                        text = "All seed phrases saved in this vault. Each key can be used by multiple wallets.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = if (isEditMode) "Tap to rename, use trash icon to delete." 
                               else "Tap to view details, long-press to delete.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                }
                
                items(keys) { key ->
                    val referencingWallets = secureStorage.getWalletsReferencingKey(key.keyId, wallet.isDecoyMode)
                    val walletCount = referencingWallets.size
                    
                    OutlinedCard(
                        modifier = Modifier
                            .fillMaxWidth()
                            .combinedClickable(
                                enabled = !isDeleting,
                                onClick = {
                                    if (isEditMode) {
                                        // In edit mode: tap to rename
                                        keyToRename = key
                                        renameValue = key.label
                                    } else {
                                        // Normal mode: tap to view details (no password needed here)
                                        keyToView = key
                                        isMnemonicVisible = false
                                        showKeyDetailsDialog = true
                                    }
                                },
                                onLongClick = {
                                    if (!isEditMode) {
                                        keyToDelete = key
                                        walletsToDelete = referencingWallets
                                        // Get multisig wallets that reference this key by fingerprint
                                        multisigWalletsUsingKey = secureStorage.getMultisigWalletsUsingFingerprint(
                                            key.fingerprint, wallet.isDecoyMode
                                        )
                                        showDeleteWarning = true
                                    }
                                }
                            ),
                        colors = CardDefaults.outlinedCardColors(),
                        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    ) {
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(16.dp),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Column(modifier = Modifier.weight(1f)) {
                                Text(
                                    text = key.label.ifEmpty { "Unnamed Key" },
                                    style = MaterialTheme.typography.titleMedium,
                                    color = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                                )
                                Text(
                                    text = "Fingerprint: ${key.fingerprint.uppercase()}",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                                Text(
                                    text = when (walletCount) {
                                        0 -> "Not used by any wallet"
                                        1 -> "Used by 1 wallet"
                                        else -> "Used by $walletCount wallets"
                                    },
                                    style = MaterialTheme.typography.labelSmall,
                                    color = if (walletCount == 0) MaterialTheme.colorScheme.error.copy(alpha = 0.7f)
                                            else MaterialTheme.colorScheme.primary
                                )
                            }
                            if (isEditMode) {
                                IconButton(
                                    onClick = {
                                        keyToDelete = key
                                        walletsToDelete = referencingWallets
                                        multisigWalletsUsingKey = secureStorage.getMultisigWalletsUsingFingerprint(
                                            key.fingerprint, wallet.isDecoyMode
                                        )
                                        deletePasswordError = ""
                                        showDeletePasswordDialog = true
                                    }
                                ) {
                                    Icon(
                                        painter = painterResource(R.drawable.ic_delete),
                                        contentDescription = "Delete",
                                        tint = MaterialTheme.colorScheme.error
                                    )
                                }
                            } else {
                                Icon(
                                    painter = painterResource(R.drawable.ic_chevron_right),
                                    contentDescription = "View details",
                                    tint = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
    
    // ========================================
    // Rename Dialog
    // ========================================
    if (keyToRename != null) {
        AlertDialog(
            onDismissRequest = { keyToRename = null },
            title = { Text("Rename Key") },
            text = {
                OutlinedTextField(
                    value = renameValue,
                    onValueChange = { if (it.length <= 25) renameValue = it },
                    label = { Text("Key Label") },
                    singleLine = true,
                    supportingText = { Text("${renameValue.length}/25") }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val key = keyToRename!!
                        val updatedKey = key.copy(label = renameValue.trim())
                        scope.launch {
                            secureStorage.saveWalletKey(updatedKey, wallet.isDecoyMode)
                            loadKeys()
                            keyToRename = null
                        }
                    },
                    enabled = renameValue.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { keyToRename = null }) { Text("Cancel") }
            }
        )
    }
    
    // ========================================
    // Seed Phrase Password Dialog (triggered by Show button)
    // ========================================
    if (showSeedPasswordDialog && keyToView != null) {
        ConfirmPasswordDialog(
            onDismiss = {
                showSeedPasswordDialog = false
                seedPasswordError = ""
            },
            onConfirm = { password ->
                val isDecoy = wallet.isDecoyMode
                val isValidPassword = if (isDecoy) {
                    secureStorage.isDecoyPassword(password)
                } else {
                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                }
                
                if (isValidPassword) {
                    showSeedPasswordDialog = false
                    seedPasswordError = ""
                    isMnemonicVisible = true
                } else {
                    seedPasswordError = "Incorrect password"
                }
            },
            errorMessage = seedPasswordError
        )
    }
    
    // ========================================
    // Key Details Dialog
    // ========================================
    if (showKeyDetailsDialog && keyToView != null) {
        val key = keyToView!!
        val referencingWalletIds = secureStorage.getWalletsReferencingKey(key.keyId, wallet.isDecoyMode)
        val referencingWalletNames = referencingWalletIds.mapNotNull { walletId ->
            secureStorage.loadWalletMetadata(walletId, wallet.isDecoyMode)?.name
        }
        val mnemonicWords = key.mnemonic.split(" ")
        val wordCount = mnemonicWords.size
        val is24Words = wordCount == 24
        
        AlertDialog(
            onDismissRequest = { 
                showKeyDetailsDialog = false
                keyToView = null
                isMnemonicVisible = false
            },
            title = { 
                Text(key.label.ifEmpty { "Key Details" }) 
            },
            text = {
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    // Fingerprint
                    Column {
                        Text(
                            text = "Master Fingerprint",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = key.fingerprint.uppercase(),
                            style = MaterialTheme.typography.bodyLarge.copy(
                                fontFamily = FontFamily.Monospace
                            )
                        )
                    }
                    
                    HorizontalDivider()
                    
                    // Seed Phrase
                    Column {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Seed Phrase ($wordCount words)",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            TextButton(
                                onClick = { 
                                    if (isMnemonicVisible) {
                                        // Hide directly
                                        isMnemonicVisible = false
                                    } else {
                                        // Show requires password confirmation
                                        seedPasswordError = ""
                                        showSeedPasswordDialog = true
                                    }
                                }
                            ) {
                                Text(if (isMnemonicVisible) "Hide" else "Show")
                            }
                        }
                        
                        if (isMnemonicVisible) {
                            // Warning message
                            Text(
                                text = "Keep this secret!",
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(8.dp))
                            
                            // Two-column numbered seed display (matching SeedPhraseScreen)
                            val wordsPerColumn = wordCount / 2
                            val column1 = mnemonicWords.take(wordsPerColumn)
                            val column2 = mnemonicWords.drop(wordsPerColumn)
                            
                            Card(
                                modifier = Modifier.fillMaxWidth(),
                                colors = CardDefaults.cardColors(
                                    containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                                )
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(if (is24Words) 6.dp else 8.dp),
                                    horizontalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 6.dp)
                                ) {
                                    // First column
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(if (is24Words) 2.dp else 4.dp)
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
                                                    modifier = Modifier.padding(
                                                        horizontal = if (is24Words) 6.dp else 8.dp,
                                                        vertical = if (is24Words) 4.dp else 6.dp
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                    // Second column
                                    Column(
                                        modifier = Modifier.weight(1f),
                                        verticalArrangement = Arrangement.spacedBy(if (is24Words) 2.dp else 4.dp)
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
                                                    modifier = Modifier.padding(
                                                        horizontal = if (is24Words) 6.dp else 8.dp,
                                                        vertical = if (is24Words) 4.dp else 6.dp
                                                    ),
                                                    style = MaterialTheme.typography.bodySmall
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        } else {
                            Text(
                                text = "••••••••••••••••••••••••",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    HorizontalDivider()
                    
                    // Wallets using this key
                    Column {
                        Text(
                            text = "Used by ${referencingWalletNames.size} wallet(s)",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        if (referencingWalletNames.isNotEmpty()) {
                            Spacer(modifier = Modifier.height(4.dp))
                            referencingWalletNames.forEach { walletName ->
                                Text(
                                    text = "• $walletName",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurface
                                )
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(onClick = { 
                    showKeyDetailsDialog = false
                    keyToView = null
                    isMnemonicVisible = false
                }) { 
                    Text("Close") 
                }
            }
        )
    }
    
    // ========================================
    // Delete Warning Dialog
    // ========================================
    if (showDeleteWarning && keyToDelete != null) {
        val key = keyToDelete!!
        val directWalletCount = walletsToDelete.size
        val multisigCount = multisigWalletsUsingKey.size
        
        AlertDialog(
            onDismissRequest = { 
                showDeleteWarning = false
                keyToDelete = null
            },
            icon = { 
                Icon(
                    painter = painterResource(R.drawable.ic_warning), 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                ) 
            },
            title = { Text("Delete ${key.label.ifEmpty { "Key" }}?") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (directWalletCount == 0 && multisigCount == 0) {
                        Text(
                            "This key is not used by any wallet and will be permanently deleted.\n\n" +
                            "This action cannot be undone."
                        )
                    } else {
                        Text(
                            "This will permanently delete this key.",
                            style = MaterialTheme.typography.bodyMedium
                        )
                        
                        if (directWalletCount > 0) {
                            Text(
                                "• $directWalletCount ${if (directWalletCount == 1) "wallet" else "wallets"} will be deleted",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                        }
                        
                        if (multisigCount > 0) {
                            Text(
                                "This key is also used by $multisigCount multisig ${if (multisigCount == 1) "wallet" else "wallets"}:",
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            multisigWalletsUsingKey.forEach { name ->
                                Text(
                                    "   • $name",
                                    style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.error
                                )
                            }
                            Text(
                                "These multisig wallets will lose the ability to sign with this key.",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            "This action cannot be undone unless you have your seed phrase backed up.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteWarning = false
                        deletePasswordError = ""
                        showDeletePasswordDialog = true
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteWarning = false
                    keyToDelete = null
                }) { Text("Cancel") }
            }
        )
    }
    
    // ========================================
    // Delete Password Confirmation Dialog
    // ========================================
    if (showDeletePasswordDialog && keyToDelete != null) {
        ConfirmPasswordDialog(
            onDismiss = {
                showDeletePasswordDialog = false
                keyToDelete = null
                deletePasswordError = ""
            },
            onConfirm = { password ->
                val isDecoy = wallet.isDecoyMode
                val isValidPassword = if (isDecoy) {
                    secureStorage.isDecoyPassword(password)
                } else {
                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                }
                
                if (isValidPassword) {
                    isDeleting = true
                    scope.launch {
                        val key = keyToDelete!!
                        
                        // First, delete all wallets that reference this key
                        val walletsList = secureStorage.loadAllWalletMetadata(isDecoy)
                        for (w in walletsList) {
                            // Delete single-sig wallets that use this key
                            if (!w.isMultisig && w.keyIds.contains(key.keyId)) {
                                wallet.deleteWallet(w.id)
                            }
                            // Delete multisig wallets that ONLY have this key as local signer
                            if (w.isMultisig && w.keyIds.size == 1 && w.keyIds.contains(key.keyId)) {
                                wallet.deleteWallet(w.id)
                            }
                            // For multisig with multiple local keys, remove this key reference
                            if (w.isMultisig && w.keyIds.size > 1 && w.keyIds.contains(key.keyId)) {
                                val updatedKeyIds = w.keyIds.filter { it != key.keyId }
                                // Update cosigner isLocal flags
                                val updatedCosigners = w.multisigConfig?.cosigners?.map { cosigner ->
                                    if (cosigner.keyId == key.keyId) {
                                        cosigner.copy(isLocal = false, keyId = null)
                                    } else {
                                        cosigner
                                    }
                                }
                                val updatedConfig = w.multisigConfig?.copy(
                                    cosigners = updatedCosigners ?: emptyList(),
                                    localKeyFingerprints = w.multisigConfig.localKeyFingerprints.filter { 
                                        !it.equals(key.fingerprint, ignoreCase = true) 
                                    }
                                )
                                val updatedMetadata = w.copy(
                                    keyIds = updatedKeyIds,
                                    multisigConfig = updatedConfig
                                )
                                secureStorage.updateWalletMetadata(updatedMetadata, isDecoy)
                            }
                        }
                        
                        // Now delete the key (should be unreferenced or force delete)
                        secureStorage.deleteWalletKey(key.keyId, isDecoy)
                        
                        // Refresh wallet list
                        wallet.refreshWallets()
                        
                        isDeleting = false
                        showDeletePasswordDialog = false
                        keyToDelete = null
                        loadKeys()
                    }
                } else {
                    deletePasswordError = "Incorrect password"
                }
            },
            isLoading = isDeleting,
            errorMessage = deletePasswordError
        )
    }
}
