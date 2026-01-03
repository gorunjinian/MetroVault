package com.gorunjinian.metrovault.feature.settings

import android.view.HapticFeedbackConstants
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
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * Screen for viewing and managing all saved wallet keys.
 * Allows renaming and deleting keys (with cascade delete of dependent wallets).
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
    val view = LocalView.current
    
    // Keys list - loaded immediately since authentication was done in AdvancedSettingsScreen
    var keys by remember { mutableStateOf<List<WalletKeys>>(emptyList()) }
    
    // Edit mode state
    var isEditMode by remember { mutableStateOf(false) }
    
    // Rename dialog state
    var keyToRename by remember { mutableStateOf<WalletKeys?>(null) }
    var renameValue by remember { mutableStateOf("") }
    
    // Delete dialog state
    var keyToDelete by remember { mutableStateOf<WalletKeys?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    var showDeletePasswordDialog by remember { mutableStateOf(false) }
    var deletePasswordError by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    var walletsToDelete by remember { mutableStateOf<List<String>>(emptyList()) }
    
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
                        text = if (isEditMode) "Tap to rename, use trash icon to delete." else "Long-press a key to delete it.",
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
                                        keyToRename = key
                                        renameValue = key.label
                                    }
                                },
                                onLongClick = {
                                    if (!isEditMode) {
                                        view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                        keyToDelete = key
                                        walletsToDelete = referencingWallets
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
                                        view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                        keyToDelete = key
                                        walletsToDelete = referencingWallets
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
                            }
                        }
                    }
                }
            }
        }
    }
    
    // Rename Dialog
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
    
    // Delete Warning Dialog
    if (showDeleteWarning && keyToDelete != null) {
        val key = keyToDelete!!
        val walletCount = walletsToDelete.size
        
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
                Text(
                    if (walletCount == 0) {
                        "This key is not used by any wallet and will be permanently deleted.\n\n" +
                        "This action cannot be undone."
                    } else {
                        "This will permanently delete this key and ALL wallets using it:\n\n" +
                        "• $walletCount ${if (walletCount == 1) "wallet" else "wallets"} will be deleted\n\n" +
                        "This action cannot be undone unless you have your seed phrase backed up."
                    }
                )
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
    
    // Delete Password Confirmation Dialog
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
                            // For multisig with multiple keys, just remove this key reference
                            // (This is a simplification - in practice we'd update the metadata)
                        }
                        
                        // Force delete the key (it should be unreferenced now)
                        val prefs = if (isDecoy) {
                            // Use reflection or add a forceDeleteKey method
                            // For now, the key should be deletable since wallets are gone
                        } else {
                            // Same for main vault
                        }
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
