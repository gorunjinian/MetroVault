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

@Suppress("AssignedValueIsNeverRead")
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onViewAddresses: () -> Unit,
    onScanPSBT: () -> Unit,
    onExport: () -> Unit,
    onExportMultiSig: () -> Unit,
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

    // Check if multisig wallet
    val isMultisig = activeWalletMetadata?.isMultisig ?: false

    // Calculated fingerprints for multi-sig local keys (reflects passphrase if entered)
    var calculatedKeyFingerprints by remember { mutableStateOf<List<com.gorunjinian.metrovault.domain.manager.PassphraseManager.CalculatedKeyFingerprint>>(emptyList()) }

    // Load calculated fingerprints when screen opens (for multi-sig wallets)
    LaunchedEffect(activeWalletId, isMultisig) {
        if (isMultisig && activeWalletId != null) {
            calculatedKeyFingerprints = wallet.getCalculatedKeyFingerprints(activeWalletId)
        }
    }

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
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
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
                    // Multi-Sig badge
                    if (isMultisig) {
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = "Multi-Sig",
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
                        
                        // For multisig: show local cosigner derivation paths (max 2)
                        // For single-sig: show the regular derivation path
                        val displayPath = if (isMultisig) {
                            val localCosignerPaths = activeWalletMetadata.multisigConfig?.cosigners
                                ?.filter { it.isLocal }
                                ?.map { "m/${it.derivationPath}" }
                                ?: emptyList()
                            when {
                                localCosignerPaths.isEmpty() -> derivationPath
                                localCosignerPaths.size == 1 -> localCosignerPaths.first()
                                localCosignerPaths.size == 2 -> localCosignerPaths.joinToString(", ")
                                else -> "${localCosignerPaths.take(2).joinToString(", ")} (${localCosignerPaths.size - 2} more)"
                            }
                        } else {
                            derivationPath
                        }
                        
                        Text(
                            text = displayPath,
                            style = MaterialTheme.typography.bodyLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    // Master Fingerprint display
                    // For multisig: show calculated local key fingerprints (reflects passphrase if entered)
                    // For single-sig: show single fingerprint
                    if (isMultisig) {
                        // Multi-sig: use calculated fingerprints (shows passphrase-derived fingerprint if entered)
                        val displayFingerprints = if (calculatedKeyFingerprints.isNotEmpty()) {
                            calculatedKeyFingerprints.map { it.calculatedFingerprint }
                        } else {
                            activeWalletMetadata.multisigConfig?.localKeyFingerprints ?: emptyList()
                        }
                        val hasAnyMismatch = calculatedKeyFingerprints.any { it.isMismatch }

                        if (displayFingerprints.isNotEmpty()) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (displayFingerprints.size > 1) "Local Master FP" else "Master Fingerprint",
                                    style = MaterialTheme.typography.labelMedium,
                                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                                )

                                // Format fingerprints: show up to 2, then "(X more)" if needed
                                val fingerprintsText = displayFingerprints.take(2).joinToString(", ") { it.uppercase() }
                                val extraCount = displayFingerprints.size - 2
                                val fingerprintText = if (extraCount > 0) {
                                    "$fingerprintsText ($extraCount more)"
                                } else {
                                    fingerprintsText
                                }

                                Text(
                                    text = fingerprintText,
                                    style = MaterialTheme.typography.bodyLarge,
                                    fontWeight = FontWeight.Bold,
                                    color = if (hasAnyMismatch)
                                        MaterialTheme.colorScheme.error  // RED when any key has mismatch
                                    else
                                        MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    } else {
                        // Single-sig: show fingerprint with mismatch detection
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
                                    text = fingerprint.uppercase(),
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
                        painter = painterResource(R.drawable.ic_addresses),
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

            // Sign/Verify Message (not for multisig wallets)
            if (!isMultisig) {
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
                            painter = painterResource(R.drawable.ic_signature),
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
            }

            // BIP-85 Derivation (only if enabled in settings and not multisig)
            if (bip85Enabled && !isMultisig) {
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

            // Different Accounts (only if enabled in settings and not multisig)
            if (differentAccountsEnabled && !isMultisig) {
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
                onClick = { if (isMultisig) onExportMultiSig() else onExport() },
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
                            text = if (isMultisig) "Export multisig wallet descriptor" 
                                   else "Export keys, descriptors or seed phrase",
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