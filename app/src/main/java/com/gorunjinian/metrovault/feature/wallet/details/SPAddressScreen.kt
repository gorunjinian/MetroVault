package com.gorunjinian.metrovault.feature.wallet.details

import android.graphics.Bitmap
import android.widget.Toast
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.qr.QRCodeUtils
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.components.CopyableValueCard
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.dialogs.PasswordGatedWarningDialog
import com.gorunjinian.metrovault.core.ui.dialogs.RevealedKeysDialog
import com.gorunjinian.metrovault.core.util.SecurityUtils
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
            MetroTopBar(
                title = "Silent Payment Address",
                onBack = onBack
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

    var showRevealGate by remember { mutableStateOf(false) }
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
                                    SecurityUtils.copyToClipboard(
                                        context = context,
                                        label = "Silent Payment Address",
                                        text = address,
                                        sensitive = false
                                    )
                                    Toast.makeText(
                                        context,
                                        "Silent Payment Address copied",
                                        Toast.LENGTH_SHORT
                                    ).show()
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
            onClick = {
                passwordError = ""
                showRevealGate = true
            },
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

    if (showRevealGate) {
        PasswordGatedWarningDialog(
            title = "Show Silent Payment Keys?",
            confirmLabel = "Show Keys",
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { showRevealGate = false },
            onVerified = {
                // On failure the password step stays up and says why, instead of closing silently
                val keys = wallet.getActiveSilentPaymentKeys()
                if (keys != null) {
                    revealedKeys = keys
                    showRevealGate = false
                } else {
                    passwordError = "Couldn't load the silent payment keys for this wallet."
                }
            },
            destructive = false,
            errorMessage = passwordError
        ) {
            Text("The scan private key lets anyone who has it see every silent payment this wallet receives. It can't spend them, and the spend private key is never shown.")
            Text("Make sure you're somewhere private and no one can see your screen.")
        }
    }

    revealedKeys?.let { keys ->
        RevealedKeysDialog(
            title = "Silent Payment Keys",
            onDismiss = { revealedKeys = null }
        ) {
            CopyableValueCard(
                label = "Scan Public Key",
                value = keys.scanPublicKey.toHex(),
                sensitive = false,
                clipboardLabel = "SP Scan Public Key"
            )
            CopyableValueCard(
                label = "Spend Public Key",
                value = keys.spendPublicKey.toHex(),
                sensitive = false,
                clipboardLabel = "SP Spend Public Key"
            )
            CopyableValueCard(
                label = "Scan Private Key",
                value = keys.scanPrivateKey.toHex(),
                sensitive = true,
                clipboardLabel = "SP Scan Private Key"
            )
        }
    }
}

