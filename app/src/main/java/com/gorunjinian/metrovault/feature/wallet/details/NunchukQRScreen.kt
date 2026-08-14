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
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.ui.components.TapToCopyQRCard
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.feature.wallet.details.components.AccountSelectorDropdown
import com.gorunjinian.metrovault.feature.wallet.details.components.Bip48ScriptTypeToggle
import com.gorunjinian.metrovault.feature.wallet.details.components.rememberAccountExportState

/**
 * NunchukQRScreen - Displays the public Nunchuk signer record as a QR code.
 * The record is one short line, so a single static QR always suffices.
 * Includes an account selector and a single-sig/multisig (BIP48) toggle.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NunchukQRScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    // Export mode: false = single-sig signer record, true = multisig key (BIP48)
    var exportForMultisig by remember { mutableStateOf(false) }

    // BIP48 script type for multisig export
    var bip48ScriptType by remember { mutableStateOf(DerivationPaths.Bip48ScriptType.P2WSH) }

    val accountState = rememberAccountExportState(wallet)
    val baseDerivationPath = accountState.baseDerivationPath
    val selectedAccountNumber = accountState.selectedAccountNumber

    // Compute the signer record based on export mode
    val signerRecord = remember(selectedAccountNumber, exportForMultisig, bip48ScriptType) {
        if (exportForMultisig) {
            wallet.getNunchukBip48SignerRecordForAccount(selectedAccountNumber, bip48ScriptType)
        } else {
            wallet.getNunchukSignerRecordForAccount(selectedAccountNumber)
        }
    }
    val recordLabel = if (exportForMultisig) "Nunchuk Multisig Key" else "Nunchuk Signer Record"

    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Nunchuk Export",
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
                label = recordLabel,
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

            InfoCard(
                text = if (exportForMultisig) {
                    "In Nunchuk, add this device as a key for a multisig wallet by scanning this QR."
                } else {
                    "In Nunchuk, choose Add Signer via QR and scan this code. It contains only " +
                        "public information and cannot sign."
                },
                tone = InfoTone.Neutral,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            if (signerRecord.isEmpty()) {
                InfoCard(
                    text = "This export is unavailable for the active wallet.",
                    tone = InfoTone.Danger,
                    textStyle = MaterialTheme.typography.bodyMedium
                )
            } else {
                // QR Code
                TapToCopyQRCard(
                    data = signerRecord,
                    clipboardLabel = recordLabel,
                    tapToCopyEnabled = tapToCopyEnabled,
                    contentDescription = "$recordLabel QR Code - Tap to copy"
                )

                // Signer record text display
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = signerRecord,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
