package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.util.AddressFormatter
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

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

    // Separate lists for receive and change addresses
    var receiveAddresses by remember { mutableStateOf<List<BitcoinAddress>>(emptyList()) }
    var changeAddresses by remember { mutableStateOf<List<BitcoinAddress>>(emptyList()) }

    var receiveCount by remember { mutableIntStateOf(20) }
    var changeCount by remember { mutableIntStateOf(20) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Show FAB when scrolled down, but only on receive/change tabs (silent-payment tab is short)
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 && selectedTabIndex != 2 }
    }

    // Load initial addresses
    LaunchedEffect(Unit) {
        receiveAddresses = wallet.generateAddresses(
            count = 20,
            offset = 0,
            isChange = false
        ) ?: emptyList()

        changeAddresses = wallet.generateAddresses(
            count = 20,
            offset = 0,
            isChange = true
        ) ?: emptyList()
    }

    fun loadMoreReceiveAddresses() {
        val result = wallet.generateAddresses(
            count = 20,
            offset = receiveCount,
            isChange = false
        )
        result?.let {
            receiveAddresses = receiveAddresses + it
            receiveCount += 20
        }
    }

    fun loadMoreChangeAddresses() {
        val result = wallet.generateAddresses(
            count = 20,
            offset = changeCount,
            isChange = true
        )
        result?.let {
            changeAddresses = changeAddresses + it
            changeCount += 20
        }
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Addresses",
                onBack = onBack
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
                    text = { Text("Receive (${receiveAddresses.size})") }
                )
                Tab(
                    selected = selectedTabIndex == 1,
                    onClick = { selectedTabIndex = 1 },
                    text = { Text("Change (${changeAddresses.size})") }
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
                    val currentAddresses = if (selectedTabIndex == 0) receiveAddresses else changeAddresses
                    val loadMoreAction = if (selectedTabIndex == 0) ::loadMoreReceiveAddresses else ::loadMoreChangeAddresses

                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(12.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        items(currentAddresses) { addressInfo ->
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

                        item {
                            Button(
                                onClick = { loadMoreAction() },
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
