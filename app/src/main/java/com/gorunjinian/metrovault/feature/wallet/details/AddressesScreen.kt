package com.gorunjinian.metrovault.feature.wallet.details

import com.gorunjinian.metrovault.data.model.BitcoinAddress
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
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
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.lib.qrtools.QRCodeUtils
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AddressesScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    onSignMessage: (String) -> Unit = {}
) {
    var selectedTabIndex by remember { mutableIntStateOf(0) }
    var selectedAddress by remember { mutableStateOf<String?>(null) }
    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }

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

    LaunchedEffect(selectedAddress) {
        selectedAddress?.let { address ->
            qrBitmap = QRCodeUtils.generateAddressQRCode(address)
        }
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

    // Handle back press
    androidx.activity.compose.BackHandler(enabled = selectedAddress != null) {
        selectedAddress = null
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Addresses") },
                navigationIcon = {
                    IconButton(onClick = {
                        if (selectedAddress != null) {
                            selectedAddress = null
                        } else {
                            onBack()
                        }
                    }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            if (showScrollToTop && selectedAddress == null) {
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
        val context = LocalContext.current

        if (selectedAddress != null) {
            // Show QR Code
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
                    .padding(horizontal = 16.dp)
                    .padding(top = 32.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Top
            ) {
                qrBitmap?.let { bitmap ->
                    Card(
                        modifier = Modifier
                            .size(320.dp)
                            .clickable {
                                // Copy address to clipboard
                                val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                                val clip = ClipData.newPlainText("Bitcoin Address", selectedAddress)
                                clipboard.setPrimaryClip(clip)
                                Toast.makeText(context, "Address copied to clipboard", Toast.LENGTH_SHORT).show()
                            }
                    ) {
                        Image(
                            bitmap = bitmap.asImageBitmap(),
                            contentDescription = "QR Code - Tap to copy address",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    text = "Tap QR code to copy address",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = selectedAddress!!,
                    style = MaterialTheme.typography.bodyMedium
                )

                Spacer(modifier = Modifier.height(24.dp))
                
                // Sign Message button
                Button(
                    onClick = { onSignMessage(selectedAddress!!) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Sign Message")
                }
                
                Spacer(modifier = Modifier.height(8.dp))

                OutlinedButton(
                    onClick = { selectedAddress = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Back to List")
                }
            }
        } else {
            // Show address list with tabs
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(padding)
            ) {
                SecondaryTabRow(
                    selectedTabIndex = selectedTabIndex
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
                            onClick = { selectedAddress = addressInfo.address },
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
                                        text = addressInfo.address,
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
}