package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.SettingsItem
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoOpenSingleWalletMain by userPreferencesRepository.autoOpenSingleWalletMain.collectAsState()
    val autoOpenSingleWalletDecoy by userPreferencesRepository.autoOpenSingleWalletDecoy.collectAsState()
    val autoExpandEnabled by userPreferencesRepository.autoExpandSingleWallet.collectAsState()
    val differentAccountsEnabled by userPreferencesRepository.differentAccountsEnabled.collectAsState()
    val bip85Enabled by userPreferencesRepository.bip85Enabled.collectAsState()

    var showDeleteAllWalletsDialog by remember { mutableStateOf(false) }
    var showDeletePasswordDialog by remember { mutableStateOf(false) }
    var showDisableAccountsWarningDialog by remember { mutableStateOf(false) }

    // Check if any wallet has multiple accounts
    val walletsList by wallet.wallets.collectAsState()
    val hasMultiAccountWallets = remember(walletsList) {
        walletsList.any { it.accounts.size > 1 }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Advanced") },
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
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Auto-open single wallet
            val autoOpenEnabled = if (wallet.isDecoyMode) autoOpenSingleWalletDecoy else autoOpenSingleWalletMain

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_wallet),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Auto-open Single Wallet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Open wallet automatically if only 1 exists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = autoOpenEnabled,
                    onCheckedChange = { enabled ->
                        if (wallet.isDecoyMode) {
                            userPreferencesRepository.setAutoOpenSingleWalletDecoy(enabled)
                        } else {
                            userPreferencesRepository.setAutoOpenSingleWalletMain(enabled)
                        }
                    }
                )
            }

            // Auto-expand single wallet card
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_expand),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Auto-expand Single Wallet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Expand wallet card if only 1 exists",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = autoExpandEnabled,
                    onCheckedChange = { enabled ->
                        userPreferencesRepository.setAutoExpandSingleWallet(enabled)
                    }
                )
            }

            // BIP-85 Derivation toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_account_tree),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "BIP-85 Derivation",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Enable child seed phrase derivation",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = bip85Enabled,
                    onCheckedChange = { enabled ->
                        userPreferencesRepository.setBip85Enabled(enabled)
                    }
                )
            }

            // Different Accounts toggle
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(vertical = 8.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_accounts),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Different Accounts",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Enable BIP44 account number management",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = differentAccountsEnabled,
                    onCheckedChange = { enabled ->
                        if (!enabled && hasMultiAccountWallets) {
                            showDisableAccountsWarningDialog = true
                        } else {
                            userPreferencesRepository.setDifferentAccountsEnabled(enabled)
                        }
                    }
                )
            }

            // Delete All Wallets
            SettingsItem(
                icon = R.drawable.ic_delete,
                title = "Delete All Wallets",
                description = "Remove all wallets from the vault",
                onClick = { showDeleteAllWalletsDialog = true },
                iconTint = MaterialTheme.colorScheme.error
            )

            Spacer(modifier = Modifier.height(24.dp))

            // Info Card after content
            SettingsInfoCard(
                icon = R.drawable.ic_tune,
                title = "Advanced Settings",
                description = "Advanced options for power users. Configure wallet behavior like auto-opening and auto-expanding, and manage wallet deletion."
            )
        }
    }

    // Delete All Wallets warning dialog (Step 1)
    if (showDeleteAllWalletsDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteAllWalletsDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Delete All Wallets") },
            text = {
                Text(
                    "This will permanently delete all wallets from this vault.\n\n" +
                    "This action cannot be undone unless you have your seed phrases backed up."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteAllWalletsDialog = false
                        showDeletePasswordDialog = true
                    }
                ) {
                    Text("I Understand", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteAllWalletsDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // Delete All Wallets password confirmation dialog (Step 2)
    if (showDeletePasswordDialog) {
        var passwordError by remember { mutableStateOf("") }
        var isDeleting by remember { mutableStateOf(false) }

        ConfirmPasswordDialog(
            onDismiss = {
                if (!isDeleting) {
                    showDeletePasswordDialog = false
                    passwordError = ""
                }
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
                        val walletList = wallet.wallets.value
                        var allDeleted = true
                        for (w in walletList) {
                            val deleted = wallet.deleteWallet(w.id)
                            if (!deleted) {
                                allDeleted = false
                                break
                            }
                        }
                        isDeleting = false
                        if (allDeleted) {
                            showDeletePasswordDialog = false
                            android.widget.Toast.makeText(
                                context,
                                "All wallets deleted",
                                android.widget.Toast.LENGTH_SHORT
                            ).show()
                        } else {
                            passwordError = "Failed to delete all wallets"
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

    // Warning dialog for disabling Different Accounts with multi-account wallets
    if (showDisableAccountsWarningDialog) {
        AlertDialog(
            onDismissRequest = { showDisableAccountsWarningDialog = false },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            },
            title = { Text("Hide Different Accounts?") },
            text = {
                Text(
                    "One or more wallets have multiple account numbers. " +
                    "Hiding this option will not delete the accounts, but you won't " +
                    "be able to access or switch between them from within the app.\n\n" +
                    "You can re-enable this setting at any time to regain access."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    userPreferencesRepository.setDifferentAccountsEnabled(false)
                    showDisableAccountsWarningDialog = false
                }) {
                    Text("Hide Anyway", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = { showDisableAccountsWarningDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }
}
