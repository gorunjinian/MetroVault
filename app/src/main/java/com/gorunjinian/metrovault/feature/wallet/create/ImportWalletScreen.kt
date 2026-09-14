package com.gorunjinian.metrovault.feature.wallet.create

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
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

                2 -> SeedPhraseEntryStep(
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
                    onSeedQrScanned = { viewModel.onSeedQrScanned(it) },
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
