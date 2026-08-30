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
 * AccountKeysScreen - Displays extended public and private keys with QR codes.
 * Supports public/private toggle with password confirmation for private keys.
 * Includes account selector to export keys for any account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountKeysScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    // For keys view: false = public, true = private
    var showPrivate by remember { mutableStateOf(false) }

    // Export mode: false = single-sig key, true = multisig key (BIP48)
    var exportForMultisig by remember { mutableStateOf(false) }

    // BIP48 script type for multisig export
    var bip48ScriptType by remember { mutableStateOf(DerivationPaths.Bip48ScriptType.P2WSH) }

    // Password confirmation state
    var showPasswordDialog by remember { mutableStateOf(false) }

    val accountState = rememberAccountExportState(wallet)
    val baseDerivationPath = accountState.baseDerivationPath
    val selectedAccountNumber = accountState.selectedAccountNumber

    // Compute keys based on export mode
    val displayKey = remember(selectedAccountNumber, baseDerivationPath, exportForMultisig, showPrivate, bip48ScriptType) {
        if (exportForMultisig) {
            // BIP48 multisig key export
            if (showPrivate) {
                wallet.getBip48XprivForAccount(selectedAccountNumber, bip48ScriptType)
            } else {
                wallet.getBip48XpubForAccount(selectedAccountNumber, bip48ScriptType)
            }
        } else {
            // Single-sig key export
            if (baseDerivationPath.isEmpty()) return@remember ""
            if (showPrivate) {
                wallet.getXprivForAccount(baseDerivationPath, selectedAccountNumber)
            } else {
                wallet.getXpubForAccount(baseDerivationPath, selectedAccountNumber)
            }
        }
    }

    // Key prefix for display label
    val keyPrefix = when {
        displayKey.startsWith("Zpub") || displayKey.startsWith("Zprv") -> if (showPrivate) "Zprv" else "Zpub"
        displayKey.startsWith("Vpub") || displayKey.startsWith("Vprv") -> if (showPrivate) "Vprv" else "Vpub"
        displayKey.startsWith("Ypub") || displayKey.startsWith("Yprv") -> if (showPrivate) "Yprv" else "Ypub"
        displayKey.startsWith("Upub") || displayKey.startsWith("Uprv") -> if (showPrivate) "Uprv" else "Upub"
        displayKey.startsWith("zpub") || displayKey.startsWith("zprv") -> if (showPrivate) "zprv" else "zpub"
        displayKey.startsWith("ypub") || displayKey.startsWith("yprv") -> if (showPrivate) "yprv" else "ypub"
        displayKey.startsWith("vpub") || displayKey.startsWith("vprv") -> if (showPrivate) "vprv" else "vpub"
        displayKey.startsWith("upub") || displayKey.startsWith("uprv") -> if (showPrivate) "uprv" else "upub"
        displayKey.startsWith("tpub") || displayKey.startsWith("tprv") -> if (showPrivate) "tprv" else "tpub"
        else -> if (showPrivate) "xprv" else "xpub"
    }

    val keyType = if (showPrivate) "Private" else "Public"
    val exportLabel = if (exportForMultisig) "Multisig $keyType Key" else "Extended $keyType Key"

    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Account Keys",
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
                label = "$exportLabel ($keyPrefix)",
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

            // Security warning for private key
            if (showPrivate) {
                InfoCard(
                    text = if (exportForMultisig) {
                        "This key can sign multisig transactions.\nNever share it!"
                    } else {
                        "This key can spend all funds in this account.\nNever share it!"
                    },
                    tone = InfoTone.Danger,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else if (exportForMultisig) {
                // Info card for multisig public key
                InfoCard(
                    text = "Import this key into a multisig coordinator.",
                    tone = InfoTone.Neutral,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            }

            // QR Code
            TapToCopyQRCard(
                data = displayKey,
                clipboardLabel = exportLabel,
                tapToCopyEnabled = tapToCopyEnabled,
                contentDescription = "$keyType Key QR Code - Tap to copy"
            )

            // Key text display
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
                    text = displayKey,
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
