package com.gorunjinian.metrovault.feature.wallet.details

import android.view.HapticFeedbackConstants
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.DeleteWalletDialogs
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onViewAddresses: () -> Unit,
    onScanPSBT: () -> Unit,
    onExport: () -> Unit,
    onBIP85: () -> Unit,
    onSignMessage: () -> Unit,
    onCheckAddress: () -> Unit,
    onDifferentAccounts: () -> Unit,
    onLock: () -> Unit,
    onBack: () -> Unit
) {
    val view = LocalView.current
    
    // Collect wallets to get the active wallet metadata
    val wallets by wallet.wallets.collectAsState()
    val activeWalletId = wallet.getActiveWalletId()
    val activeWalletMetadata = wallets.find { it.id == activeWalletId }
    
    // Settings
    val differentAccountsEnabled by userPreferencesRepository.differentAccountsEnabled.collectAsState()
    val bip85Enabled by userPreferencesRepository.bip85Enabled.collectAsState()
    val activeAccountNumber = wallet.getActiveAccountNumber()
    
    // Delete wallet dialog state
    var walletToDelete by remember { mutableStateOf<WalletMetadata?>(null) }
    
    // Calculate isTestnet early for app bar badge
    val derivationPath = wallet.getActiveWalletDerivationPath()
    val isTestnet = DerivationPaths.isTestnet(derivationPath)

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wallet.getActiveWalletName()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                actions = {
                    // Testnet badge
                    if (isTestnet) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Testnet",
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                                style = MaterialTheme.typography.labelMedium,
                                fontWeight = FontWeight.SemiBold
                            )
                        }
                        Spacer(modifier = Modifier.width(8.dp))
                    }
                    IconButton(
                        onClick = {
                            view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                            wallet.emergencyWipe()
                            onLock()
                        }
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_lock),
                            contentDescription = "Lock",
                            modifier = Modifier.size(24.dp)
                        )
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
            // Wallet Info Card
            val walletType = when (DerivationPaths.getPurpose(derivationPath)) {
                86 -> if (isTestnet) "Taproot (tb1p...)" else "Taproot (bc1p...)"
                84 -> if (isTestnet) "Native SegWit (tb1q...)" else "Native SegWit (bc1...)"
                49 -> if (isTestnet) "Nested SegWit (2...)" else "Nested SegWit (3...)"
                44 -> if (isTestnet) "Legacy (m/n...)" else "Legacy (1...)"
                else -> "Unknown"
            }
            val fingerprint = wallet.getMasterFingerprint()
            
            // Check for fingerprint mismatch (different passphrase entered)
            val originalFingerprint = activeWalletMetadata?.masterFingerprint
            val fingerprintMismatch = fingerprint != null && 
                                      originalFingerprint != null && 
                                      fingerprint != originalFingerprint

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Address Type",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = walletType,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Derivation Path",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = derivationPath,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    if (fingerprint != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween,
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(
                                text = "Master Fingerprint",
                                style = MaterialTheme.typography.labelMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                            )
                            Text(
                                text = fingerprint,
                                style = MaterialTheme.typography.bodyLarge,
                                fontWeight = FontWeight.Bold,
                                color = if (fingerprintMismatch) 
                                    MaterialTheme.colorScheme.error  // RED when mismatch
                                else 
                                    MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }
            }

            Text(
                text = "Wallet Operations",
                style = MaterialTheme.typography.titleLarge
            )

            // View Addresses
            ElevatedCard(
                onClick = onViewAddresses,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_qr_code_2),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "View Addresses",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Generate and view public addresses",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Sign PSBT
            ElevatedCard(
                onClick = onScanPSBT,
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
                    Column {
                        Text(
                            text = "Sign PSBT",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Scan and sign a transaction",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Check Address
            ElevatedCard(
                onClick = onCheckAddress,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_search),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Check Address",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Verify if an address belongs to this wallet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = "Advanced",
                style = MaterialTheme.typography.titleLarge
            )

            // Sign/Verify Message
            ElevatedCard(
                onClick = onSignMessage,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_edit),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Sign/Verify Message",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Sign messages or verify signatures",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // BIP-85 Derivation (only if enabled in settings)
            if (bip85Enabled) {
                ElevatedCard(
                    onClick = onBIP85,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_account_tree),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "BIP-85 Derivation",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Derive child seed phrases or passwords",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Different Accounts (only if enabled in settings)
            if (differentAccountsEnabled) {
                ElevatedCard(
                    onClick = onDifferentAccounts,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(16.dp),
                        horizontalArrangement = Arrangement.spacedBy(16.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Icon(
                            painter = painterResource(R.drawable.ic_accounts),
                            contentDescription = null,
                            modifier = Modifier.size(40.dp),
                            tint = MaterialTheme.colorScheme.primary
                        )
                        Column {
                            Text(
                                text = "Different Accounts",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = "Manage BIP44 account numbers (current: $activeAccountNumber)",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }

            // Export Options
            ElevatedCard(
                onClick = onExport,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_upload),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.primary
                    )
                    Column {
                        Text(
                            text = "Export",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Export keys, descriptors or seed phrase",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // Delete Wallet
            ElevatedCard(
                onClick = { walletToDelete = activeWalletMetadata },
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(
                    modifier = Modifier.padding(16.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Icon(
                        painter = painterResource(R.drawable.ic_delete),
                        contentDescription = null,
                        modifier = Modifier.size(40.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Column {
                        Text(
                            text = "Delete Wallet",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            text = "Permanently remove this wallet",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }

    // Delete wallet dialogs (reusable component handles both confirmation and password steps)
    DeleteWalletDialogs(
        walletToDelete = walletToDelete,
        wallet = wallet,
        secureStorage = secureStorage,
        onDismiss = { walletToDelete = null },
        onDeleted = { 
            walletToDelete = null
            onBack() // Navigate back to home after deletion
        }
    )
}