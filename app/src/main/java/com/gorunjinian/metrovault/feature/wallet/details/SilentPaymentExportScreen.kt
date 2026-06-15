package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.util.SecurityUtils
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * BIP-352 silent-payments export screen.
 *
 * Mirrors [DescriptorsScreen]: account dropdown, segmented toggle, QR + textual card. The toggle
 * picks between two equivalent exports of the wallet's scan key:
 *  - **Scan Key** — the raw `spscan…` / `tspscan…` bech32m string (BIP-352 scan-key export).
 *  - **Descriptor** — `sp([fp/352h/coin_h/account_h]spscan…)#checksum`, the form Sparrow imports.
 *
 * Both contain the scan **private** key, so anyone with either export can detect every silent
 * payment to this wallet (sender, amount, time). Neither exposes the spend private key, so funds
 * remain safe. The receive `sp1q…` address lives on [AddressesScreen]; this screen is exclusively
 * about handing the scan capability to a watching wallet.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SilentPaymentExportScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
) {
    // false = scan key (spscan…), true = descriptor (sp(…)#…)
    var showDescriptor by remember { mutableStateOf(false) }

    var currentQR by remember { mutableStateOf<Bitmap?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            currentQR?.recycle()
            currentQR = null
            System.gc()
        }
    }

    val walletsList by wallet.wallets.collectAsState()
    val walletId = wallet.getActiveWalletId()
    val activeWalletMetadata = remember(walletsList, walletId) {
        walletsList.find { it.id == walletId }
    }

    val walletInfo = wallet.getActiveWalletInfo(activeWalletMetadata)
    val accounts = walletInfo.accounts
    val isTestnet = wallet.isActiveWalletTestnet()
    var selectedAccountNumber by remember { mutableIntStateOf(walletInfo.accountNumber) }
    var accountDropdownExpanded by remember { mutableStateOf(false) }

    val displayData = remember(selectedAccountNumber, showDescriptor, isTestnet) {
        if (showDescriptor) {
            wallet.getActiveSilentPaymentDescriptor(selectedAccountNumber).orEmpty()
        } else {
            wallet.getActiveSilentPaymentScanKeyExport(selectedAccountNumber).orEmpty()
        }
    }

    val exportLabel = if (showDescriptor) "Descriptor" else "Scan Key"
    val selectedAccountName = activeWalletMetadata?.getAccountDisplayName(selectedAccountNumber)
        ?: "Account $selectedAccountNumber"

    LaunchedEffect(displayData) {
        if (displayData.isNotEmpty()) {
            currentQR = null
            withContext(Dispatchers.IO) {
                currentQR = QRCodeUtils.generateQRCode(displayData)
            }
        }
    }

    val context = LocalContext.current
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Silent Payments") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))

            ExposedDropdownMenuBox(
                expanded = accountDropdownExpanded,
                onExpandedChange = { accountDropdownExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedAccountName,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("$exportLabel · Silent Payments") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = accountDropdownExpanded) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = accountDropdownExpanded,
                    onDismissRequest = { accountDropdownExpanded = false }
                ) {
                    accounts.forEach { accountNum ->
                        val accountName = activeWalletMetadata?.getAccountDisplayName(accountNum)
                            ?: "Account $accountNum"
                        val coin = if (isTestnet) 1 else 0
                        val accountPath = "m/352'/${coin}'/${accountNum}'"
                        DropdownMenuItem(
                            text = {
                                Column(modifier = Modifier.padding(vertical = 4.dp)) {
                                    Text(
                                        text = accountName,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = accountPath,
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                selectedAccountNumber = accountNum
                                accountDropdownExpanded = false
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        if (accountNum != accounts.last()) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }

            SegmentedToggle(
                firstOption = "Scan Key",
                secondOption = "Descriptor",
                isSecondSelected = showDescriptor,
                onSelectFirst = { showDescriptor = false },
                onSelectSecond = { showDescriptor = true },
                modifier = Modifier.fillMaxWidth()
            )

            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.surfaceVariant
                )
            ) {
                Text(
                    text = "Import this into a watching wallet to detect incoming silent payments. " +
                        "Anyone with it can see every payment you receive.Share only with watching wallets you trust.",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }

            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(1f)
            ) {
                val qr = currentQR
                if (qr != null && displayData.isNotEmpty()) {
                    Card(
                        modifier = Modifier
                            .fillMaxSize()
                            .then(
                                if (tapToCopyEnabled) {
                                    Modifier.clickable {
                                        SecurityUtils.copyToClipboardWithAutoClear(
                                            context = context,
                                            label = exportLabel,
                                            text = displayData,
                                            delayMs = 20_000
                                        )
                                        Toast.makeText(
                                            context,
                                            "Copied! Clipboard will clear in 20 seconds",
                                            Toast.LENGTH_SHORT
                                        ).show()
                                    }
                                } else Modifier
                            )
                    ) {
                        Image(
                            bitmap = qr.asImageBitmap(),
                            contentDescription = "$exportLabel QR Code - Tap to copy",
                            modifier = Modifier.fillMaxSize()
                        )
                    }
                } else if (displayData.isEmpty()) {
                    Text(
                        text = "Silent payments are not available for this wallet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                } else {
                    CircularProgressIndicator()
                }
            }

            if (tapToCopyEnabled && displayData.isNotEmpty()) {
                Text(
                    text = "Tap QR code to copy",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (displayData.isNotEmpty()) {
                Card(modifier = Modifier.fillMaxWidth()) {
                    Text(
                        text = displayData,
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
