package com.gorunjinian.metrovault.feature.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import kotlinx.coroutines.launch
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.SettingsItem
import com.gorunjinian.metrovault.core.ui.dialogs.PasswordGatedWarningDialog
import com.gorunjinian.metrovault.core.ui.dialogs.VerifyPasswordDialog
import com.gorunjinian.metrovault.core.ui.dialogs.WarningDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AdvancedSettingsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onViewSavedKeys: () -> Unit = {}
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()

    val autoOpenSingleWalletMain by userPreferencesRepository.autoOpenSingleWalletMain.collectAsState()
    val autoOpenSingleWalletDecoy by userPreferencesRepository.autoOpenSingleWalletDecoy.collectAsState()
    val autoExpandEnabled by userPreferencesRepository.autoExpandSingleWallet.collectAsState()
    val differentAccountsEnabled by userPreferencesRepository.differentAccountsEnabled.collectAsState()
    val bip85Enabled by userPreferencesRepository.bip85Enabled.collectAsState()
    val silentPaymentsEnabled by userPreferencesRepository.silentPaymentsEnabled.collectAsState()

    var showDeleteAllWalletsDialog by remember { mutableStateOf(false) }
    var showDisableAccountsWarningDialog by remember { mutableStateOf(false) }
    var showKeysPasswordDialog by remember { mutableStateOf(false) }

    // Check if any wallet has multiple accounts
    val walletsList by wallet.wallets.collectAsState()
    val hasMultiAccountWallets = remember(walletsList) {
        walletsList.any { it.accounts.size > 1 }
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Advanced",
                onBack = onBack
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

            // Silent Payments toggle
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
                        painter = painterResource(R.drawable.ic_person_shield),
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(24.dp)
                    )
                    Column {
                        Text(
                            text = "Silent Payments",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Enable SP for existing regular wallets",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                Switch(
                    checked = silentPaymentsEnabled,
                    onCheckedChange = { enabled ->
                        userPreferencesRepository.setSilentPaymentsEnabled(enabled)
                    }
                )
            }

            // View All Saved Keys
            SettingsItem(
                icon = R.drawable.ic_key,
                title = "View All Saved Keys",
                description = "Manage the keys stored in the vault",
                onClick = {
                    showKeysPasswordDialog = true
                }
            )

            // Delete All Wallets
            SettingsItem(
                icon = R.drawable.ic_delete,
                title = "Delete All Wallets",
                description = "Remove all wallets from the vault",
                onClick = { showDeleteAllWalletsDialog = true },
                iconTint = MaterialTheme.colorScheme.error
            )

            // Info Card after content
            SettingsInfoCard(
                icon = R.drawable.ic_tune,
                title = "Advanced Settings",
                description = "Advanced options for power users. Configure wallet behavior like auto-opening and auto-expanding, and manage wallet deletion."
            )
        }
    }

    // Delete All Wallets: warning, then password
    if (showDeleteAllWalletsDialog) {
        var passwordError by remember { mutableStateOf("") }
        var isDeleting by remember { mutableStateOf(false) }

        PasswordGatedWarningDialog(
            title = "Delete All Wallets?",
            confirmLabel = "Delete All",
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { if (!isDeleting) showDeleteAllWalletsDialog = false },
            onVerified = {
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
                        showDeleteAllWalletsDialog = false
                        android.widget.Toast.makeText(
                            context,
                            "All wallets deleted",
                            android.widget.Toast.LENGTH_SHORT
                        ).show()
                    } else {
                        passwordError = "Failed to delete all wallets"
                    }
                }
            },
            isLoading = isDeleting,
            errorMessage = passwordError
        ) {
            Text("This permanently removes every wallet in this vault from this device, along with their keys.")
            Text("You'll need your backups to access them again: each wallet's seed phrase (plus its passphrase, if it has one), and the descriptor of each multisig wallet.")
        }
    }

    // Warning dialog for disabling Different Accounts with multi-account wallets
    if (showDisableAccountsWarningDialog) {
        WarningDialog(
            title = "Hide Different Accounts?",
            confirmLabel = "Hide Anyway",
            onConfirm = {
                userPreferencesRepository.setDifferentAccountsEnabled(false)
                showDisableAccountsWarningDialog = false
            },
            onDismiss = { showDisableAccountsWarningDialog = false }
        ) {
            Text("One or more wallets have multiple account numbers. Hiding this option doesn't delete those accounts, but you won't be able to open or switch between them in the app.")
            Text("You can turn this setting back on at any time to regain access.")
        }
    }

    // Password confirmation dialog for View All Saved Keys
    if (showKeysPasswordDialog) {
        VerifyPasswordDialog(
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { showKeysPasswordDialog = false },
            onVerified = {
                showKeysPasswordDialog = false
                onViewSavedKeys()
            }
        )
    }
}
