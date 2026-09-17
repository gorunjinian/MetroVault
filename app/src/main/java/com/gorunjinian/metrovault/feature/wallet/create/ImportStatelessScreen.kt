package com.gorunjinian.metrovault.feature.wallet.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import kotlinx.coroutines.delay
import kotlin.time.Duration.Companion.milliseconds

/**
 * Import screen for stateless wallets.
 * Flow: Step 1 (Configuration) → Step 2 (Seed Phrase, typed or SeedQR) → Step 3 (Passphrase) → WalletDetailsScreen
 * Mirrors the regular import wizard; the difference is that nothing is persisted —
 * the wallet exists only in memory.
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

    // Calculate fingerprint in real-time when mnemonic, passphrase or path changes
    LaunchedEffect(
        uiState.mnemonicWords, uiState.passphrase, uiState.usePassphrase,
        uiState.selectedDerivationPath, uiState.accountNumber
    ) {
        delay(150.milliseconds)  // Debounce
        viewModel.updateRealtimeFingerprint()
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Stateless Import",
                onBack = { viewModel.goToPreviousStep() }
            )
        }
    ) { padding ->
        when (uiState.currentStep) {
            1 -> WalletConfigurationStep(
                modifier = Modifier.padding(padding),
                title = "Seed Phrase Length",
                selectedDerivationPath = uiState.selectedDerivationPath,
                accountNumber = uiState.accountNumber,
                isTestnet = uiState.isTestnet,
                includeSilentPayments = false,
                onDerivationPathChange = { viewModel.setDerivationPath(it) },
                onAccountNumberChange = { viewModel.setAccountNumber(it) },
                onTestnetChange = { viewModel.setTestnetMode(it) },
                onNext = { viewModel.goToNextStep() },
                wordCount = uiState.expectedWordCount,
                onWordCountChange = { viewModel.setWordCount(it) },
                topContent = { StatelessWalletInfoCard() }
            )

            2 -> SeedPhraseEntryStep(
                modifier = Modifier.padding(padding),
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
                modifier = Modifier.padding(padding),
                infoPrimaryText = "If your seed phrase has a BIP39 passphrase (25th word), enable it here.",
                useBip39Passphrase = uiState.usePassphrase,
                bip39Passphrase = uiState.passphrase,
                confirmBip39Passphrase = uiState.confirmPassphrase,
                realtimeFingerprint = "", // shown in the seed summary card instead
                errorMessage = uiState.errorMessage,
                isSubmitting = uiState.isCreating,
                submitLabel = "Open Stateless Wallet",
                onUsePassphraseChange = { viewModel.setUsePassphrase(it) },
                onPassphraseChange = { viewModel.setPassphrase(it) },
                onConfirmPassphraseChange = { viewModel.setConfirmPassphrase(it) },
                onConfirmDone = { viewModel.createWallet() },
                onSubmit = { viewModel.createWallet() },
                topContent = {
                    SeedSummaryCard(
                        wordCount = uiState.mnemonicWords.size,
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
private fun SeedSummaryCard(
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
                    text = "Seed Phrase",
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
