package com.gorunjinian.metrovault.feature.wallet.list

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
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
import com.gorunjinian.metrovault.core.ui.dialogs.RenameWalletDialog
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.model.DerivationPaths

@Composable
fun WalletsListContent(
    wallet: Wallet, // Renamed from Wallet to wallet
    secureStorage: SecureStorage,
    autoExpandSingleWallet: Boolean = false,
    onWalletClick: (String) -> Unit,
    onViewAddresses: (String) -> Unit,
    onScanPSBT: (String) -> Unit,
    onCheckAddress: (String) -> Unit
) {
    val wallets by wallet.wallets.collectAsState()
    var showRenameDialog by remember { mutableStateOf<Pair<String, String>?>(null) }
    var errorMessage by remember { mutableStateOf("") }
    var expandedWalletId by remember { mutableStateOf<String?>(null) }
    val scope = rememberCoroutineScope()

    // Auto-expand the wallet card when there's only one wallet (if preference is enabled)
    LaunchedEffect(wallets, autoExpandSingleWallet) {
        if (autoExpandSingleWallet && wallets.size == 1) {
            expandedWalletId = wallets.first().id
        }
    }

    // Drag and Drop State
    var draggingItemIndex by remember { mutableStateOf<Int?>(null) }
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
            userScrollEnabled = draggingItemIndex == null
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
                val offset by remember { derivedStateOf { if (isDragging) draggingItemOffset else 0f } }
                val zIndex by remember { derivedStateOf { if (isDragging) 1f else 0f } }
                val scale by remember { derivedStateOf { if (isDragging) 1.05f else 1f } }
                val shadow by remember { derivedStateOf { if (isDragging) 8.dp else 2.dp } }

                Box(
                    modifier = Modifier
                        // Only apply graphicsLayer when dragging to reduce GPU overhead
                        .then(
                            if (isDragging) Modifier.graphicsLayer {
                                translationY = offset
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
                    // Compute wallet type once and cache it
                    val walletType = remember(walletItem.derivationPath) {
                        when (walletItem.derivationPath) {
                            DerivationPaths.TAPROOT -> "Taproot"
                            DerivationPaths.NATIVE_SEGWIT -> "Native SegWit"
                            DerivationPaths.NESTED_SEGWIT -> "Nested SegWit"
                            DerivationPaths.LEGACY -> "Legacy"
                            else -> "Unknown"
                        }
                    }

                    WalletCard(
                        walletId = walletItem.id,
                        name = walletItem.name,
                        type = walletType,
                        masterFingerprint = walletItem.masterFingerprint,
                        elevation = shadow,
                        isExpanded = expandedWalletId == walletItem.id,
                        onClick = { if (draggingItemIndex == null) onWalletClick(walletItem.id) },
                        onEditClick = { showRenameDialog = walletItem.id to walletItem.name },
                        onExpandClick = {
                            expandedWalletId = if (expandedWalletId == walletItem.id) null else walletItem.id
                        },
                        onViewAddresses = { onViewAddresses(walletItem.id) },
                        onScanPSBT = { onScanPSBT(walletItem.id) },
                        onCheckAddress = { onCheckAddress(walletItem.id) },
                        dragHandleModifier = Modifier.pointerInput(walletItem.id) {
                            detectDragGesturesAfterLongPress(
                                onDragStart = {
                                    draggingItemIndex = currentIndex
                                    draggingItemOffset = 0f
                                },
                                onDrag = { change, dragAmount ->
                                    change.consume()
                                    draggingItemOffset += dragAmount.y

                                    // Use draggingItemIndex instead of captured index for swap calculations
                                    val dragIdx = draggingItemIndex ?: return@detectDragGesturesAfterLongPress

                                    // Calculate swap with larger threshold for smoother feel
                                    val currentOffset = draggingItemOffset
                                    val height = itemHeight.intValue.toFloat() + 12.dp.toPx() // Item height + spacing
                                    val swapThreshold = height * 0.6f // Use 60% threshold for smoother swapping

                                    // Swap down
                                    if (currentOffset > swapThreshold) {
                                        val nextIndex = dragIdx + 1
                                        if (nextIndex < currentWallets.size) {
                                            wallet.swapWallets(dragIdx, nextIndex)
                                            draggingItemIndex = nextIndex
                                            draggingItemOffset -= height
                                        }
                                    }
                                    // Swap up
                                    else if (currentOffset < -swapThreshold) {
                                        val prevIndex = dragIdx - 1
                                        if (prevIndex >= 0) {
                                            wallet.swapWallets(dragIdx, prevIndex)
                                            draggingItemIndex = prevIndex
                                            draggingItemOffset += height
                                        }
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
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun WalletCard(
    walletId: String,
    name: String,
    type: String,
    masterFingerprint: String = "",
    elevation: androidx.compose.ui.unit.Dp = 2.dp,
    isExpanded: Boolean = false,
    onClick: () -> Unit,
    onEditClick: () -> Unit,
    onExpandClick: () -> Unit,
    onViewAddresses: () -> Unit,
    onScanPSBT: () -> Unit,
    onCheckAddress: () -> Unit,
    dragHandleModifier: Modifier = Modifier
) {
    val chevronRotation by animateFloatAsState(
        targetValue = if (isExpanded) 180f else 0f,
        label = "chevronRotation"
    )

    ElevatedCard(
        onClick = onClick,
        modifier = Modifier.fillMaxWidth(),
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
                    // Drag Handle
                    Icon(
                        painter = painterResource(R.drawable.ic_drag_handle),
                        contentDescription = "Drag to reorder",
                        tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                        modifier = dragHandleModifier
                    )

                    Column {
                        Text(
                            text = name,
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold
                        )
                        if (masterFingerprint.isNotEmpty()) {
                            Text(
                                text = masterFingerprint,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.primary,
                                fontWeight = FontWeight.Medium
                            )
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconButton(onClick = onEditClick) {
                        Icon(
                            painter = painterResource(R.drawable.ic_edit),
                            contentDescription = "Edit Name",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }

                    IconButton(onClick = onExpandClick) {
                        Icon(
                            imageVector = Icons.Default.KeyboardArrowDown,
                            contentDescription = if (isExpanded) "Collapse" else "Expand",
                            tint = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.rotate(chevronRotation)
                        )
                    }
                }
            }

            // Expandable quick actions
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
                        // View Addresses Button
                        QuickActionButton(
                            icon = R.drawable.ic_qr_code_2,
                            label = "Addresses",
                            onClick = onViewAddresses
                        )

                        // Sign PSBT Button
                        QuickActionButton(
                            icon = R.drawable.ic_qr_code_scanner,
                            label = "Sign PSBT",
                            onClick = onScanPSBT
                        )

                        // Check Address Button
                        QuickActionButton(
                            icon = R.drawable.ic_search,
                            label = "Check",
                            onClick = onCheckAddress
                        )
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
        modifier = Modifier.height(60.dp),
        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp)
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