package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DifferentAccountsScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    val scope = rememberCoroutineScope()
    
    // Observe wallets StateFlow for reactive updates
    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    
    // Derive accounts and activeAccountNumber from observed state
    val activeWalletMetadata = remember(walletsList, walletId) {
        walletsList.find { it.id == walletId }
    }
    val accounts = activeWalletMetadata?.accounts ?: listOf(0)
    val currentAccount = activeWalletMetadata?.activeAccountNumber ?: 0
    val baseDerivationPath = activeWalletMetadata?.derivationPath ?: ""
    
    var showAddDialog by remember { mutableStateOf(false) }
    var newAccountNumber by remember { mutableStateOf("") }
    var addError by remember { mutableStateOf("") }
    var isSwitching by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Different Accounts") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
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
                Icon(Icons.Default.Add, "Add Account")
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
                Spacer(modifier = Modifier.height(8.dp))
            }
            
            items(accounts.sorted()) { accountNum ->
                val isActive = accountNum == currentAccount
                val path = DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                
                OutlinedCard(
                    onClick = {
                        if (!isActive && walletId != null && !isSwitching) {
                            isSwitching = true
                            scope.launch {
                                wallet.switchActiveAccount(walletId, accountNum)
                                isSwitching = false
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isActive && !isSwitching,
                    colors = if (isActive) {
                        CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.outlinedCardColors()
                    },
                    border = if (isActive) {
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
                                text = "Account $accountNum",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isActive) {
                                Text(
                                    text = "Currently active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (isActive) {
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
                    Text("Enter a new account number to add:")
                    Spacer(modifier = Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newAccountNumber,
                        onValueChange = { 
                            newAccountNumber = it.filter { c -> c.isDigit() }.take(4)
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
}
