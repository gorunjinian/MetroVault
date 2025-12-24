package com.gorunjinian.metrovault.feature.wallet.list

import android.view.HapticFeedbackConstants
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.painterResource
import androidx.compose.runtime.rememberUpdatedState
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.DeleteWalletDialogs
import com.gorunjinian.metrovault.core.ui.dialogs.PassphraseEntryDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RenameWalletDialog
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.QuickShortcut
import com.gorunjinian.metrovault.data.model.WalletMetadata

@Suppress("AssignedValueIsNeverRead")
@Composable
fun WalletsListContent(
    wallet: Wallet,
    secureStorage: SecureStorage,
    isEditMode: Boolean = false,
    autoExpandSingleWallet: Boolean = false,
    quickShortcuts: List<QuickShortcut> = QuickShortcut.DEFAULT,
    onWalletClick: (String) -> Unit,
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
    
    // Delete wallet dialog state (single state drives the reusable component)
    var walletToDelete by remember { mutableStateOf<WalletMetadata?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-expand the wallet card when there's only one wallet (if preference is enabled)
    LaunchedEffect(wallets, autoExpandSingleWallet) {
        if (autoExpandSingleWallet && wallets.size == 1) {
            expandedWalletId = wallets.first().id
        }
    }

    // Drag and Drop State
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
    var dragStartIndex by remember { mutableIntStateOf(0) } // Track where drag started
    var draggingItemOffset by remember { mutableFloatStateOf(0f) }
    val itemHeight = remember { mutableIntStateOf(0) }

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

        LazyColumn(
            state = lazyListState,
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
            // Disable user scroll during drag for smoother experience
            userScrollEnabled = draggingItemIndex == null && !isEditMode
        ) {
            itemsIndexed(
                items = wallets,
                key = { _, walletItem -> walletItem.id }
            ) { index, walletItem ->
                // Use rememberUpdatedState to always have the current index in callbacks
                val currentIndex by rememberUpdatedState(index)
                val currentWallets by rememberUpdatedState(wallets)

                // Use derivedStateOf for drag states to reduce recompositions
                val isDragging by remember {
                    derivedStateOf {
                        walletItem.id == draggingItemIndex?.let { wallets.getOrNull(it)?.id }
                    }
                }
                
                // Calculate visual offset: total drag minus the layout displacement from swaps
                // This keeps the card exactly under the finger regardless of swaps
                val slotHeight = itemHeight.intValue.toFloat() + with(LocalDensity.current) { 12.dp.toPx() }
                val visualOffset by remember(slotHeight) { 
                    derivedStateOf { 
                        if (isDragging) {
                            val currentIdx = draggingItemIndex ?: dragStartIndex
                            val layoutDisplacement = (currentIdx - dragStartIndex) * slotHeight
                            draggingItemOffset - layoutDisplacement
                        } else 0f
                    } 
                }
                
                val zIndex by remember { derivedStateOf { if (isDragging) 1f else 0f } }
                // In edit mode, cards are 97% scale (subtle shrink); when dragging, bump slightly
                val baseScale = if (isEditMode) 0.97f else 1f
                val scale by remember(isEditMode) { derivedStateOf { if (isDragging) baseScale * 1.05f else baseScale } }
                val shadow by remember { derivedStateOf { if (isDragging) 8.dp else 2.dp } }

                Box(
                    modifier = Modifier
                        // Apply graphicsLayer in edit mode for scaling, or when dragging for translation
                        .then(
                            if (isEditMode || isDragging) Modifier.graphicsLayer {
                                translationY = visualOffset
                                scaleX = scale
                                scaleY = scale
                            } else Modifier
                        )
                        .zIndex(zIndex)
                        .onGloballyPositioned { coordinates ->
                            if (itemHeight.intValue == 0) {
                                itemHeight.intValue = coordinates.size.height
                            }
                        }
                ) {
                    // Compute wallet type and testnet status
                    val isWalletTestnet = remember(walletItem.derivationPath) {
                        DerivationPaths.isTestnet(walletItem.derivationPath)
                    }
                    val walletType = remember(walletItem.derivationPath) {
                        when (DerivationPaths.getPurpose(walletItem.derivationPath)) {
                            86 -> "Taproot"
                            84 -> "Native SegWit"
                            49 -> "Nested SegWit"
                            44 -> "Legacy"
                            else -> "Unknown"
                        }
                    }

                    WalletCard(
                        walletId = walletItem.id,
                        name = walletItem.name,
                        type = walletType,
                        masterFingerprint = walletItem.masterFingerprint,
                        isTestnet = isWalletTestnet,
                        elevation = shadow,
                        isEditMode = isEditMode,
                        isExpanded = if (isEditMode) false else expandedWalletId == walletItem.id,
                        quickShortcuts = quickShortcuts,
                        onClick = { 
                            if (draggingItemIndex == null) {
                                if (isEditMode) {
                                    // In edit mode, tapping card opens rename dialog
                                    showRenameDialog = walletItem.id to walletItem.name
                                } else {
                                    // Normal mode: navigate to wallet details
                                    if (wallet.needsPassphraseInput(walletItem.id)) {
                                        showPassphraseDialog = walletItem
                                    } else {
                                        onWalletClick(walletItem.id)
                                    }
                                }
                            }
                        },
                        onLongPress = {
                            // Long press to delete wallet (only when not in edit mode)
                            if (!isEditMode && draggingItemIndex == null) {
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
                            if (wallet.needsPassphraseInput(walletItem.id)) {
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
                        // In edit mode, use immediate drag; otherwise no drag handle interaction
                        modifier = if (isEditMode) {
                            Modifier.pointerInput(walletItem.id) {
                                detectDragGestures(
                                    onDragStart = {
                                        draggingItemIndex = currentIndex
                                        dragStartIndex = currentIndex
                                        draggingItemOffset = 0f
                                    },
                                    onDrag = { change, dragAmount ->
                                        change.consume()
                                        draggingItemOffset += dragAmount.y

                                        val dragIdx = draggingItemIndex ?: return@detectDragGestures
                                        
                                        // Calculate item slot height
                                        val slotHeight = itemHeight.intValue.toFloat() + 12.dp.toPx()
                                        
                                        // Calculate visual offset (offset from current layout position)
                                        val layoutDisplacement = (dragIdx - dragStartIndex) * slotHeight
                                        val currentVisualOffset = draggingItemOffset - layoutDisplacement
                                        
                                        // Swap when visual offset exceeds threshold (60% of slot height)
                                        val swapThreshold = slotHeight * 0.6f
                                        
                                        // Swap down
                                        if (currentVisualOffset > swapThreshold && dragIdx < currentWallets.size - 1) {
                                            wallet.swapWallets(dragIdx, dragIdx + 1)
                                            draggingItemIndex = dragIdx + 1
                                        }
                                        // Swap up
                                        else if (currentVisualOffset < -swapThreshold && dragIdx > 0) {
                                            wallet.swapWallets(dragIdx, dragIdx - 1)
                                            draggingItemIndex = dragIdx - 1
                                        }
                                    },
                                    onDragEnd = {
                                        draggingItemIndex = null
                                        draggingItemOffset = 0f

                                        // Save new order
                                        scope.launch {
                                            wallet.saveWalletListOrder()
                                        }
                                    },
                                    onDragCancel = {
                                        draggingItemIndex = null
                                        draggingItemOffset = 0f
                                    }
                                )
                            }
                        } else {
                            Modifier
                        }
                    )
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

    // Passphrase re-entry dialog for wallets with unsaved passphrase
    if (showPassphraseDialog != null) {
        val walletMeta = showPassphraseDialog!!
        var mnemonic by remember { mutableStateOf<List<String>?>(null) }
        
        // Load mnemonic when dialog opens
        LaunchedEffect(walletMeta.id) {
            mnemonic = wallet.getMnemonicForWallet(walletMeta.id)
        }
        
        if (mnemonic != null) {
            PassphraseEntryDialog(
                walletName = walletMeta.name,
                originalFingerprint = walletMeta.masterFingerprint,
                onDismiss = { 
                    showPassphraseDialog = null
                    pendingShortcut = null
                },
                onConfirm = { passphrase, calculatedFingerprint ->
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
    walletId: String,
    name: String,
    type: String,
    masterFingerprint: String = "",
    isTestnet: Boolean = false,
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
                                    color = MaterialTheme.colorScheme.tertiaryContainer,
                                    contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
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
                        if (masterFingerprint.isNotEmpty() && !isEditMode) {
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
                            imageVector = Icons.Default.KeyboardArrowDown,
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