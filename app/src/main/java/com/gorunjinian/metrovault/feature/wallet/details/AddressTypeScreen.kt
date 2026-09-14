package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.data.model.AddressFormat
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * "Address Type & Network" screen — lets the user move a persisted single-seed wallet between the
 * five address formats (Taproot / Native SegWit / Nested SegWit / Legacy / Silent Payments) and,
 * via the switch in the top bar, between mainnet and testnet, without re-importing the seed. The
 * same seed backs every choice; only the derivation path (and, for Silent Payments, the wallet's
 * SP flag and cached scan/spend pubkeys) changes.
 *
 * Tapping a row or flipping the switch produces one [PendingChange] — a target format on a target
 * network — which is confirmed in a single dialog and applied by [Wallet.changeDerivation]. While
 * it runs, the row for the target format shows an inline spinner and every control is disabled.
 * Multisig and stateless wallets never reach this screen.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressTypeScreen(
    wallet: Wallet,
    onBack: () -> Unit,
) {
    val scope = rememberCoroutineScope()

    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    val metadata = walletsList.find { it.id == walletId }
    val currentPath = metadata?.derivationPath ?: ""
    val activeAccount = metadata?.activeAccountNumber ?: 0
    val isTestnet = DerivationPaths.isTestnet(currentPath)
    val currentFormat = if (currentPath.isEmpty()) null else AddressFormat.fromPath(currentPath)

    // The change awaiting confirmation. The Switch stays bound to the persisted network, so
    // cancelling the dialog simply leaves it where it was.
    var pending by remember { mutableStateOf<PendingChange?>(null) }
    // The change being applied while the wallet unloads and reloads.
    var inFlight by remember { mutableStateOf<PendingChange?>(null) }
    val isSwitching = inFlight != null

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Address Type & Network",
                onBack = onBack,
                actions = {
                    Text(
                        text = "Testnet",
                        style = MaterialTheme.typography.labelMedium,
                        color = if (isTestnet) MaterialTheme.colorScheme.primary
                        else MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Switch(
                        checked = isTestnet,
                        enabled = !isSwitching && currentFormat != null,
                        onCheckedChange = { testnet ->
                            currentFormat?.let { pending = PendingChange(it, testnet) }
                        }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                }
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
                    text = "Change the address format and network this wallet derives from. The same seed is " +
                        "used; only the derivation path and address format change. Accounts and " +
                        "their custom names are preserved.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            items(items = FORMAT_ORDER, key = { it.name }) { format ->
                val isActive = currentFormat == format
                OptionCard(
                    title = format.label(isTestnet),
                    path = DerivationPaths.withAccountNumber(
                        DerivationPaths.baseForFormat(format, isTestnet),
                        activeAccount
                    ),
                    isActive = isActive,
                    spinning = inFlight?.format == format,
                    enabled = !isSwitching && !isActive,
                    onClick = { pending = PendingChange(format, isTestnet) }
                )
            }
        }
    }

    val change = pending
    if (change != null && currentFormat != null && walletId != null) {
        val networkChange = change.testnet != isTestnet
        AlertDialog(
            onDismissRequest = { pending = null },
            title = {
                Text(
                    when {
                        networkChange && change.testnet -> "Switch to Testnet?"
                        networkChange -> "Switch to Mainnet?"
                        else -> "Switch to ${change.format.displayName}?"
                    }
                )
            },
            text = {
                Text(
                    buildString {
                        append("This wallet's addresses will change from ")
                        append("${currentFormat.label(isTestnet)} to ")
                        append("${change.format.label(change.testnet)}. The same seed is used — ")
                        append("switching back restores the previous addresses.")
                        // The scan key is re-derived for the new purpose/network either way.
                        if (change.format.isSilentPayment) {
                            append(" Export the scan key to your watching wallet afterwards so ")
                            append("it can detect incoming payments.")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        inFlight = change
                        pending = null
                        scope.launch {
                            wallet.changeDerivation(walletId, change.format, change.testnet)
                            inFlight = null
                        }
                    }
                ) { Text("Switch") }
            },
            dismissButton = {
                TextButton(onClick = { pending = null }) { Text("Cancel") }
            }
        )
    }
}

/** A requested move of the wallet to [format] on the network selected by [testnet]. */
private data class PendingChange(val format: AddressFormat, val testnet: Boolean)

/** Display order of the rows. The four script types come first, Silent Payments last. */
private val FORMAT_ORDER = listOf(
    AddressFormat.NATIVE_SEGWIT,
    AddressFormat.LEGACY,
    AddressFormat.NESTED_SEGWIT,
    AddressFormat.TAPROOT,
    AddressFormat.SILENT_PAYMENTS,
)

/**
 * One selectable row: address-format title, the account-level derivation path beneath it, a
 * "Currently active" marker on the highlighted row, and an inline spinner while a switch that
 * affects this row is in flight.
 */
@Composable
private fun OptionCard(
    title: String,
    path: String,
    isActive: Boolean,
    spinning: Boolean,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    OutlinedCard(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = onClick),
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
                    text = title,
                    style = MaterialTheme.typography.titleMedium
                )
                Text(
                    text = path,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (isActive && !spinning) {
                    Text(
                        text = "Currently active",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
            }
            if (spinning) {
                CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp
                )
            }
        }
    }
}
