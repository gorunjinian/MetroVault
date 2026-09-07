package com.gorunjinian.metrovault.feature.wallet.create

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.core.qr.configureForQRScanning
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Import screen for stateless wallets via SeedQR.
 * Flow: Step 1 (Configuration) → Step 2 (QR Scan) → Step 3 (Passphrase) → WalletDetailsScreen
 * Does not persist anything - wallet exists only in memory.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportStatelessScreen(
    viewModel: ImportStatelessViewModel = viewModel(),
    onBack: () -> Unit,
    onWalletCreated: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportStatelessViewModel.ImportStatelessEvent.WalletCreated -> onWalletCreated()
                is ImportStatelessViewModel.ImportStatelessEvent.NavigateBack -> onBack()
            }
        }
    }

    // Calculate fingerprint in real-time when mnemonic or passphrase changes (Step 3)
    LaunchedEffect(
        uiState.scannedMnemonic, uiState.passphrase, uiState.usePassphrase,
        uiState.selectedDerivationPath, uiState.accountNumber
    ) {
        delay(150.milliseconds)  // Debounce
        viewModel.updateRealtimeFingerprint()
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = when (uiState.currentStep) {
                    1 -> "Stateless Import"
                    2 -> "Scan SeedQR"
                    3 -> "Passphrase (Optional)"
                    else -> "Stateless Import"
                },
                onBack = { viewModel.goToPreviousStep() }
            )
        }
    ) { padding ->
        when (uiState.currentStep) {
            1 -> WalletConfigurationStep(
                modifier = Modifier.padding(padding),
                title = "Address Type",
                selectedDerivationPath = uiState.selectedDerivationPath,
                accountNumber = uiState.accountNumber,
                isTestnet = uiState.isTestnet,
                includeSilentPayments = false,
                onDerivationPathChange = { viewModel.setDerivationPath(it) },
                onAccountNumberChange = { viewModel.setAccountNumber(it) },
                onTestnetChange = { viewModel.setTestnetMode(it) },
                onNext = { viewModel.goToNextStep() },
                topContent = { StatelessWalletInfoCard() }
            )

            2 -> Step2ScanSeedQR(
                modifier = Modifier.padding(padding),
                errorMessage = uiState.scanError,
                onMnemonicScanned = { viewModel.onMnemonicScanned(it) },
                onError = { viewModel.setScanError(it) }
            )

            3 -> Bip39PassphraseStep(
                modifier = Modifier.padding(padding),
                infoPrimaryText = "If your seed phrase has a BIP39 passphrase (25th word), enable it here.",
                useBip39Passphrase = uiState.usePassphrase,
                bip39Passphrase = uiState.passphrase,
                confirmBip39Passphrase = uiState.confirmPassphrase,
                realtimeFingerprint = "", // shown in the scanned-seed summary card instead
                errorMessage = uiState.errorMessage,
                isSubmitting = uiState.isCreating,
                submitLabel = "Open Stateless Wallet",
                onUsePassphraseChange = { viewModel.setUsePassphrase(it) },
                onPassphraseChange = { viewModel.setPassphrase(it) },
                onConfirmPassphraseChange = { viewModel.setConfirmPassphrase(it) },
                onConfirmDone = { viewModel.createWallet() },
                onSubmit = { viewModel.createWallet() },
                topContent = {
                    ScannedSeedSummaryCard(
                        wordCount = uiState.scannedMnemonic?.size ?: 0,
                        fingerprint = uiState.realtimeFingerprint
                    )
                },
                aboveButtonContent = { StatelessWipeWarningCard() }
            )
        }
    }
}

// ========== Stateless-specific cards ==========

@Composable
private fun StatelessWalletInfoCard() {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp)
        ) {
            Text(
                text = "Stateless Wallet",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
            Text(
                text = "This wallet will exist only in memory and will be wiped when you lock or exit the app.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSecondaryContainer
            )
        }
    }
}

@Composable
private fun ScannedSeedSummaryCard(
    wordCount: Int,
    fingerprint: String
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "SeedQR Scanned",
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onPrimaryContainer
                )
                Surface(
                    color = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    shape = RoundedCornerShape(4.dp)
                ) {
                    Text(
                        text = "$wordCount words",
                        modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                        style = MaterialTheme.typography.labelMedium
                    )
                }
            }
            if (fingerprint.isNotEmpty()) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Fingerprint:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    Text(
                        text = fingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onPrimaryContainer
                    )
                }
            }
        }
    }
}

@Composable
private fun StatelessWipeWarningCard() {
    InfoCard(
        text = "This wallet will be wiped when you lock or leave the app.",
        tone = InfoTone.Warning,
        textStyle = MaterialTheme.typography.bodySmall
    )
}

// ========== Step 2: Scan SeedQR ==========

@Suppress("UNUSED_PARAMETER")
@Composable
private fun Step2ScanSeedQR(
    modifier: Modifier = Modifier,
    errorMessage: String,
    onMnemonicScanned: (List<String>) -> Unit,
    onError: (String) -> Unit
) {
    val context = androidx.compose.ui.platform.LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // Check if permission is already granted on composition
    var hasCameraPermission by remember {
        mutableStateOf(
            androidx.core.content.ContextCompat.checkSelfPermission(
                context,
                Manifest.permission.CAMERA
            ) == android.content.pm.PackageManager.PERMISSION_GRANTED
        )
    }
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }

    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
    }

    // Request camera permission if not already granted
    LaunchedEffect(hasCameraPermission) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // Lifecycle observer for scanner
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            val scanner = barcodeView
            if (scanner != null) {
                when (event) {
                    Lifecycle.Event.ON_RESUME -> {
                        try { scanner.resume() } catch (_: Exception) { }
                    }
                    Lifecycle.Event.ON_PAUSE -> {
                        try { scanner.pause() } catch (_: Exception) { }
                    }
                    else -> {}
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            try { barcodeView?.pause() } catch (_: Exception) { }
        }
    }

    // Resume camera when view is ready
    LaunchedEffect(barcodeView) {
        if (barcodeView != null) {
            try { barcodeView?.resume() } catch (_: Exception) { }
        }
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        if (!hasCameraPermission) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "Camera Permission Required",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "Please grant camera permission to scan SeedQR codes.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) }
                    ) {
                        Text("Grant Permission")
                    }
                }
            }
        } else {
            Text(
                text = "Scan a SeedQR code to import your wallet.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            // QR Scanner
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f)
            ) {
                Box(modifier = Modifier.fillMaxSize()) {
                    AndroidView(
                        factory = { ctx ->
                            CompoundBarcodeView(ctx).apply {
                                barcodeView = this
                                configureForQRScanning()
                                setStatusText("")
                                decodeContinuous { result ->
                                    result.text?.let { scannedText ->
                                        val rawBytes = result.rawBytes
                                        val decodedWords = SeedQRUtils.decodeSeedQR(scannedText, rawBytes)

                                        if (decodedWords != null) {
                                            onMnemonicScanned(decodedWords)
                                            pause()
                                        }
                                    }
                                }
                            }
                        },
                        modifier = Modifier.fillMaxSize()
                    )
                }
            }

            if (errorMessage.isNotEmpty()) {
                InfoCard(
                    text = errorMessage,
                    tone = InfoTone.Danger
                )
            }
        }
    }
}
