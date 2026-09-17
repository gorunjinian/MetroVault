package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.ui.util.AddressFormatter
import com.gorunjinian.metrovault.data.model.AddressStartIndex
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * The loaded slice of one chain's address list. [addresses] always covers the contiguous index
 * range `[loadedStart, loadedStart + addresses.size)`; the screen grows it in either direction
 * one [AddressStartIndex.BATCH_SIZE] batch at a time.
 */
private data class AddressWindow(
    val loadedStart: Int = 0,
    val addresses: List<BitcoinAddress> = emptyList()
) {
    val loadedEnd: Int get() = loadedStart + addresses.size
    val canLoadEarlier: Boolean get() = loadedStart > 0
    val isLoaded: Boolean get() = addresses.isNotEmpty()

    /** Tab-label suffix: the inclusive index range currently loaded, e.g. "40-79". */
    val rangeLabel: String get() = if (isLoaded) "$loadedStart-${loadedEnd - 1}" else "…"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    initialTabIndex: Int = 0,
    onBack: () -> Unit,
    onAddressSelected: (address: String, index: Int, isChange: Boolean) -> Unit,
    onViewSilentPaymentExport: () -> Unit,
) {
    // The SP tab is only exposed here when the user has opted in via Advanced Settings; SP-flagged
    // wallets never reach this screen (they're routed to SPAddressScreen), so this gate is purely
    // about the "derive an SP address from an existing regular wallet" discovery surface.
    val silentPaymentsEnabled by userPreferencesRepository.silentPaymentsEnabled.collectAsState()

    // Multisig wallets have no silent-payment address, so the SP tab is hidden entirely for them.
    val isMultisig = remember { wallet.isActiveMultisig() }
    val showSilentPaymentsTab = silentPaymentsEnabled && !isMultisig

    var selectedTabIndex by remember { mutableIntStateOf(initialTabIndex) }

    // Sync selectedTabIndex when initialTabIndex changes (e.g., returning from back stack)
    LaunchedEffect(initialTabIndex) {
        selectedTabIndex = initialTabIndex
    }

    // If the SP tab becomes unavailable (disabled, or multisig) while it's selected, snap back to Receive.
    LaunchedEffect(showSilentPaymentsTab) {
        if (!showSilentPaymentsTab && selectedTabIndex == 2) {
            selectedTabIndex = 0
        }
    }

    // Configured start index per chain. Seeded from wallet metadata; for stateless wallets the
    // persist call is a no-op and the values live only for this visit.
    val initialStarts = remember { wallet.getAddressStartIndices() }
    var receiveStartIndex by remember { mutableIntStateOf(initialStarts.first) }
    var changeStartIndex by remember { mutableIntStateOf(initialStarts.second) }

    // Separate windows for receive and change addresses
    var receiveWindow by remember { mutableStateOf(AddressWindow()) }
    var changeWindow by remember { mutableStateOf(AddressWindow()) }

    var showStartIndexDialog by remember { mutableStateOf(false) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Show FAB when scrolled down, but only on receive/change tabs (silent-payment tab is short)
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 && selectedTabIndex != 2 }
    }

    fun loadWindow(startIndex: Int, isChange: Boolean): AddressWindow {
        val batchStart = AddressStartIndex.batchStart(startIndex)
        val addresses = wallet.generateAddresses(
            count = AddressStartIndex.BATCH_SIZE,
            offset = batchStart,
            isChange = isChange
        ) ?: emptyList()
        return AddressWindow(loadedStart = batchStart, addresses = addresses)
    }

    // (Re)load each chain's window whenever its configured start changes. This also performs the
    // initial load, and drops any batches the user had walked to before changing the setting.
    // The scroll reset is *requested* rather than performed: a plain scrollToItem would act on the
    // layout that exists now, before the new items are composed, and the keyed items would then
    // re-anchor the viewport to wherever the previously visible key moved. requestScrollToItem
    // applies on the next measure pass, i.e. against the new window.
    LaunchedEffect(receiveStartIndex) {
        receiveWindow = loadWindow(receiveStartIndex, isChange = false)
        if (selectedTabIndex == 0) listState.requestScrollToItem(0)
    }
    LaunchedEffect(changeStartIndex) {
        changeWindow = loadWindow(changeStartIndex, isChange = true)
        if (selectedTabIndex == 1) listState.requestScrollToItem(0)
    }

    fun AddressWindow.withMore(isChange: Boolean): AddressWindow {
        val result = wallet.generateAddresses(
            count = AddressStartIndex.BATCH_SIZE,
            offset = loadedEnd,
            isChange = isChange
        ) ?: return this
        return copy(addresses = addresses + result)
    }

    fun AddressWindow.withEarlier(isChange: Boolean): AddressWindow {
        if (!canLoadEarlier) return this
        val newStart = (loadedStart - AddressStartIndex.BATCH_SIZE).coerceAtLeast(0)
        val result = wallet.generateAddresses(
            count = loadedStart - newStart,
            offset = newStart,
            isChange = isChange
        ) ?: return this
        return copy(loadedStart = newStart, addresses = result + addresses)
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Addresses",
                onBack = onBack,
                actions = {
                    IconButton(onClick = { showStartIndexDialog = true }) {
                        Icon(
                            painter = painterResource(R.drawable.ic_settings),
                            contentDescription = "Address list settings"
                        )
                    }
                }
            )
        },
        floatingActionButton = {
            if (showScrollToTop) {
                FloatingActionButton(
                    onClick = {
                        coroutineScope.launch {
                            listState.animateScrollToItem(0)
                        }
                    }
                ) {
                    Icon(painter = painterResource(R.drawable.ic_keyboard_arrow_up), contentDescription = "Scroll to top")
                }
            }
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            SecondaryTabRow(
                selectedTabIndex = selectedTabIndex,
                containerColor = Color.Transparent
            ) {
                Tab(
                    selected = selectedTabIndex == 0,
                    onClick = { selectedTabIndex = 0 },
                    text = { Text("Receive (${receiveWindow.rangeLabel})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Change (${changeWindow.rangeLabel})") }
                )
                if (showSilentPaymentsTab) {
                    Tab(
                        selected = selectedTabIndex == 2,
                        onClick = { selectedTabIndex = 2 },
                        text = { Text("Silent Payments") }
                    )
                }
            }

            when (selectedTabIndex) {
                2 -> {
                    SilentPaymentAddressContent(
                        wallet = wallet,
                        userPreferencesRepository = userPreferencesRepository,
                        onExport = onViewSilentPaymentExport
                    )
                }
                else -> {
                    val isChangeTab = selectedTabIndex == 1
                    val window = if (isChangeTab) changeWindow else receiveWindow
                    val loadMore: () -> Unit = {
                        if (isChangeTab) changeWindow = changeWindow.withMore(true)
                        else receiveWindow = receiveWindow.withMore(false)
                    }
                    val loadEarlier: () -> Unit = {
                        if (isChangeTab) changeWindow = changeWindow.withEarlier(true)
                        else receiveWindow = receiveWindow.withEarlier(false)
                    }

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // Only present when the window starts past index 0; lets the user walk
                        // back toward the first batch without touching the configured start.
                        if (window.canLoadEarlier) {
                            item(key = "show_earlier") {
                                OutlinedButton(
                                    onClick = loadEarlier,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text("Show Earlier")
                                }
                            }
                        }

                        items(window.addresses, key = { it.index }) { addressInfo ->
                            ElevatedCard(
                                onClick = {
                                    onAddressSelected(addressInfo.address, addressInfo.index, addressInfo.isChange)
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(16.dp),
                                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                                ) {
                                    Surface(
                                        shape = MaterialTheme.shapes.small,
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        modifier = Modifier.align(Alignment.CenterVertically)
                                    ) {
                                        Text(
                                            text = "${addressInfo.index}",
                                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer
                                        )
                                    }

                                    Column(
                                        modifier = Modifier.weight(1f)
                                    ) {
                                        Text(
                                            text = AddressFormatter.formatTruncatedAddress(addressInfo.address),
                                            style = MaterialTheme.typography.bodyMedium
                                        )
                                    }
                                }
                            }
                        }

                        // Not shown until the window is loaded: otherwise it is the sole item on
                        // first composition, and when the addresses arrive above it the keyed
                        // list would keep it in view and open the screen scrolled to the bottom.
                        if (window.isLoaded) {
                            item(key = "show_more") {
                                Button(
                                    onClick = loadMore,
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp)
                                ) {
                                    Text("Show More")
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    if (showStartIndexDialog) {
        AddressStartIndexDialog(
            initialReceive = receiveStartIndex,
            initialChange = changeStartIndex,
            initialTab = if (selectedTabIndex == 1) 1 else 0,
            onDismiss = { showStartIndexDialog = false },
            onApply = { receive, change ->
                showStartIndexDialog = false
                receiveStartIndex = receive
                changeStartIndex = change
                // Returns false for stateless wallets, where the values above stay session-only.
                coroutineScope.launch { wallet.setAddressStartIndices(receive, change) }
            }
        )
    }
}

/**
 * Number-input dialog for the per-chain start index. A Receive/Change toggle picks which chain the
 * single field edits; both values are kept until Apply commits them together. Input is normalised
 * via [AddressStartIndex.normalize] so anything under one batch means "start from the beginning".
 */
@Composable
private fun AddressStartIndexDialog(
    initialReceive: Int,
    initialChange: Int,
    initialTab: Int,
    onDismiss: () -> Unit,
    onApply: (receive: Int, change: Int) -> Unit
) {
    var tab by remember { mutableIntStateOf(initialTab) }
    var receiveInput by remember { mutableStateOf(initialReceive.takeIf { it > 0 }?.toString() ?: "") }
    var changeInput by remember { mutableStateOf(initialChange.takeIf { it > 0 }?.toString() ?: "") }

    val maxDigits = AddressStartIndex.MAX.toString().length
    val focusRequester = remember { FocusRequester() }

    // Auto-focus the field when the dialog opens. Focus then stays there while the user switches
    // chain: the toggle segments are clickables, which cannot take focus under touch input, so the
    // keyboard never drops and re-animates on a switch.
    LaunchedEffect(Unit) {
        focusRequester.requestFocus()
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Start Index") },
        text = {
            Column {
                SegmentedToggle(
                    options = listOf("Receive", "Change"),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    "Open the ${if (tab == 1) "change" else "receive"} list at the batch of " +
                        "${AddressStartIndex.BATCH_SIZE} addresses containing this index. " +
                        "Leave empty or enter less than ${AddressStartIndex.BATCH_SIZE} to start from 0."
                )
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (tab == 1) changeInput else receiveInput,
                    onValueChange = { raw ->
                        val digits = raw.filter { c -> c.isDigit() }.take(maxDigits)
                        if (tab == 1) changeInput = digits else receiveInput = digits
                    },
                    label = { Text("Address Index") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true,
                    modifier = Modifier
                        .fillMaxWidth()
                        .focusRequester(focusRequester)
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = {
                    onApply(
                        AddressStartIndex.normalize(receiveInput.toIntOrNull() ?: 0),
                        AddressStartIndex.normalize(changeInput.toIntOrNull() ?: 0)
                    )
                }
            ) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
