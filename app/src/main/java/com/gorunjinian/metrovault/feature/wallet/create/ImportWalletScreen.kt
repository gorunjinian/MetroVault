package com.gorunjinian.metrovault.feature.wallet.create

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.qr.configureForQRScanning
import com.journeyapps.barcodescanner.CompoundBarcodeView
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    viewModel: ImportWalletViewModel = viewModel(),
    onBack: () -> Unit,
    onWalletImported: () -> Unit,
) {
    val uiState by viewModel.uiState.collectAsState()

    // Handle events
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is ImportWalletViewModel.ImportWalletEvent.WalletImported -> onWalletImported()
                is ImportWalletViewModel.ImportWalletEvent.NavigateBack -> onBack()
            }
        }
    }

    // Calculate fingerprint in real-time when passphrase or mnemonic changes
    LaunchedEffect(uiState.mnemonicWords, uiState.bip39Passphrase, uiState.useBip39Passphrase) {
        delay(150.milliseconds)  // Debounce
        viewModel.updateRealtimeFingerprint()
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Import Wallet",
                onBack = { viewModel.goToPreviousStep() },
                colors = TopAppBarDefaults.topAppBarColors()
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (uiState.currentStep) {
                1 -> WalletConfigurationStep(
                    title = "Seed Phrase Length",
                    selectedDerivationPath = uiState.selectedDerivationPath,
                    accountNumber = uiState.accountNumber,
                    isTestnet = uiState.isTestnet,
                    includeSilentPayments = true,
                    onDerivationPathChange = { viewModel.setDerivationPath(it) },
                    onAccountNumberChange = { viewModel.setAccountNumber(it) },
                    onTestnetChange = { viewModel.setTestnetMode(it) },
                    onNext = { viewModel.goToNextStep() },
                    wordCount = uiState.expectedWordCount,
                    onWordCountChange = { viewModel.setWordCount(it) }
                )

                2 -> Step2SeedPhrase(
                    mnemonicWords = uiState.mnemonicWords,
                    currentWord = uiState.currentWord,
                    expectedWordCount = uiState.expectedWordCount,
                    isKeyboardVisible = uiState.isKeyboardVisible,
                    onMnemonicWordsChange = { words ->
                        // Handle full list replacement (e.g., paste)
                        viewModel.clearMnemonic()
                        words.forEach { viewModel.addWord(it) }
                    },
                    onCurrentWordChange = { viewModel.setCurrentWord(it) },
                    onKeyboardVisibilityChange = { viewModel.setKeyboardVisible(it) },
                    onAddWord = { viewModel.addWord(it) },
                    onNext = { viewModel.goToNextStep() }
                )

                3 -> Bip39PassphraseStep(
                    infoPrimaryText = "If your seed phrase has a BIP39 passphrase (25th word), enable it here",
                    infoSecondaryText = "The passphrase is shown in plain text to avoid typos",
                    useBip39Passphrase = uiState.useBip39Passphrase,
                    bip39Passphrase = uiState.bip39Passphrase,
                    confirmBip39Passphrase = uiState.confirmBip39Passphrase,
                    realtimeFingerprint = uiState.realtimeFingerprint,
                    errorMessage = uiState.errorMessage,
                    isSubmitting = uiState.isImportingWallet,
                    submitLabel = if (uiState.useBip39Passphrase) "Import Wallet with Passphrase" else "Import Wallet",
                    savePassphraseLocally = uiState.savePassphraseLocally,
                    onUsePassphraseChange = { viewModel.setUseBip39Passphrase(it) },
                    onPassphraseChange = { viewModel.setBip39Passphrase(it) },
                    onConfirmPassphraseChange = { viewModel.setConfirmBip39Passphrase(it) },
                    onSavePassphraseLocallyChange = { viewModel.setSavePassphraseLocally(it) },
                    onSubmit = { viewModel.importWallet() }
                )
            }
        }
    }
}

// ========== Step 2: Seed Phrase Input ==========

@Composable
private fun Step2SeedPhrase(
    mnemonicWords: List<String>,
    currentWord: String,
    expectedWordCount: Int,
    isKeyboardVisible: Boolean,
    onMnemonicWordsChange: (List<String>) -> Unit,
    onCurrentWordChange: (String) -> Unit,
    onKeyboardVisibilityChange: (Boolean) -> Unit,
    onAddWord: (String) -> Unit,
    onNext: () -> Unit
) {
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current

    var validationError by remember { mutableStateOf("") }

    // SeedQR Scanner state
    var isScanning by remember { mutableStateOf(false) }
    var hasCameraPermission by remember { mutableStateOf(false) }
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        hasCameraPermission = isGranted
        if (isGranted) {
            isScanning = true
        }
    }

    // Lifecycle observer for scanner
    DisposableEffect(lifecycleOwner, isScanning) {
        val observer = LifecycleEventObserver { _, event ->
            val scanner = barcodeView
            if (isScanning && scanner != null) {
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

    // Resume camera when isScanning becomes true and view is ready
    // This is needed because the lifecycle observer only responds to transitions,
    // not the current state (we're already ON_RESUME when scanning starts)
    LaunchedEffect(barcodeView, isScanning) {
        if (isScanning && barcodeView != null) {
            try { barcodeView?.resume() } catch (_: Exception) { }
        }
    }

    // Pause scanner when isScanning becomes false
    LaunchedEffect(isScanning) {
        if (!isScanning) {
            try { barcodeView?.pause() } catch (_: Exception) { }
        }
    }

    Column(
        modifier = Modifier.fillMaxSize()
    ) {
        // Scrollable content area
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp)
                .padding(top = 16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            // Title row with keyboard toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Enter Seed Phrase",
                    style = MaterialTheme.typography.headlineSmall
                )

                // Keyboard visibility toggle
                FilledTonalIconButton(
                    onClick = {
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onKeyboardVisibilityChange(!isKeyboardVisible)
                    }
                ) {
                    Icon(
                        painter = painterResource(if (isKeyboardVisible) R.drawable.ic_keyboard_hide else R.drawable.ic_keyboard),
                        contentDescription = if (isKeyboardVisible) "Hide keyboard" else "Show keyboard"
                    )
                }
            }

            // Mnemonic input field with chips
            MnemonicInputField(
                words = mnemonicWords,
                currentWord = currentWord,
                expectedWordCount = expectedWordCount,
                onWordRemoved = { index ->
                    onMnemonicWordsChange(mnemonicWords.toMutableList().apply { removeAt(index) })
                },
                onClearAll = {
                    onMnemonicWordsChange(emptyList())
                    onCurrentWordChange("")
                }
            )

            // QR Scanner view (when scanning)
            if (isScanning) {
                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(250.dp)
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
                                            // Get raw bytes for CompactSeedQR (binary data gets corrupted in text)
                                            val rawBytes = result.rawBytes

                                            // Try to decode as SeedQR (passing raw bytes for CompactSeedQR)
                                            val decodedWords = SeedQRUtils.decodeSeedQR(scannedText, rawBytes)

                                            if (decodedWords != null) {
                                                // Check word count matches expected
                                                if (decodedWords.size != expectedWordCount) {
                                                    validationError = "Scanned SeedQR contains ${decodedWords.size} words, but $expectedWordCount words expected"
                                                } else {
                                                    // Success: populate mnemonic words
                                                    validationError = ""
                                                    onMnemonicWordsChange(decodedWords)
                                                    onCurrentWordChange("")
                                                }
                                                isScanning = false
                                                pause()
                                            }
                                        }
                                    }
                                    // Note: Don't call resume() here - the lifecycle observer handles this
                                    // to avoid double initialization (which causes camera freeze)
                                }
                            },
                            modifier = Modifier.fillMaxSize()
                        )

                        // Cancel button overlay
                        Button(
                            onClick = {
                                isScanning = false
                                barcodeView?.pause()
                            },
                            modifier = Modifier
                                .align(Alignment.BottomCenter)
                                .padding(16.dp)
                        ) {
                            Text("Cancel Scan")
                        }
                    }
                }
            }

        }

        // Pin buttons to bottom of content area (above keyboard)
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            if (validationError.isNotEmpty()) {
                InfoCard(
                    text = validationError,
                    tone = InfoTone.Danger
                )
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Scan SeedQR button
            OutlinedButton(
                onClick = {
                    validationError = ""
                    permissionLauncher.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = !isScanning && mnemonicWords.size < expectedWordCount
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_scanner),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Scan SeedQR")
            }

            Spacer(modifier = Modifier.height(8.dp))

            Button(
                onClick = {
                    when {
                        mnemonicWords.size != expectedWordCount -> {
                            validationError = "Please enter all $expectedWordCount words"
                        }
                        !validateMnemonic(mnemonicWords) -> {
                            validationError = "Invalid seed phrase"
                        }
                        else -> {
                            validationError = ""
                            onNext()
                        }
                    }
                },
                modifier = Modifier.fillMaxWidth(),
                enabled = mnemonicWords.size == expectedWordCount
            ) {
                Text("Next")
            }
        }

        // Secure keyboard at the bottom (visible when toggled on and not complete)
        if (isKeyboardVisible && mnemonicWords.size < expectedWordCount && !isScanning) {
            SecureMnemonicKeyboard(
                currentWord = currentWord,
                onKeyPress = { char ->
                    onCurrentWordChange(currentWord + char)
                },
                onBackspace = {
                    if (currentWord.isNotEmpty()) {
                        onCurrentWordChange(currentWord.dropLast(1))
                    } else if (mnemonicWords.isNotEmpty()) {
                        // Remove last word and put it back for editing
                        onCurrentWordChange(mnemonicWords.last())
                        onMnemonicWordsChange(mnemonicWords.dropLast(1))
                    }
                },
                onWordSelected = { word ->
                    onAddWord(word)
                    onCurrentWordChange("")
                }
            )
        }
    }
}

/**
 * Validate a BIP39 mnemonic
 */
private fun validateMnemonic(words: List<String>): Boolean {
    return try {
        MnemonicCode.validate(words)
        true
    } catch (_: Exception) {
        false
    }
}
