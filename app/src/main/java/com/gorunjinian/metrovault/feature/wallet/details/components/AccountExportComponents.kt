package com.gorunjinian.metrovault.feature.wallet.details.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.MutableIntState
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.domain.Wallet

/**
 * Per-account export state shared by the export screens (descriptors, account keys, and the
 * coordinator QR screens): the active wallet's metadata, its account list, and the currently
 * selected account. Rebuilt every recomposition so metadata stays fresh; only the selection is
 * remembered.
 */
class AccountExportState(
    val activeWalletMetadata: WalletMetadata?,
    val accounts: List<Int>,
    val baseDerivationPath: String,
    private val selectedAccountState: MutableIntState
) {
    var selectedAccountNumber: Int
        get() = selectedAccountState.intValue
        set(value) {
            selectedAccountState.intValue = value
        }

    fun accountDisplayName(accountNumber: Int): String =
        activeWalletMetadata?.getAccountDisplayName(accountNumber) ?: "Account $accountNumber"

    val selectedAccountName: String get() = accountDisplayName(selectedAccountNumber)
}

/** Unified for both stateless and persistent wallets, initially selecting the active account. */
@Composable
fun rememberAccountExportState(wallet: Wallet): AccountExportState {
    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    val activeWalletMetadata = remember(walletsList, walletId) {
        walletsList.find { it.id == walletId }
    }
    // Get unified wallet info (handles stateless vs persistent internally)
    val walletInfo = wallet.getActiveWalletInfo(activeWalletMetadata)
    val selectedAccount = remember { mutableIntStateOf(walletInfo.accountNumber) }
    return AccountExportState(
        activeWalletMetadata = activeWalletMetadata,
        accounts = walletInfo.accounts,
        baseDerivationPath = walletInfo.derivationPath,
        selectedAccountState = selectedAccount
    )
}

/**
 * The export screens' account selector: a read-only text field opening a dropdown with one
 * two-line entry per account — display name over [accountDetail], typically the derivation path
 * the current export mode would use for that account.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AccountSelectorDropdown(
    label: String,
    state: AccountExportState,
    accountDetail: (Int) -> String,
    modifier: Modifier = Modifier
) {
    var expanded by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(
        expanded = expanded,
        onExpandedChange = { expanded = it }
    ) {
        OutlinedTextField(
            value = state.selectedAccountName,
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = expanded) },
            modifier = modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
        )
        ExposedDropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false }
        ) {
            state.accounts.forEach { accountNum ->
                DropdownMenuItem(
                    text = {
                        Column(modifier = Modifier.padding(vertical = 4.dp)) {
                            Text(
                                text = state.accountDisplayName(accountNum),
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = accountDetail(accountNum),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    },
                    onClick = {
                        state.selectedAccountNumber = accountNum
                        expanded = false
                    },
                    contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                )
                if (accountNum != state.accounts.last()) {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    )
                }
            }
        }
    }
}

/** BIP48 script type selector shown by the export screens in multisig mode. */
@Composable
fun Bip48ScriptTypeToggle(
    scriptType: DerivationPaths.Bip48ScriptType,
    onScriptTypeChange: (DerivationPaths.Bip48ScriptType) -> Unit,
    modifier: Modifier = Modifier
) {
    SegmentedToggle(
        options = listOf(
            DerivationPaths.Bip48ScriptType.P2WSH.displayName,
            DerivationPaths.Bip48ScriptType.P2SH_P2WSH.displayName
        ),
        selectedIndex = if (scriptType == DerivationPaths.Bip48ScriptType.P2WSH) 0 else 1,
        onSelect = { index ->
            onScriptTypeChange(
                if (index == 0) {
                    DerivationPaths.Bip48ScriptType.P2WSH
                } else {
                    DerivationPaths.Bip48ScriptType.P2SH_P2WSH
                }
            )
        },
        modifier = modifier
    )
}
