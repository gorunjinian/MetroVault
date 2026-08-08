package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.ActionCard
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.VerifyPasswordDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

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
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    isStatelessWallet: Boolean = false,
    onBack: () -> Unit,
    onExportCoordinator: () -> Unit,
    onViewAccountKeys: () -> Unit,
    onViewDescriptors: () -> Unit,
    onViewRootKey: () -> Unit,
    onViewSeedPhrase: () -> Unit,
    onViewSilentPayments: () -> Unit = {}
) {
    // BIP-352 silent-payment export visibility = (toggle ON OR SP-flagged wallet) AND seed loaded.
    // SP-flagged wallets always show the export; regular wallets only show it when the user has
    // opted in via Advanced Settings.
    val silentPaymentsEnabled by userPreferencesRepository.silentPaymentsEnabled.collectAsState()
    val isSpFlaggedWallet = remember { wallet.isActiveSilentPayment() }
    val canDeriveSilentPayments = remember { wallet.canExportSilentPaymentForActiveWallet() }
    val canExportSilentPayments = canDeriveSilentPayments && (silentPaymentsEnabled || isSpFlaggedWallet)
    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }
    
    // Track which sensitive view is being accessed
    var pendingTarget by remember { mutableStateOf<SensitiveViewTarget?>(null) }
    
    // Warning dialog for seed phrase and BIP32 key
    var showSeedWarningDialog by remember { mutableStateOf(false) }
    var showRootKeyWarningDialog by remember { mutableStateOf(false) }
    
    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Export",
                onBack = onBack
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

            Text(
                text = "Export to another wallet",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.primary
            )

            ElevatedCard(
                onClick = onExportCoordinator,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_scanner),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text("Export to wallet coordinator", style = MaterialTheme.typography.titleMedium)
                        Text(
                            "Import this wallet into Nunchuk or another watch-only coordinator",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Surface(
                            color = MaterialTheme.colorScheme.primaryContainer,
                            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                            shape = MaterialTheme.shapes.small
                        ) {
                            Text(
                                "Public data only — cannot spend funds",
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelSmall
                            )
                        }
                    }
                }
            }

            // Cards 1 & 2: View Account Extended Keys and View Output Descriptors.
            // Hidden for SP-flagged wallets — BIP-352 wallets have no meaningful xpub or wpkh/tr
            // descriptor; the SP-equivalent export is the spscan/descriptor inside the Silent
            // Payments card. Root Key and Seed Phrase still expose the underlying master.
            if (!isSpFlaggedWallet) {
                ActionCard(
                    icon = R.drawable.ic_key,
                    title = "View Account Extended Keys",
                    description = "Extended public & private keys",
                    onClick = onViewAccountKeys
                )

                ActionCard(
                    icon = R.drawable.ic_desciption,
                    title = "View Output Descriptors",
                    description = "Public & spending descriptors",
                    onClick = onViewDescriptors
                )
            }

            // Card: Silent Payments (sp1q… + gated spscan…) — single-sig seed-based wallets only
            if (canExportSilentPayments) {
                ActionCard(
                    icon = R.drawable.ic_qr_code_scanner,
                    title = "Silent Payments",
                    description = "SP Scan key & descriptor for Silent Payments",
                    onClick = onViewSilentPayments
                )
            }

            Text(
                text = "Sensitive recovery material",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.error
            )

            // Card 3: View Root Key
            ActionCard(
                icon = R.drawable.ic_root,
                title = "View BIP32 Root Key",
                description = "Show your wallet's main BIP32 root key",
                onClick = { showRootKeyWarningDialog = true },
                iconTint = MaterialTheme.colorScheme.error,
                descriptionColor = MaterialTheme.colorScheme.error
            )

            // Card 4: View Seed Phrase (hidden for stateless wallets)
            if (!isStatelessWallet) {
                ActionCard(
                    icon = R.drawable.ic_privacy_tip,
                    title = "View Seed Phrase",
                    description = "Show your recovery seed phrase or SeedQR",
                    onClick = { showSeedWarningDialog = true },
                    iconTint = MaterialTheme.colorScheme.error,
                    descriptionColor = MaterialTheme.colorScheme.error
                )
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
        VerifyPasswordDialog(
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = {
                showPasswordDialog = false
                pendingTarget = null
            },
            onVerified = {
                showPasswordDialog = false
                when (pendingTarget) {
                    SensitiveViewTarget.SEED_PHRASE -> onViewSeedPhrase()
                    SensitiveViewTarget.ROOT_KEY -> onViewRootKey()
                    null -> { /* shouldn't happen */ }
                }
                pendingTarget = null
            }
        )
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun ExportOptionsPreview() {
    MaterialTheme {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Text("Export Options", style = MaterialTheme.typography.headlineSmall)
            Text("Export to another wallet", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
            PreviewExportCard(
                "Export to wallet coordinator",
                "Import this wallet into Nunchuk or another watch-only coordinator",
                "Public data only — cannot spend funds"
            )
            PreviewExportCard(
                "Advanced: Account Extended Keys",
                "Inspect raw public keys or password-protected private keys"
            )
            PreviewExportCard(
                "Advanced: Output Descriptors",
                "Inspect watch-only or password-protected spending descriptors"
            )
            Text("Sensitive recovery material", style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.error)
            PreviewExportCard("View BIP32 Root Key", "Show your wallet's main BIP32 root key")
            PreviewExportCard("View Seed Phrase", "Show your recovery seed phrase or SeedQR")
        }
    }
}

@Composable
private fun PreviewExportCard(title: String, description: String, badge: String? = null) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            badge?.let {
                Surface(
                    color = MaterialTheme.colorScheme.primaryContainer,
                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                    shape = MaterialTheme.shapes.small
                ) {
                    Text(it, modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp), style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }
}
