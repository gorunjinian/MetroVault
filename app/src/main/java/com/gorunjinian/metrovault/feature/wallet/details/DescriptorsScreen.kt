package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.VerifyPasswordDialog
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.ui.components.TapToCopyQRCard
import com.gorunjinian.metrovault.feature.wallet.details.components.AccountSelectorDropdown
import com.gorunjinian.metrovault.feature.wallet.details.components.Bip48ScriptTypeToggle
import com.gorunjinian.metrovault.feature.wallet.details.components.rememberAccountExportState

/**
 * DescriptorsScreen - Displays wallet output descriptors with QR codes.
 * Supports public/private toggle with password confirmation for private descriptors.
 * Includes account selector to export descriptors for any account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun DescriptorsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    // For descriptors view: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }

    // Export mode: false = single-sig descriptor, true = multisig key (BIP48)
    var exportForMultisig by remember { mutableStateOf(false) }

    // BIP48 script type for multisig export
    var bip48ScriptType by remember { mutableStateOf(DerivationPaths.Bip48ScriptType.P2WSH) }

    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }

    val accountState = rememberAccountExportState(wallet)
    val baseDerivationPath = accountState.baseDerivationPath
    val selectedAccountNumber = accountState.selectedAccountNumber

    // Compute display data based on export mode
    val displayData = remember(selectedAccountNumber, baseDerivationPath, exportForMultisig, showPrivate, bip48ScriptType) {
        if (exportForMultisig) {
            // BIP48 multisig descriptor export
            if (showPrivate) {
                wallet.getBip48PrivateDescriptorForAccount(selectedAccountNumber, bip48ScriptType)
            } else {
                wallet.getBip48DescriptorForAccount(selectedAccountNumber, bip48ScriptType)
            }
        } else {
            // Single-sig descriptor export
            if (baseDerivationPath.isEmpty()) return@remember ""
            if (showPrivate) {
                wallet.getPrivateDescriptorForAccount(baseDerivationPath, selectedAccountNumber)
            } else {
                wallet.getUnifiedDescriptorForAccount(baseDerivationPath, selectedAccountNumber)
            }
        }
    }
    // Labels for display
    val exportTypeLabel = if (exportForMultisig) "Multisig Descriptor" else "Descriptor"
    val keyTypeLabel = if (showPrivate) "Spending" else "Watch-Only"
    val fullLabel = "$keyTypeLabel $exportTypeLabel"

    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Descriptors",
                onBack = onBack,
                actions = {
                    // Single-sig / Multisig Toggle in App Bar
                    SegmentedToggle(
                        firstOption = "Single-sig",
                        secondOption = "Multisig",
                        isSecondSelected = exportForMultisig,
                        onSelectFirst = { exportForMultisig = false },
                        onSelectSecond = { exportForMultisig = true },
                        modifier = Modifier.padding(end = 8.dp),
                        compact = true
                    )
                }
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

            AccountSelectorDropdown(
                label = fullLabel,
                state = accountState,
                accountDetail = { accountNum ->
                    if (exportForMultisig) {
                        DerivationPaths.bip48(accountNum, bip48ScriptType, wallet.isActiveWalletTestnet())
                    } else {
                        DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                    }
                }
            )

            // BIP48 Script Type Selector (only shown in multisig mode)
            if (exportForMultisig) {
                Bip48ScriptTypeToggle(
                    scriptType = bip48ScriptType,
                    onScriptTypeChange = { bip48ScriptType = it },
                    modifier = Modifier.fillMaxWidth()
                )
            }

            // Public/Private Toggle
            SegmentedToggle(
                firstOption = "Public",
                secondOption = "Private",
                isSecondSelected = showPrivate,
                onSelectFirst = { showPrivate = false },
                onSelectSecond = { showPasswordDialog = true },
                modifier = Modifier.fillMaxWidth()
            )

            // Info/Warning card
            if (showPrivate) {
                InfoCard(
                    text = if (exportForMultisig) {
                        "This contains private keys.\nUse only for watch+sign multisig wallets."
                    } else {
                        "This descriptor contains private keys.\nAnyone with it can spend your UTXOs"
                    },
                    tone = InfoTone.Danger,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                InfoCard(
                    text = if (exportForMultisig) {
                        "Import this descriptor into a multisig coordinator."
                    } else {
                        "Import this to an online wallet as a watch-only wallet."
                    },
                    tone = InfoTone.Neutral,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }

            // QR Code
            TapToCopyQRCard(
                data = displayData,
                clipboardLabel = fullLabel,
                tapToCopyEnabled = tapToCopyEnabled,
                contentDescription = "$fullLabel QR Code - Tap to copy"
            )

            // Descriptor/Key text display
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = if (showPrivate) {
                    CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.3f)
                    )
                } else {
                    CardDefaults.cardColors()
                }
            ) {
                Text(
                    text = displayData,
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    // Password confirmation dialog
    if (showPasswordDialog) {
        VerifyPasswordDialog(
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { showPasswordDialog = false },
            onVerified = {
                showPasswordDialog = false
                showPrivate = true
            }
        )
    }
}
