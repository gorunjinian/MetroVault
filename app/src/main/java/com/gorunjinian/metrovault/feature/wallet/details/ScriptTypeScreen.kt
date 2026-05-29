package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * "Change Script Type" screen — lets the user switch a single-sig wallet between the four standard
 * BIPs (Taproot / Native SegWit / Nested SegWit / Legacy) without re-importing the seed. The same
 * seed backs every type; only the address tree changes.
 *
 * Structurally a trimmed-down [DifferentAccountsScreen]: a fixed list of rows with an
 * "Currently active" highlight, tap-to-switch, and an inline spinner on the tapped row during the
 * brief unload-and-reload cycle. No add/remove/rename — the four BIPs are a closed set.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ScriptTypeScreen(
    wallet: Wallet,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()

    val activeWalletMetadata by remember(walletsList, walletId) {
        derivedStateOf { walletsList.find { it.id == walletId } }
    }
    val currentPath by remember(activeWalletMetadata) {
        derivedStateOf { activeWalletMetadata?.derivationPath ?: "" }
    }
    val activeAccount by remember(activeWalletMetadata) {
        derivedStateOf { activeWalletMetadata?.activeAccountNumber ?: 0 }
    }
    val isTestnet by remember(currentPath) {
        derivedStateOf { DerivationPaths.isTestnet(currentPath) }
    }
    val currentScriptType by remember(currentPath) {
        derivedStateOf {
            if (currentPath.isEmpty()) null else DerivationPaths.getScriptType(currentPath)
        }
    }

    var switchingTo by remember { mutableStateOf<ScriptType?>(null) }
    val isSwitching = switchingTo != null

    var pendingSwitch by remember { mutableStateOf<ScriptTypeOption?>(null) }

    val options = remember(isTestnet) { buildOptions(isTestnet) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Script Type") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            contentPadding = PaddingValues(vertical = 16.dp)
        ) {
            item {
                Text(
                    text = "Change the address tree this wallet derives from. The same seed is " +
                        "used; only the BIP purpose and address format change. Accounts and their custom " +
                        "names are preserved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(items = options, key = { it.scriptType.name }) { option ->
                val isActive = currentScriptType == option.scriptType
                val rowSpinning = switchingTo == option.scriptType
                val path = DerivationPaths.withAccountNumber(option.basePath, activeAccount)

                OutlinedCard(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable(enabled = !isSwitching && !isActive) {
                            pendingSwitch = option
                        },
                    colors = if (isActive) {
                        CardDefaults.outlinedCardColors(
                            containerColor = MaterialTheme.colorScheme.primaryContainer
                        )
                    } else {
                        CardDefaults.outlinedCardColors()
                    },
                    border = if (isActive) {
                        BorderStroke(2.dp, MaterialTheme.colorScheme.primary)
                    } else {
                        BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant)
                    }
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(16.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                text = "${option.displayName} (${option.examplePrefix}…)",
                                style = MaterialTheme.typography.titleMedium
                            )
                            Text(
                                text = path,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                            if (isActive && !rowSpinning) {
                                Text(
                                    text = "Currently active",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = MaterialTheme.colorScheme.primary
                                )
                            }
                        }
                        if (rowSpinning) {
                            CircularProgressIndicator(
                                modifier = Modifier.size(16.dp),
                                strokeWidth = 2.dp
                            )
                        }
                    }
                }
            }
        }
    }

    val pending = pendingSwitch
    val currentOption = options.firstOrNull { it.scriptType == currentScriptType }
    if (pending != null && currentOption != null && walletId != null) {
        AlertDialog(
            onDismissRequest = { if (!isSwitching) pendingSwitch = null },
            title = { Text("Switch to ${pending.displayName}?") },
            text = {
                Text(
                    "This wallet's addresses will change from " +
                        "${currentOption.displayName} (${currentOption.examplePrefix}…) to " +
                        "${pending.displayName} (${pending.examplePrefix}…). The same seed is used — " +
                        "funds at the previous address tree remain accessible by switching back."
                )
            },
            confirmButton = {
                TextButton(
                    enabled = !isSwitching,
                    onClick = {
                        val target = pending.scriptType
                        switchingTo = target
                        pendingSwitch = null
                        scope.launch {
                            wallet.changeScriptType(walletId, target)
                            switchingTo = null
                        }
                    }
                ) { Text("Switch") }
            },
            dismissButton = {
                TextButton(
                    enabled = !isSwitching,
                    onClick = { pendingSwitch = null }
                ) { Text("Cancel") }
            }
        )
    }
}

/**
 * One row in the script-type picker. The example prefix is wallet-network-aware (set when [buildOptions]
 * computes the list against the current testnet state) so users see `bc1q…` or `tb1q…` as appropriate.
 */
private data class ScriptTypeOption(
    val scriptType: ScriptType,
    val displayName: String,
    val examplePrefix: String,
    val basePath: String,
)

private fun buildOptions(isTestnet: Boolean): List<ScriptTypeOption> = if (isTestnet) {
    listOf(
        ScriptTypeOption(ScriptType.P2WPKH, "Native SegWit", "tb1q", DerivationPaths.NATIVE_SEGWIT_TESTNET),
        ScriptTypeOption(ScriptType.P2PKH, "Legacy", "m/n", DerivationPaths.LEGACY_TESTNET),
        ScriptTypeOption(ScriptType.P2SH_P2WPKH, "Nested SegWit", "2", DerivationPaths.NESTED_SEGWIT_TESTNET),
        ScriptTypeOption(ScriptType.P2TR, "Taproot", "tb1p", DerivationPaths.TAPROOT_TESTNET),
    )
} else {
    listOf(
        ScriptTypeOption(ScriptType.P2WPKH, "Native SegWit", "bc1q", DerivationPaths.NATIVE_SEGWIT),
        ScriptTypeOption(ScriptType.P2PKH, "Legacy", "1", DerivationPaths.LEGACY),
        ScriptTypeOption(ScriptType.P2SH_P2WPKH, "Nested SegWit", "3", DerivationPaths.NESTED_SEGWIT),
        ScriptTypeOption(ScriptType.P2TR, "Taproot", "bc1p", DerivationPaths.TAPROOT),
    )
}
