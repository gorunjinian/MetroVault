package com.gorunjinian.metrovault.feature.wallet.list

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.DeleteWalletDialogs
import com.gorunjinian.metrovault.core.ui.dialogs.InfoDialog
import com.gorunjinian.metrovault.core.ui.dialogs.PassphraseEntryDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RenameDialog
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.QuickShortcut
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletProfile
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.filter
import sh.calvin.reorderable.ReorderableItem
import sh.calvin.reorderable.rememberReorderableLazyListState

/** Smallest wallet count that shows the "N wallets" line under the last card. */
private const val WALLET_COUNT_FOOTER_THRESHOLD = 5

/** Footer key; wallet ids are UUIDs, so it can't collide with a card's key. */
private const val WALLET_COUNT_FOOTER_KEY = "wallet_count_footer"

/**
 * The vault's wallet list. The number of wallets is unbounded, so the list scrolls in every mode,
 * edit mode included. [contentPadding] is applied inside the list so cards can scroll out from
 * under any overlay at the bottom (the Home screen's floating nav) instead of being cut off by it.
 */
@Composable
fun WalletsListContent(
    wallet: Wallet,
    secureStorage: SecureStorage,
    isEditMode: Boolean = false,
    autoExpandSingleWallet: Boolean = false,
    quickShortcuts: List<QuickShortcut> = QuickShortcut.DEFAULT,
    contentPadding: PaddingValues = PaddingValues(horizontal = 16.dp, vertical = 8.dp),
    onWalletClick: (String) -> Unit,
    onShortcut: (walletId: String, shortcut: QuickShortcut) -> Unit
) {
    val wallets by wallet.wallets.collectAsState()
    val view = LocalView.current
    var showRenameDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var showPassphraseDialog by remember { mutableStateOf<WalletMetadata?>(null) }
    // Track pending shortcut action when passphrase is required
    var pendingShortcut by remember { mutableStateOf<QuickShortcut?>(null) }
    // Passphrase shortcut opened the dialog for a wallet that doesn't normally ask for one
    var isAddedPassphrase by remember { mutableStateOf(false) }
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
        // Empty State, centered in the area the bottom overlay leaves visible
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(contentPadding),
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
        // The padding doubles as the auto-scroll threshold, so dragging a card toward the
        // floating nav scrolls the list before the card disappears behind it
        val reorderableState = rememberReorderableLazyListState(
            lazyListState = lazyListState,
            scrollThresholdPadding = contentPadding
        ) { from, to ->
            val fromIndex = wallets.indexOfFirst { it.id == from.key }
            val toIndex = wallets.indexOfFirst { it.id == to.key }
            if (fromIndex != -1 && toIndex != -1) {
                wallet.swapWallets(fromIndex, toIndex)
            }
        }

        // Scrolling stays enabled in edit mode: with any number of wallets, cards below the fold
        // must stay reachable to rename or reorder. A drag on the handle is consumed by the
        // reorderable modifier, so it never fights the list's own scroll gesture.
        LazyColumn(
            state = lazyListState,
            modifier = Modifier.fillMaxSize(),
            contentPadding = contentPadding,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            items(
                items = wallets,
                key = { walletItem -> walletItem.id }
            ) { walletItem ->
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
                            isSilentPayment = walletItem.isSilentPayment,
                            elevation = shadow,
                            isEditMode = isEditMode,
                            isExpanded = if (isEditMode) false else expandedWalletId == walletItem.id,
                            quickShortcuts = QuickShortcut.forProfile(quickShortcuts, WalletProfile.of(walletItem)),
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
                            onExpandSettled = {
                                // Bring the quick actions out from under the floating nav
                                scope.launch { lazyListState.revealItem(walletItem.id) }
                            },
                            onShortcutClick = { shortcut ->
                                // Passphrase shortcut always prompts, then opens Wallet Details. The
                                // entered passphrase is applied to the stored mnemonic, replacing any
                                // saved one for this visit (Home wipes it again).
                                // Other shortcuts check if wallet needs passphrase re-entry first.
                                if (shortcut == QuickShortcut.PASSPHRASE) {
                                    isAddedPassphrase = !walletItem.hasPassphrase
                                    showPassphraseDialog = walletItem
                                } else if (walletItem.isMultisig) {
                                    // Multi-sig: check if any local keys need passphrase
                                    scope.launch {
                                        val keysNeeding = wallet.getKeysNeedingPassphrase(walletItem.id)
                                        if (keysNeeding.isNotEmpty()) {
                                            pendingShortcut = shortcut
                                            multisigKeysNeedingPassphrase = keysNeeding
                                            currentKeyPassphraseIndex = 0
                                            pendingMultisigWalletId = walletItem.id
                                        } else {
                                            onShortcut(walletItem.id, shortcut)
                                        }
                                    }
                                } else if (wallet.needsPassphraseInput(walletItem.id)) {
                                    pendingShortcut = shortcut
                                    showPassphraseDialog = walletItem
                                } else {
                                    onShortcut(walletItem.id, shortcut)
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

            // Wallet count under the last card, once the list is long enough that counting the
            // cards is a chore. Not a ReorderableItem, so cards can't be dragged past it.
            if (wallets.size >= WALLET_COUNT_FOOTER_THRESHOLD) {
                item(key = WALLET_COUNT_FOOTER_KEY) {
                    Text(
                        text = "${wallets.size} wallets",
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        style = MaterialTheme.typography.labelLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }

    if (showRenameDialog != null) {
        val (walletId, currentName) = showRenameDialog!!
        RenameDialog(
            title = "Rename Wallet",
            label = "Wallet Name",
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

    // Passphrase dialog for single-sig wallets with unsaved passphrase, or opened by the Passphrase shortcut
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
                    isAddedPassphrase = false
                },
                onConfirm = { passphrase, _ ->
                    // Store session seed (computed from passphrase) and navigate
                    val shortcut = pendingShortcut
                    pendingShortcut = null
                    showPassphraseDialog = null
                    isAddedPassphrase = false

                    scope.launch {
                        // Compute and store BIP39 seed from passphrase
                        wallet.setSessionPassphrase(walletMeta.id, passphrase)

                        // Execute pending shortcut action or open wallet
                        if (shortcut != null) {
                            onShortcut(walletMeta.id, shortcut)
                        } else {
                            onWalletClick(walletMeta.id)
                        }
                    }
                },
                calculateFingerprint = { passphrase ->
                    wallet.calculateFingerprint(mnemonic!!, passphrase)
                },
                note = when {
                    isAddedPassphrase -> "Applies until you leave this wallet. Nothing is saved."
                    // A wallet that saves its passphrase is only asked for it when an older
                    // version stored its key without it (see PassphraseManager.needsPassphraseInput)
                    !walletMeta.hasPassphrase ->
                        "An older version saved this wallet without its passphrase. Enter it to " +
                            "continue; funds sent to its earlier addresses open with an empty passphrase."
                    else -> null
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
                                onShortcut(walletId, shortcut)
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
        InfoDialog(title = "Error", onDismiss = { errorMessage = "" }) {
            Text(errorMessage)
        }
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
    isSilentPayment: Boolean = false,
    elevation: androidx.compose.ui.unit.Dp = 2.dp,
    isEditMode: Boolean = false,
    isExpanded: Boolean = false,
    quickShortcuts: List<QuickShortcut> = QuickShortcut.DEFAULT,
    onClick: () -> Unit,
    onLongPress: () -> Unit = {},
    onExpandClick: () -> Unit,
    /** Called once each time the quick actions finish expanding (not when the card is composed already expanded). */
    onExpandSettled: () -> Unit = {},
    onShortcutClick: (QuickShortcut) -> Unit
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    // Drives the quick actions so the end of the expand animation is observable. A card that
    // re-enters composition already expanded (scrolled back into view) starts idle, and drop(1)
    // skips that initial state, so only a real expansion reports back and scrolls the list.
    val expandState = remember { MutableTransitionState(isExpanded) }
    expandState.targetState = isExpanded
    val currentOnExpandSettled by rememberUpdatedState(onExpandSettled)
    LaunchedEffect(expandState) {
        snapshotFlow { expandState.isIdle && expandState.currentState }
            .drop(1)
            .filter { it }
            .collect { currentOnExpandSettled() }
    }

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
                            // Silent Payments badge
                            if (isSilentPayment) {
                                Surface(
                                    color = MaterialTheme.colorScheme.primaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    shape = RoundedCornerShape(4.dp)
                                ) {
                                    Text(
                                        text = "Silent Payments",
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
                visibleState = expandState,
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
                                // SP wallets have a single receive address, so use singular label
                                label = if (isSilentPayment && shortcut == QuickShortcut.VIEW_ADDRESSES) {
                                    "Address"
                                } else {
                                    shortcut.label
                                },
                                onClick = { onShortcutClick(shortcut) }
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Scrolls just far enough that the whole item with [key] sits above the bottom content padding,
 * without pushing its top under the top edge. Does nothing when the item isn't laid out or
 * already fits.
 *
 * Item offsets are measured from the end of the top content padding, and [viewportEndOffset]
 * is likewise reduced by it, so the visible area runs from 0 to viewportEnd - afterContentPadding.
 */
private suspend fun LazyListState.revealItem(key: Any) {
    val info = layoutInfo
    val item = info.visibleItemsInfo.firstOrNull { it.key == key } ?: return
    val visibleEnd = info.viewportEndOffset - info.afterContentPadding
    val overflow = item.offset + item.size - visibleEnd
    if (overflow > 0 && item.offset > 0) {
        // A card taller than the visible area keeps its header in view
        animateScrollBy(minOf(overflow, item.offset).toFloat())
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