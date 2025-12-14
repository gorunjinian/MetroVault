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
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.components.SettingsInfoCard
import com.gorunjinian.metrovault.core.ui.components.SettingsItem
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

    var showDeleteAllWalletsDialog by remember { mutableStateOf(false) }

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

    // Delete All Wallets confirmation dialog
    if (showDeleteAllWalletsDialog) {
        var password by remember { mutableStateOf("") }
        var passwordError by remember { mutableStateOf("") }
        var isDeleting by remember { mutableStateOf(false) }

        AlertDialog(
            onDismissRequest = {
                if (!isDeleting) {
                    showDeleteAllWalletsDialog = false
                    passwordError = ""
                }
            },
            title = { Text("Delete All Wallets") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(
                        "⚠️ This will permanently delete all wallets from this vault. This action cannot be undone.",
                        color = MaterialTheme.colorScheme.error
                    )
                    Text("Enter your password to confirm:")
                    SecureOutlinedTextField(
                        value = password,
                        onValueChange = {
                            password = it
                            passwordError = ""
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        isPasswordField = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = passwordError.isNotEmpty(),
                        enabled = !isDeleting,
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError.isNotEmpty()) {
                        Text(
                            text = passwordError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
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
                        } else {
                            passwordError = "Incorrect password"
                        }
                    },
                    enabled = password.isNotEmpty() && !isDeleting
                ) {
                    if (isDeleting) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(16.dp),
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Delete All", color = MaterialTheme.colorScheme.error)
                    }
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showDeleteAllWalletsDialog = false
                        passwordError = ""
                    },
                    enabled = !isDeleting
                ) {
                    Text("Cancel")
                }
            }
        )
    }
}
