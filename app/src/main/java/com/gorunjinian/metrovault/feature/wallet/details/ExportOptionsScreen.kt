package com.gorunjinian.metrovault.feature.wallet.details

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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog

/**
 * Enum to track which sensitive view flow is active.
 * Used to determine where to navigate after password confirmation.
 */
private enum class SensitiveViewTarget {
    SEED_PHRASE,
    ROOT_KEY
}

/**
 * ExportOptionsScreen - Navigation hub with 4 export options:
 * 1. View Account Keys - Navigates to AccountKeysScreen
 * 2. View Descriptors - Navigates to DescriptorsScreen
 * 3. View BIP32 Root Key - Requires password confirmation, then navigates to RootKeyScreen
 * 4. View Seed Phrase - Requires password confirmation, then navigates to SeedPhraseScreen
 */
@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onBack: () -> Unit,
    onViewAccountKeys: () -> Unit,
    onViewDescriptors: () -> Unit,
    onViewRootKey: () -> Unit,
    onViewSeedPhrase: () -> Unit
) {
    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }
    
    // Track which sensitive view is being accessed
    var pendingTarget by remember { mutableStateOf<SensitiveViewTarget?>(null) }
    
    // Warning dialog for seed phrase and BIP32 key
    var showSeedWarningDialog by remember { mutableStateOf(false) }
    var showRootKeyWarningDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Export") },
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
            
            Text(
                text = "Export Options",
                style = MaterialTheme.typography.headlineSmall
            )

            // Card 1: View Account Keys
            ElevatedCard(
                onClick = onViewAccountKeys,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_key),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "View Account Extended Keys",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Extended public & private keys",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Card 2: View Descriptors
            ElevatedCard(
                onClick = onViewDescriptors,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_desciption),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "View Output Descriptors",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Public & spending descriptors",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Card 3: View Root Key
            ElevatedCard(
                onClick = { showRootKeyWarningDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_root),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            text = "View BIP32 Root Key",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Show your wallet's main BIP32 root key",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }

            // Card 4: View Seed Phrase
            ElevatedCard(
                onClick = { showSeedWarningDialog = true },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_privacy_tip),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            text = "View Seed Phrase",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Show your recovery seed phrase or SeedQR",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Root key warning dialog
    if (showRootKeyWarningDialog) {
        AlertDialog(
            onDismissRequest = { showRootKeyWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text("Your BIP32 root key is the master key to your funds. Never share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showRootKeyWarningDialog = false
                        pendingTarget = SensitiveViewTarget.ROOT_KEY
                        passwordError = ""
                        showPasswordDialog = true
                    }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showRootKeyWarningDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = {
                Icon(painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            }
        )
    }

    // Seed phrase warning dialog
    if (showSeedWarningDialog) {
        AlertDialog(
            onDismissRequest = { showSeedWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text("Your seed phrase is the master key to your funds. Never share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showSeedWarningDialog = false
                        pendingTarget = SensitiveViewTarget.SEED_PHRASE
                        passwordError = ""
                        showPasswordDialog = true
                    }
                ) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { showSeedWarningDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = {
                Icon(painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error)
            }
        )
    }
    
    // Password confirmation dialog for sensitive views
    if (showPasswordDialog) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                passwordError = ""
                pendingTarget = null
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
                    passwordError = ""
                    when (pendingTarget) {
                        SensitiveViewTarget.SEED_PHRASE -> onViewSeedPhrase()
                        SensitiveViewTarget.ROOT_KEY -> onViewRootKey()
                        null -> { /* shouldn't happen */ }
                    }
                    pendingTarget = null
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }
}
