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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletDetailsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    onViewAddresses: () -> Unit,
    onScanPSBT: () -> Unit,
    onExport: () -> Unit,
    onBIP85: () -> Unit,
    onSignMessage: () -> Unit,
    onCheckAddress: () -> Unit,
    onBack: () -> Unit
) {
    var showDeleteDialog by remember { mutableStateOf(false) }
    var showDeletePasswordDialog by remember { mutableStateOf(false) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(wallet.getActiveWalletName()) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Wallet Info Card
            val derivationPath = wallet.getActiveWalletDerivationPath()
            val walletType = when (derivationPath) {
                DerivationPaths.TAPROOT -> "Taproot (bc1p...)"
                DerivationPaths.NATIVE_SEGWIT -> "Native SegWit (bc1...)"
                DerivationPaths.NESTED_SEGWIT -> "Nested SegWit (3...)"
                DerivationPaths.LEGACY -> "Legacy (1...)"
                else -> "Unknown"
            }
            val fingerprint = wallet.getMasterFingerprint()
            
            // Check for fingerprint mismatch (different passphrase entered)
            val wallets by wallet.wallets.collectAsState()
            val activeWalletId = wallet.getActiveWalletId()
            val originalFingerprint = wallets.find { it.id == activeWalletId }?.masterFingerprint
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
                        painter = painterResource(R.drawable.ic_download),
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
                            text = "Export account public key or seed phrase",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

            // BIP-85 Derivation
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
                            text = "Derive child seed phrases",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }

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

            // Delete Wallet
            ElevatedCard(
                onClick = { showDeleteDialog = true },
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

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = { showDeleteDialog = false },
            title = { Text("Delete Wallet") },
            text = { 
                Text("Are you sure you want to delete this wallet?\nThis action cannot be undone unless you have your seed phrase backed up.")
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDeleteDialog = false
                        showDeletePasswordDialog = true
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDeleteDialog = false }) {
                    Text("Cancel")
                }
            },
            icon = { 
                Icon(
                    painter = painterResource(R.drawable.ic_delete), 
                    contentDescription = null, 
                    tint = MaterialTheme.colorScheme.error
                ) 
            }
        )
    }

    val scope = rememberCoroutineScope()

    if (showDeletePasswordDialog) {
        var password by remember { mutableStateOf("") }
        var passwordError by remember { mutableStateOf("") }

        AlertDialog(
            onDismissRequest = { 
                showDeletePasswordDialog = false 
                passwordError = ""
            },
            title = { Text("Confirm Deletion") },
            text = {
                Column {
                    Text("Enter your password to confirm deletion.")
                    Spacer(modifier = Modifier.height(8.dp))
                    SecureOutlinedTextField(
                        value = password,
                        onValueChange = { 
                            password = it
                            passwordError = ""
                        },
                        label = { Text("Password") },
                        singleLine = true,
                        visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                        isError = passwordError.isNotEmpty(),
                        modifier = Modifier.fillMaxWidth()
                    )
                    if (passwordError.isNotEmpty()) {
                        Text(
                            text = passwordError,
                            color = MaterialTheme.colorScheme.error,
                            style = MaterialTheme.typography.bodySmall,
                            modifier = Modifier.padding(top = 4.dp)
                        )
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        scope.launch {
                            val isDecoy = wallet.isDecoyMode
                            val isValid = withContext(kotlinx.coroutines.Dispatchers.IO) {
                                if (isDecoy) {
                                    secureStorage.isDecoyPassword(password)
                                } else {
                                    secureStorage.verifyPasswordSimple(password) && !secureStorage.isDecoyPassword(password)
                                }
                            }
                            
                            if (isValid) {
                                val walletId = wallet.getActiveWalletState()?.let { activeState ->
                                    wallet.wallets.value.find { walletData -> walletData.name == activeState.name }?.id
                                }
                                
                                if (walletId != null) {
                                    // Use the simplified delete method that relies on session password
                                    val deleted = wallet.deleteWallet(walletId)
                                    if (deleted) {
                                        // Deletion successful - wallet removed from memory and storage
                                        showDeletePasswordDialog = false
                                        onBack() // Navigate back to home
                                    } else {
                                        passwordError = "Failed to delete wallet. Session might be expired."
                                    }
                                } else {
                                    passwordError = "Failed to find wallet"
                                }
                            } else {
                                passwordError = "Incorrect password"
                            }
                        }
                    },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error)
                ) {
                    Text("Confirm Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { 
                    showDeletePasswordDialog = false 
                    passwordError = ""
                }) {
                    Text("Cancel")
                }
            }
        )
    }
}