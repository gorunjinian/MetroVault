package com.gorunjinian.metrovault.feature.wallet.details

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
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
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.ConfirmPasswordDialog
import com.gorunjinian.metrovault.data.model.SilentPaymentKeys
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Standalone screen for the wallet's `sp1q…`/`tsp1q…` silent-payment receive address.
 *
 * Reached two ways:
 *  - Dedicated SP wallets: `WalletDetails → View SP Address` lands here directly (these wallets have
 *    no receive/change tree, so the tabbed [AddressesScreen] is bypassed).
 *  - Regular wallets: tab body inside [AddressesScreen]; see [SilentPaymentAddressContent].
 *
 * Structurally mirrors [AddressDetailScreen]: QR + monospaced address + action buttons. The buttons
 * surface the two follow-ups a user typically wants here — handing the scan capability to a watching
 * wallet, or inspecting the underlying SP keypair.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SPAddressScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onExport: () -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Silent Payment Address") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color.Transparent)
            )
        }
    ) { padding ->
        SilentPaymentAddressContent(
            wallet = wallet,
            userPreferencesRepository = userPreferencesRepository,
            onExport = onExport,
            modifier = Modifier.padding(padding)
        )
    }
}

/**
 * Body of the silent-payment address view. Lives outside any Scaffold so it can be embedded in two
 * places: standalone [SPAddressScreen] (wrapped in its own Scaffold above) and as a tab body inside
 * [AddressesScreen] for regular wallets.
 *
 * Shows the active account's `sp1q…` (always — regardless of whether the wallet is SP-flagged), the
 * derivation context, and two actions: jump to the spscan/descriptor export, or reveal the BIP-352
 * keypair (scan pub/priv + spend pub) behind a password gate. The spend private key is never shown.
 */
@Composable
fun SilentPaymentAddressContent(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onExport: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val context = LocalContext.current
    val secureStorage = remember { SecureStorage(context) }
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    val address = remember { wallet.getActiveSilentPaymentAddress() }
    val accountNumber = remember { wallet.getActiveAccountNumber() }
    val isTestnet = remember { wallet.isActiveWalletTestnet() }
    val coin = if (isTestnet) 1 else 0
    val derivationPath = "m/352'/${coin}'/${accountNumber}'"

    var qrBitmap by remember { mutableStateOf<Bitmap?>(null) }
    LaunchedEffect(address) {
        if (address != null) {
            withContext(Dispatchers.IO) {
                // Encode the raw sp1q… string — silent payments don't use the BIP-21 bitcoin: URI
                // scheme, so generateAddressQRCode (which prepends "bitcoin:") would corrupt it.
                qrBitmap = QRCodeUtils.generateQRCode(address)
            }
        }
    }

    var showWarningDialog by remember { mutableStateOf(false) }
    var showPasswordDialog by remember { mutableStateOf(false) }
    var showKeysDialog by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf("") }
    var revealedKeys by remember { mutableStateOf<SilentPaymentKeys?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
            .padding(top = 32.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        if (address == null) {
            Text(
                text = "Silent payment address is not available for this wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.error
            )
            return@Column
        }

        Box(contentAlignment = Alignment.Center, modifier = Modifier.size(320.dp)) {
            val bmp = qrBitmap
            if (bmp != null) {
                Card(
                    modifier = Modifier
                        .fillMaxSize()
                        .then(
                            if (tapToCopyEnabled) {
                                Modifier.clickable {
                                    copyToClipboard(context, "Silent Payment Address", address)
                                }
                            } else Modifier
                        )
                ) {
                    Image(
                        bitmap = bmp.asImageBitmap(),
                        contentDescription = "QR Code - Tap to copy silent payment address",
                        modifier = Modifier.fillMaxSize()
                    )
                }
            } else {
                CircularProgressIndicator()
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        if (tapToCopyEnabled) {
            Text(
                text = "Tap QR code to copy address",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(8.dp))

        Text(
            text = buildAnnotatedString {
                if (address.length > 5) append(address.dropLast(5))
                withStyle(SpanStyle(fontWeight = FontWeight.Bold)) {
                    append(address.takeLast(5))
                }
            },
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = onExport,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Export Scan Key & Descriptor")
        }

        Spacer(modifier = Modifier.height(8.dp))

        OutlinedButton(
            onClick = { showWarningDialog = true },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show SP Keys")
        }

        Spacer(modifier = Modifier.height(16.dp))

        Text(
            text = "Derivation Path: $derivationPath",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = "Type: Silent Payment",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(24.dp))
    }

    if (showWarningDialog) {
        AlertDialog(
            onDismissRequest = { showWarningDialog = false },
            title = { Text("Security Warning") },
            text = {
                Text(
                    "The scan private key lets whoever holds it detect every silent payment to " +
                        "this wallet. The spend public key alone does not let them spend.\n\n" +
                        "Ensure you are in a private location and no one is watching your screen."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showWarningDialog = false
                    passwordError = ""
                    showPasswordDialog = true
                }) { Text("I Understand") }
            },
            dismissButton = {
                TextButton(onClick = { showWarningDialog = false }) { Text("Cancel") }
            },
            icon = {
                Icon(
                    painter = painterResource(R.drawable.ic_warning),
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.error
                )
            }
        )
    }

    if (showPasswordDialog) {
        ConfirmPasswordDialog(
            onDismiss = {
                showPasswordDialog = false
                passwordError = ""
            },
            onConfirm = { password ->
                val isDecoy = wallet.isDecoyMode
                val isValid = if (isDecoy) {
                    secureStorage.isDecoyPassword(password)
                } else {
                    secureStorage.verifyPasswordSimple(password) &&
                        !secureStorage.isDecoyPassword(password)
                }
                if (isValid) {
                    revealedKeys = wallet.getActiveSilentPaymentKeys()
                    showPasswordDialog = false
                    showKeysDialog = revealedKeys != null
                    passwordError = if (revealedKeys == null) "Keys unavailable" else ""
                } else {
                    passwordError = "Incorrect password"
                }
            },
            errorMessage = passwordError
        )
    }

    if (showKeysDialog && revealedKeys != null) {
        SpKeysDialog(
            keys = revealedKeys!!,
            onDismiss = {
                showKeysDialog = false
                revealedKeys = null
            }
        )
    }
}

@Composable
private fun SpKeysDialog(keys: SilentPaymentKeys, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Dialog(onDismissRequest = onDismiss) {
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 8.dp, vertical = 16.dp),
            shape = MaterialTheme.shapes.extraLarge
        ) {
            Column(
                modifier = Modifier
                    .padding(24.dp)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Silent Payment Keys",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold
                )

                KeyRow(
                    label = "Scan Public Key",
                    value = keys.scanPublicKey.toHex(),
                    sensitive = false,
                    context = context,
                    clipLabel = "SP Scan Public Key"
                )
                KeyRow(
                    label = "Spend Public Key",
                    value = keys.spendPublicKey.toHex(),
                    sensitive = false,
                    context = context,
                    clipLabel = "SP Spend Public Key"
                )
                KeyRow(
                    label = "Scan Private Key",
                    value = keys.scanPrivateKey.toHex(),
                    sensitive = true,
                    context = context,
                    clipLabel = "SP Scan Private Key"
                )

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.align(Alignment.End)
                ) { Text("Close") }
            }
        }
    }
}

@Composable
private fun KeyRow(
    label: String,
    value: String,
    sensitive: Boolean,
    context: Context,
    clipLabel: String,
) {
    Column {
        Text(
            text = label,
            style = MaterialTheme.typography.titleSmall,
            color = if (sensitive) MaterialTheme.colorScheme.error
            else MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { copyToClipboard(context, clipLabel, value) },
            colors = if (sensitive) {
                CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
            } else {
                CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            }
        ) {
            Text(
                text = value,
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodyMedium,
                fontFamily = FontFamily.Monospace,
                fontWeight = if (sensitive) FontWeight.Medium else FontWeight.Normal
            )
        }
    }
}

private fun copyToClipboard(context: Context, label: String, value: String) {
    val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
    clipboard.setPrimaryClip(ClipData.newPlainText(label, value))
    Toast.makeText(context, "$label copied", Toast.LENGTH_SHORT).show()
}
