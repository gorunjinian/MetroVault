package com.gorunjinian.metrovault.feature.wallet.details

import android.view.HapticFeedbackConstants
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RenameAccountDialog
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun DifferentAccountsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    val view = LocalView.current
    
    // Observe wallets StateFlow for reactive updates
    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    
    // Derive accounts and activeAccountNumber from observed state
    // Use derivedStateOf for proper reactivity across all devices
    val activeWalletMetadata by remember(walletsList, walletId) {
        derivedStateOf { walletsList.find { it.id == walletId } }
    }
    val accounts by remember(activeWalletMetadata) {
        derivedStateOf { activeWalletMetadata?.accounts ?: listOf(0) }
    }
    val currentAccount by remember(activeWalletMetadata) {
        derivedStateOf { activeWalletMetadata?.activeAccountNumber ?: 0 }
    }
    val baseDerivationPath by remember(activeWalletMetadata) {
        derivedStateOf { activeWalletMetadata?.derivationPath ?: "" }
    }
    
    // Add dialog state
    var showAddDialog by remember { mutableStateOf(false) }
    var newAccountNumber by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var isSwitching by remember { mutableStateOf(false) }
    
    // Delete dialog states
    var accountToDelete by remember { mutableStateOf<Int?>(null) }
    var showDeleteWarning by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }
    var isDeleting by remember { mutableStateOf(false) }
    
    // Edit mode state
    var isEditMode by remember { mutableStateOf(false) }
    
    // Rename dialog state
    var accountToRename by remember { mutableStateOf<Int?>(null) }
    
    // Handle back press to exit edit mode
    BackHandler(enabled = isEditMode) {
        isEditMode = false
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Different Accounts") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (isEditMode) {
                            isEditMode = false
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack,
                            "Back"
                        )
                    }
                },
                actions = {
                    IconButton(onClick = { isEditMode = !isEditMode }) {
                        Icon(
                            painter = painterResource(
                                if (isEditMode) R.drawable.ic_check else R.drawable.ic_edit
                            ),
                            contentDescription = if (isEditMode) "Done" else "Edit",
                            modifier = if (isEditMode) Modifier.size(28.dp) else Modifier
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
            )
        },
        floatingActionButton = {
            FloatingActionButton(
                onClick = { showAddDialog = true },
                shape = androidx.compose.foundation.shape.CircleShape
            ) {
                Icon(painter = painterResource(R.drawable.ic_add),
                    "Add Account"
                )
            }
        },
        floatingActionButtonPosition = FabPosition.Center
    ) { padding ->
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
                    text = "Manage account numbers under this wallet. All accounts share the same seed phrase.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = if (isEditMode) "Tap to rename or remove accounts." else "Long-press an inactive account to remove it.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            items(
                items = accounts.sorted(),
                key = { accountNum -> "account_$accountNum" }
            ) { accountNum ->
                val isActive = accountNum == currentAccount
                val path = DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                val canDelete = !isActive && accounts.size > 1
                val displayName = activeWalletMetadata?.getAccountDisplayName(accountNum) ?: "Account $accountNum"
                
                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .combinedClickable(
                            enabled = !isSwitching && !isDeleting,
                            onClick = {
                                if (isEditMode) {
                                    // In edit mode, tap opens rename dialog
                                    accountToRename = accountNum
                                } else if (!isActive && walletId != null) {
                                    // Normal mode: switch to this account
                                    isSwitching = true
                                    scope.launch {
                                        wallet.switchActiveAccount(walletId, accountNum)
                                        isSwitching = false
                                    }
                                }
                            },
                            onLongClick = {
                                // Long-press delete only in normal mode
                                if (!isEditMode && canDelete) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    accountToDelete = accountNum
                                    showDeleteWarning = true
                                }
                            }
                        ),
                    // In edit mode, all cards look the same (no active highlighting)
                    colors = if (isActive && !isEditMode) {
                        CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.outlinedCardColors()
                    },
                    border = if (isActive && !isEditMode) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    }
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
                                text = displayName,
                                style = MaterialTheme.typography.titleMedium,
                                // In edit mode, use primary color to hint it's editable
                                color = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            // Show "Currently active" only when not in edit mode
                            if (isActive && !isEditMode) {
                                Text(
                                    text = "Currently active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        // In edit mode: show trash icon for deletable accounts
                        // In normal mode: show check icon for active account
                        if (isEditMode && canDelete) {
                            IconButton(
                                onClick = {
                                    view.performHapticFeedback(HapticFeedbackConstants.CONTEXT_CLICK)
                                    accountToDelete = accountNum
                                    // In edit mode, skip warning and go directly to password dialog
                                    passwordError = ""
                                    showPasswordDialog = true
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_delete),
                                    contentDescription = "Delete",
                                    tint = MaterialTheme.colorScheme.error
                                )
                            }
                        } else if (isActive && !isEditMode) {
                            Icon(
                                painter = painterResource(R.drawable.ic_check),
                                contentDescription = "Active",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        }
                    }
                }
            }
        }
    }
    
    // Delete Warning Dialog
    if (showDeleteWarning && accountToDelete != null) {
        val deleteDisplayName = activeWalletMetadata?.getAccountDisplayName(accountToDelete!!) ?: "Account ${accountToDelete!!}"
        AlertDialog(
            onDismissRequest = { 
                showDeleteWarning = false
                accountToDelete = null
            },
            icon = { 
                Icon(
                    painter = painterResource(R.drawable.ic_warning), 
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                ) 
            },
            title = { Text("Remove $deleteDisplayName?") },
            text = {
                Text(
                    "This will remove \"$deleteDisplayName\" from this wallet.\n\n" +
                    "The account can be re-added later using the same account number. " +
                    "Any funds on this account will still be accessible by re-adding it."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteWarning = false
                        passwordError = ""
                        showPasswordDialog = true
                    },
                    colors = ButtonDefaults.textButtonColors(
                        contentColor = MaterialTheme.colorScheme.error
                    )
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeleteWarning = false
                    accountToDelete = null
                }) { Text("Cancel") }
            }
        )
    }
    
    // Password Confirmation Dialog
    if (showPasswordDialog && accountToDelete != null) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                accountToDelete = null
                passwordError = ""
            },
            onConfirm = { password ->
                if (secureStorage.verifyPasswordSimple(password) && 
                    !secureStorage.isDecoyPassword(password)) {
                    isDeleting = true
                    scope.launch {
                        val success = walletId?.let { 
                            wallet.removeAccountFromWallet(it, accountToDelete!!) 
                        } ?: false
                        isDeleting = false
                        if (success) {
                            showPasswordDialog = false
                            accountToDelete = null
                        } else {
                            passwordError = "Failed to delete account"
                        }
                    }
                } else {
                    passwordError = "Incorrect password"
                }
            },
            isLoading = isDeleting,
            errorMessage = passwordError
        )
    }
    
    // Add Account Dialog
    if (showAddDialog) {
        val focusRequester = remember { FocusRequester() }
        
        // Auto-focus the input field when dialog opens
        LaunchedEffect(Unit) {
            focusRequester.requestFocus()
        }
        
        AlertDialog(
            onDismissRequest = { 
                showAddDialog = false
                newAccountNumber = ""
                addError = ""
            },
            title = { Text("Add Account") },
            text = {
                Column {
                    Text("Enter a new account number (0-99):")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAccountNumber,
                        onValueChange = { 
                            newAccountNumber = it.filter { c -> c.isDigit() }.take(2)
                            addError = ""
                        },
                        label = { Text("Account Number") },
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                        singleLine = true,
                        isError = addError.isNotEmpty(),
                        modifier = Modifier.focusRequester(focusRequester)
                    )
                    if (addError.isNotEmpty()) {
                        Spacer(modifier = Modifier.height(4.dp))
                        Text(
                            text = addError, 
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        val num = newAccountNumber.toIntOrNull()
                        if (num == null) {
                            addError = "Enter a valid number"
                        } else if (num > 99) {
                            addError = "Account number must be 0-99"
                        } else if (accounts.contains(num)) {
                            addError = "Account $num already exists"
                        } else if (walletId != null) {
                            scope.launch {
                                val success = wallet.addAccountToWallet(walletId, num)
                                if (success) {
                                    showAddDialog = false
                                    newAccountNumber = ""
                                } else {
                                    addError = "Failed to add account"
                                }
                            }
                        }
                    }
                ) { Text("Add") }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showAddDialog = false
                    newAccountNumber = ""
                    addError = ""
                }) { Text("Cancel") }
            }
        )
    }
    
    // Rename Account Dialog
    if (accountToRename != null) {
        val currentName = activeWalletMetadata?.getAccountDisplayName(accountToRename!!) ?: "Account ${accountToRename!!}"
        RenameAccountDialog(
            currentName = currentName,
            onDismiss = { accountToRename = null },
            onConfirm = { newName ->
                if (walletId != null) {
                    scope.launch {
                        wallet.renameAccount(walletId, accountToRename!!, newName)
                        accountToRename = null
                    }
                }
            }
        )
    }
}

