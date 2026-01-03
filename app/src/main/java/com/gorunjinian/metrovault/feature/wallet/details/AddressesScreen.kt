package com.gorunjinian.metrovault.feature.wallet.details

import com.gorunjinian.metrovault.data.model.BitcoinAddress
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.*
import androidx.compose.material3.SecondaryTabRow
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

/**
 * Formats an address for display by showing first 12 and last 12 characters with ellipsis.
 * Each 4 characters are separated by 2 spaces for readability.
 * Example: "bc1q  ab12  cd34  ...  wx89  yz00"
 */
private fun formatTruncatedAddress(address: String): String {
    if (address.length <= 28) return address // Short enough to show fully
    
    val first15 = address.take(15)
    val last15 = address.takeLast(15)
    
    // Format with 2 spaces every 4 characters
    fun formatWithSpaces(s: String): String {
        return s.chunked(5).joinToString("   ")
    }
    
    return "${formatWithSpaces(first15)}  ...  ${formatWithSpaces(last15)}"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesScreen(
    wallet: Wallet,
    initialTabIndex: Int = 0,
    onBack: () -> Unit,
    onAddressSelected: (address: String, index: Int, isChange: Boolean) -> Unit
) {
    var selectedTabIndex by remember { mutableIntStateOf(initialTabIndex) }
    
    // Sync selectedTabIndex when initialTabIndex changes (e.g., returning from back stack)
    LaunchedEffect(initialTabIndex) {
        selectedTabIndex = initialTabIndex
    }

    // Separate lists for receive and change addresses
    var receiveAddresses by remember { mutableStateOf<List<BitcoinAddress>>(emptyList()) }
    var changeAddresses by remember { mutableStateOf<List<BitcoinAddress>>(emptyList()) }

    var receiveCount by remember { mutableIntStateOf(20) }
    var changeCount by remember { mutableIntStateOf(20) }

    val listState = rememberLazyListState()
    val coroutineScope = rememberCoroutineScope()

    // Show FAB when scrolled down
    val showScrollToTop by remember {
        derivedStateOf { listState.firstVisibleItemIndex > 5 }
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

    // Function to load more addresses
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
            TopAppBar(
                title = { Text("Addresses") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color.Transparent
                )
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
                    Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Scroll to top")
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
            }

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
                            // Index badge
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

                            // Address details
                            Column(
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = formatTruncatedAddress(addressInfo.address),
                                    style = MaterialTheme.typography.bodyMedium
                                )
                            }
                        }
                    }
                }

                // Show More button
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