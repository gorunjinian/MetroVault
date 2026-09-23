package com.gorunjinian.metrovault.feature.settings

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.dialogs.VerifyPasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.PasswordGatedWarningDialog
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * Screen for viewing and managing all saved wallet keys.
 * Allows renaming, viewing details (with password confirmation), and deleting keys.
 */
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
    var isMnemonicVisible by remember { mutableStateOf(false) }
    
    // Delete dialog state
    var keyToDelete by remember { mutableStateOf<WalletKeys?>(null) }
    var isDeleting by remember { mutableStateOf(false) }
    var deletionImpact by remember { mutableStateOf(KeyDeletionImpact()) }

    // Load keys on composition
    fun loadKeys() {
        keys = secureStorage.loadAllWalletKeys(wallet.isDecoyMode)
    }

    // Every delete starts with the warning, which lists the wallets the deletion affects
    fun requestDeleteKey(key: WalletKeys) {
        deletionImpact = keyDeletionImpact(key.keyId, secureStorage.loadAllWalletMetadata(wallet.isDecoyMode))
        keyToDelete = key
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
            MetroTopBar(
                title = "Saved Keys",
                onBack = {
                        if (isEditMode) {
                            isEditMode = false
                        } else {
                            onBack()
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
                }
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
                                        requestDeleteKey(key)
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
                                        requestDeleteKey(key)
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
        VerifyPasswordDialog(
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { showSeedPasswordDialog = false },
            onVerified = {
                showSeedPasswordDialog = false
                isMnemonicVisible = true
            }
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
    // Delete Key: warning, then password
    // ========================================
    keyToDelete?.let { key ->
        PasswordGatedWarningDialog(
            title = if (key.label.isNotEmpty()) "Delete \"${key.label}\"?" else "Delete This Key?",
            confirmLabel = "Delete",
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { keyToDelete = null },
            onVerified = {
                val isDecoy = wallet.isDecoyMode
                isDeleting = true
                scope.launch {
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
                    keyToDelete = null
                    loadKeys()
                }
            },
            isLoading = isDeleting
        ) {
            if (deletionImpact.deletedWallets.isEmpty() && deletionImpact.multisigsLosingKey.isEmpty()) {
                Text("No wallet uses this key. It will be permanently removed from this device.")
            } else {
                Text("This permanently removes the key from this device.")
                KeyDeletionWalletList(
                    heading = "These wallets depend on it and will also be deleted:",
                    walletNames = deletionImpact.deletedWallets
                )
                KeyDeletionWalletList(
                    heading = "These multisig wallets stay, but can no longer sign with this key:",
                    walletNames = deletionImpact.multisigsLosingKey
                )
            }
            Text(
                "You can only restore it from its seed phrase.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}

/**
 * What deleting a key does to the vault's wallets, for the delete warning. Mirrors the
 * deletion in the password dialog's `onVerified`: a wallet goes with the key when the key is
 * its only local signer, and a multisig wallet with other local signers just loses this one.
 */
private class KeyDeletionImpact(
    val deletedWallets: List<String> = emptyList(),
    val multisigsLosingKey: List<String> = emptyList()
)

private fun keyDeletionImpact(keyId: String, wallets: List<WalletMetadata>): KeyDeletionImpact {
    val (losingKey, deleted) = wallets
        .filter { keyId in it.keyIds }
        .partition { it.isMultisig && it.keyIds.size > 1 }
    return KeyDeletionImpact(
        deletedWallets = deleted.map { it.name },
        multisigsLosingKey = losingKey.map { it.name }
    )
}

@Composable
private fun KeyDeletionWalletList(heading: String, walletNames: List<String>) {
    if (walletNames.isEmpty()) return
    Text(heading, color = MaterialTheme.colorScheme.error)
    walletNames.forEach { name ->
        Text(
            "   • $name",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.error
        )
    }
}
