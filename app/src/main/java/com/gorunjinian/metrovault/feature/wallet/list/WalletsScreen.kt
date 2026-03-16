package com.gorunjinian.metrovault.feature.wallet.list

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.DeleteWalletDialogs
import com.gorunjinian.metrovault.core.ui.dialogs.PassphraseEntryDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RenameWalletDialog
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.QuickShortcut
import com.gorunjinian.metrovault.data.model.WalletMetadata
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

@Composable
fun WalletsListContent(
    wallet: Wallet,
    secureStorage: SecureStorage,
    isEditMode: Boolean = false,
    autoExpandSingleWallet: Boolean = false,
    quickShortcuts: List<QuickShortcut> = QuickShortcut.DEFAULT,
    onWalletClick: (String) -> Unit,
    onStatelessWalletClick: () -> Unit = {},
    onViewAddresses: (String) -> Unit,
    onScanPSBT: (String) -> Unit,
    onCheckAddress: (String) -> Unit,
    onExport: (String) -> Unit,
    onBIP85: (String) -> Unit,
    onSignMessage: (String) -> Unit
) {
    val wallets by wallet.wallets.collectAsState()
    val view = LocalView.current
    var showRenameDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPassphraseDialog by remember { mutableStateOf<WalletMetadata?>(null) }
    // Track pending shortcut action when passphrase is required
    var pendingShortcut by remember { mutableStateOf<QuickShortcut?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var expandedWalletId by remember { mutableStateOf<String?>(null) }

    // Multi-sig passphrase dialog state
    var multisigKeysNeedingPassphrase by remember { mutableStateOf<List<com.gorunjinian.metrovault.domain.manager.PassphraseManager.KeyPassphraseInfo>>(emptyList()) }
    var currentKeyPassphraseIndex by remember { mutableIntStateOf(0) }
    var pendingMultisigWalletId by remember { mutableStateOf<String?>(null) }
    
    // Delete wallet dialog state (single state drives the reusable component)
    var walletToDelete by remember { mutableStateOf<WalletMetadata?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-expand the wallet card when there's only one wallet (if preference is enabled)
    LaunchedEffect(wallets, autoExpandSingleWallet) {
        if (autoExpandSingleWallet && wallets.size == 1) {
            expandedWalletId = wallets.first().id
        }
    }

    if (wallets.isEmpty()) {
        // Empty State
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_wallet),
                    contentDescription = null,
                    modifier = Modifier.size(80.dp),
                    tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
                Text(
                    "No wallets in this vault",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
                )
                Text(
                    "Tap the + button to add one",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                )
            }
        }
    } else {
        // Wallet List with Drag and Drop using LazyColumn for better performance
        val lazyListState = rememberLazyListState()
        val reorderableState = rememberReorderableLazyListState(lazyListState) { from, to ->
            val fromIndex = wallets.indexOfFirst { it.id == from.key }
            val toIndex = wallets.indexOfFirst { it.id == to.key }
            if (fromIndex != -1 && toIndex != -1) {
                wallet.swapWallets(fromIndex, toIndex)
            }
        }

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Disable user scroll during drag for smoother experience
            userScrollEnabled = !isEditMode
        ) {
            // Stateless wallet card at top of list (if active)
            val statelessState = wallet.getStatelessWalletState()
            if (statelessState != null && !isEditMode) {
                item(key = "stateless_wallet") {
                    StatelessWalletCard(
                        fingerprint = statelessState.fingerprint.uppercase(),
                        derivationPath = statelessState.derivationPath,
                        onClick = onStatelessWalletClick
                    )
                }
                // Divider between stateless and regular wallets
                if (wallets.isNotEmpty()) {
                    item(key = "stateless_divider") {
                        HorizontalDivider(
                            modifier = Modifier.padding(vertical = 4.dp),
                            color = MaterialTheme.colorScheme.outlineVariant
                        )
                    }
                }
            }
            
            itemsIndexed(
                items = wallets,
                key = { _, walletItem -> walletItem.id }
            ) { index, walletItem ->
                ReorderableItem(reorderableState, key = walletItem.id) { isDragging ->
                    val baseScale = if (isEditMode) 0.97f else 1f
                    val scale by animateFloatAsState(
                        if (isDragging) baseScale * 1.05f else baseScale, label = "dragScale"
                    )
                    val shadow by animateDpAsState(
                        if (isDragging) 8.dp else 2.dp, label = "dragShadow"
                    )

                    Box(modifier = Modifier.graphicsLayer { scaleX = scale; scaleY = scale }) {
                        // Compute wallet type and testnet status
                        val isWalletTestnet = remember(walletItem.derivationPath) {
                            DerivationPaths.isTestnet(walletItem.derivationPath)
                        }

                        WalletCard(
                            name = walletItem.name,
                            masterFingerprint = walletItem.masterFingerprint.uppercase(),
                            isTestnet = isWalletTestnet,
                            isMultisig = walletItem.isMultisig,
                            elevation = shadow,
                            isEditMode = isEditMode,
                            isExpanded = if (isEditMode) false else expandedWalletId == walletItem.id,
                            // For multisig wallets, use only allowed shortcuts: Addresses, Sign PSBT, Check Address
                            quickShortcuts = if (walletItem.isMultisig) {
                                QuickShortcut.DEFAULT
                            } else {
                                quickShortcuts
                            },
                            onClick = {
                                if (!isDragging) {
                                    if (isEditMode) {
                                        // In edit mode, tapping card opens rename dialog
                                        showRenameDialog = walletItem.id to walletItem.name
                                    } else {
                                        // Normal mode: navigate to wallet details
                                        if (walletItem.isMultisig) {
                                            // Multi-sig: check if any local keys need passphrase
                                            scope.launch {
                                                val keysNeeding = wallet.getKeysNeedingPassphrase(walletItem.id)
                                                if (keysNeeding.isNotEmpty()) {
                                                    multisigKeysNeedingPassphrase = keysNeeding
                                                    currentKeyPassphraseIndex = 0
                                                    pendingMultisigWalletId = walletItem.id
                                                } else {
                                                    onWalletClick(walletItem.id)
                                                }
                                            }
                                        } else {
                                            // Single-sig: use existing passphrase check
                                            if (wallet.needsPassphraseInput(walletItem.id)) {
                                                showPassphraseDialog = walletItem
                                            } else {
                                                onWalletClick(walletItem.id)
                                            }
                                        }
                                    }
                                }
                            },
                            onLongPress = {
                                // Long press to delete wallet (only when not in edit mode)
                                if (!isEditMode && !isDragging) {
                                    view.performHapticFeedback(HapticFeedbackConstants.LONG_PRESS)
                                    walletToDelete = walletItem
                                }
                            },
                            onExpandClick = {
                                if (!isEditMode) {
                                    expandedWalletId = if (expandedWalletId == walletItem.id) null else walletItem.id
                                }
                            },
                            onShortcutClick = { shortcut ->
                                // Check if wallet needs passphrase re-entry before executing shortcut
                                if (walletItem.isMultisig) {
                                    // Multi-sig: check if any local keys need passphrase
                                    scope.launch {
                                        val keysNeeding = wallet.getKeysNeedingPassphrase(walletItem.id)
                                        if (keysNeeding.isNotEmpty()) {
                                            pendingShortcut = shortcut
                                            multisigKeysNeedingPassphrase = keysNeeding
                                            currentKeyPassphraseIndex = 0
                                            pendingMultisigWalletId = walletItem.id
                                        } else {
                                            when (shortcut) {
                                                QuickShortcut.VIEW_ADDRESSES -> onViewAddresses(walletItem.id)
                                                QuickShortcut.SIGN_PSBT -> onScanPSBT(walletItem.id)
                                                QuickShortcut.CHECK_ADDRESS -> onCheckAddress(walletItem.id)
                                                QuickShortcut.EXPORT -> onExport(walletItem.id)
                                                QuickShortcut.BIP85 -> onBIP85(walletItem.id)
                                                QuickShortcut.SIGN_MESSAGE -> onSignMessage(walletItem.id)
                                            }
                                        }
                                    }
                                } else if (wallet.needsPassphraseInput(walletItem.id)) {
                                    pendingShortcut = shortcut
                                    showPassphraseDialog = walletItem
                                } else {
                                    when (shortcut) {
                                        QuickShortcut.VIEW_ADDRESSES -> onViewAddresses(walletItem.id)
                                        QuickShortcut.SIGN_PSBT -> onScanPSBT(walletItem.id)
                                        QuickShortcut.CHECK_ADDRESS -> onCheckAddress(walletItem.id)
                                        QuickShortcut.EXPORT -> onExport(walletItem.id)
                                        QuickShortcut.BIP85 -> onBIP85(walletItem.id)
                                        QuickShortcut.SIGN_MESSAGE -> onSignMessage(walletItem.id)
                                    }
                                }
                            },
                            modifier = if (isEditMode) {
                                Modifier.draggableHandle(
                                    onDragStopped = {
                                        scope.launch { wallet.saveWalletListOrder() }
                                    }
                                )
                            } else {
                                Modifier
                            }
                        )
                    }
                }
            }
        }
    }

    if (showRenameDialog != null) {
        val (walletId, currentName) = showRenameDialog!!
        RenameWalletDialog(
            currentName = currentName,
            onDismiss = { showRenameDialog = null },
            onConfirm = { newName ->
                scope.launch {
                    if (wallet.renameWallet(walletId, newName)) {
                        showRenameDialog = null
                    } else {
                        errorMessage = "Failed to rename wallet. Session might be expired."
                    }
                }
            }
        )
    }

    // Passphrase re-entry dialog for single-sig wallets with unsaved passphrase
    if (showPassphraseDialog != null) {
        val walletMeta = showPassphraseDialog!!
        var mnemonic by remember { mutableStateOf<List<String>?>(null) }

        // Load mnemonic when dialog opens
        LaunchedEffect(walletMeta.id) {
            mnemonic = wallet.getMnemonicForWallet(walletMeta.id)
        }

        if (mnemonic != null) {
            PassphraseEntryDialog(
                label = walletMeta.name,
                originalFingerprint = walletMeta.masterFingerprint,
                onDismiss = {
                    showPassphraseDialog = null
                    pendingShortcut = null
                },
                onConfirm = { passphrase, _ ->
                    // Store session seed (computed from passphrase) and navigate
                    val shortcut = pendingShortcut
                    pendingShortcut = null
                    showPassphraseDialog = null

                    scope.launch {
                        // Compute and store BIP39 seed from passphrase
                        wallet.setSessionPassphrase(walletMeta.id, passphrase)

                        // Execute pending shortcut action or open wallet
                        if (shortcut != null) {
                            when (shortcut) {
                                QuickShortcut.VIEW_ADDRESSES -> onViewAddresses(walletMeta.id)
                                QuickShortcut.SIGN_PSBT -> onScanPSBT(walletMeta.id)
                                QuickShortcut.CHECK_ADDRESS -> onCheckAddress(walletMeta.id)
                                QuickShortcut.EXPORT -> onExport(walletMeta.id)
                                QuickShortcut.BIP85 -> onBIP85(walletMeta.id)
                                QuickShortcut.SIGN_MESSAGE -> onSignMessage(walletMeta.id)
                            }
                        } else {
                            onWalletClick(walletMeta.id)
                        }
                    }
                },
                calculateFingerprint = { passphrase ->
                    wallet.calculateFingerprint(mnemonic!!, passphrase)
                }
            )
        }
    }

    // Passphrase re-entry dialog for multi-sig wallet keys (sequential dialogs)
    if (multisigKeysNeedingPassphrase.isNotEmpty() && currentKeyPassphraseIndex < multisigKeysNeedingPassphrase.size) {
        val currentKey = multisigKeysNeedingPassphrase[currentKeyPassphraseIndex]
        val walletId = pendingMultisigWalletId

        PassphraseEntryDialog(
            label = currentKey.label,
            originalFingerprint = currentKey.fingerprint,
            onDismiss = {
                // Cancel all - clear state
                multisigKeysNeedingPassphrase = emptyList()
                currentKeyPassphraseIndex = 0
                pendingMultisigWalletId = null
                pendingShortcut = null
            },
            onConfirm = { passphrase, _ ->
                scope.launch {
                    // Store session seed for this key (empty passphrase = derive from base key)
                    wallet.setSessionKeyPassphrase(currentKey.keyId, passphrase)

                    // Move to next key or complete
                    if (currentKeyPassphraseIndex + 1 < multisigKeysNeedingPassphrase.size) {
                        // More keys to process
                        currentKeyPassphraseIndex += 1
                    } else {
                        // All keys processed - execute pending action
                        val shortcut = pendingShortcut

                        // Clear state
                        multisigKeysNeedingPassphrase = emptyList()
                        currentKeyPassphraseIndex = 0
                        pendingMultisigWalletId = null
                        pendingShortcut = null

                        if (walletId != null) {
                            if (shortcut != null) {
                                when (shortcut) {
                                    QuickShortcut.VIEW_ADDRESSES -> onViewAddresses(walletId)
                                    QuickShortcut.SIGN_PSBT -> onScanPSBT(walletId)
                                    QuickShortcut.CHECK_ADDRESS -> onCheckAddress(walletId)
                                    QuickShortcut.EXPORT -> onExport(walletId)
                                    QuickShortcut.BIP85 -> onBIP85(walletId)
                                    QuickShortcut.SIGN_MESSAGE -> onSignMessage(walletId)
                                }
                            } else {
                                onWalletClick(walletId)
                            }
                        }
                    }
                }
            },
            calculateFingerprint = { passphrase ->
                wallet.calculateFingerprint(currentKey.mnemonic, passphrase)
            }
        )
    }
    
    if (errorMessage.isNotEmpty()) {
        AlertDialog(
            onDismissRequest = { errorMessage = "" },
            title = { Text("Error") },
            text = { Text(errorMessage) },
            confirmButton = {
                TextButton(onClick = { errorMessage = "" }) {
                    Text("OK")
                }
            }
        )
    }
    
    // Delete wallet dialogs (reusable component handles both confirmation and password steps)
    DeleteWalletDialogs(
        walletToDelete = walletToDelete,
        wallet = wallet,
        secureStorage = secureStorage,
        onDismiss = { walletToDelete = null },
        onDeleted = { walletToDelete = null }
    )
}

@OptIn(ExperimentalMaterial3Api::class, ExperimentalFoundationApi::class)
@Composable
fun WalletCard(
    modifier: Modifier = Modifier,
    name: String,
    masterFingerprint: String = "",
    isTestnet: Boolean = false,
    isMultisig: Boolean = false,
    elevation: androidx.compose.ui.unit.Dp = 2.dp,
    isEditMode: Boolean = false,
    isExpanded: Boolean = false,
    quickShortcuts: List<QuickShortcut> = QuickShortcut.DEFAULT,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onExpandClick: () -> Unit,
    onShortcutClick: (QuickShortcut) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    ElevatedCard(
        modifier = Modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = onLongPress
            ),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = elevation),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            // Main card content
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.weight(1f)
                ) {
                    // Drag Handle - only visible in edit mode
                    if (isEditMode) {
                        Icon(
                            painter = painterResource(R.drawable.ic_drag_handle),
                            contentDescription = "Drag to reorder",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                            modifier = modifier
                        )
                    }

                    Column {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Text(
                                text = name,
                                style = MaterialTheme.typography.titleLarge,
                                fontWeight = FontWeight.Bold,
                                // In edit mode, use primary color to hint it's editable
                                color = if (isEditMode) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
                            )
                            // Testnet badge
                            if (isTestnet) {
                                Surface(
                                    color = MaterialTheme.colorScheme.secondaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Testnet",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                            // Multi-Sig badge
                            if (isMultisig) {
                                Surface(
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Multi-Sig",
                                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                        style = MaterialTheme.typography.labelSmall,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }
                        }
                        if (masterFingerprint.isNotEmpty() && !isEditMode && !isMultisig) {
                            Text(
                                text = masterFingerprint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                // Chevron button - hidden in edit mode
                if (!isEditMode) {
                    IconButton(onClick = onExpandClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_arrow_down),
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier
                                .size(28.dp)
                                .rotate(chevronRotation)
                        )
                    }
                }
            }

            // Expandable quick actions - dynamic based on user preferences
            AnimatedVisibility(
                visible = isExpanded,
                enter = expandVertically(),
                exit = shrinkVertically()
            ) {
                Column {
                    HorizontalDivider(
                        modifier = Modifier.padding(horizontal = 16.dp),
                        color = MaterialTheme.colorScheme.outlineVariant
                    )

                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 12.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly
                    ) {
                        // Render shortcuts dynamically from preferences
                        quickShortcuts.forEach { shortcut ->
                            QuickActionButton(
                                icon = shortcut.iconRes,
                                label = shortcut.label,
                                onClick = { onShortcutClick(shortcut) }
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun QuickActionButton(
    icon: Int,
    label: String,
    onClick: () -> Unit
) {
    FilledTonalButton(
        onClick = onClick,
        modifier = Modifier
            .width(100.dp)
            .height(60.dp),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 8.dp)
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Icon(
                painter = painterResource(icon),
                contentDescription = null,
                modifier = Modifier.size(24.dp)
            )
            Text(
                text = label,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1
            )
        }
    }
}

/**
 * Stateless wallet card - shown at top of wallet list when a stateless wallet is active.
 * Has a distinct appearance with "Stateless" badge in errorContainer color.
 */
@Composable
fun StatelessWalletCard(
    fingerprint: String,
    derivationPath: String,
    onClick: () -> Unit
) {
    val isTestnet = DerivationPaths.isTestnet(derivationPath)
    val walletType = when (DerivationPaths.getPurpose(derivationPath)) {
        86 -> "Taproot"
        84 -> "Native SegWit"
        49 -> "Nested SegWit"
        44 -> "Legacy"
        else -> "Unknown"
    }
    
    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
        elevation = CardDefaults.elevatedCardElevation(defaultElevation = 2.dp),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(20.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Stateless Wallet",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    // Stateless badge
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        contentColor = MaterialTheme.colorScheme.onErrorContainer,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "Stateless",
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    // Testnet badge
                    if (isTestnet) {
                        Surface(
                            color = MaterialTheme.colorScheme.secondaryContainer,
                            contentColor = MaterialTheme.colorScheme.onSecondaryContainer,
                            shape = RoundedCornerShape(4.dp)
                        ) {
                            Text(
                                text = "Testnet",
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                style = MaterialTheme.typography.labelSmall,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.primary,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = "• $walletType",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
            
            Icon(
                painter = painterResource(R.drawable.ic_chevron_right),
                contentDescription = "Open",
                tint = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}