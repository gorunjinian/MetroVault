package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.ui.dialogs.InfoDialog
import com.gorunjinian.metrovault.core.ui.dialogs.WarningDialog
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CreateWalletScreen(
    viewModel: CreateWalletViewModel = viewModel(),
    onWalletCreated: () -> Unit,
    onBack: () -> Unit
) {
    // Collect state from ViewModel
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()

    // Handle events from ViewModel
    LaunchedEffect(Unit) {
        viewModel.events.collect { event ->
            when (event) {
                is CreateWalletViewModel.CreateWalletEvent.WalletCreated -> {
                    onWalletCreated()
                }
                is CreateWalletViewModel.CreateWalletEvent.NavigateBack -> {
                    onBack()
                }
            }
        }
    }

    // Cleanup on dispose
    DisposableEffect(Unit) {
        onDispose {
            viewModel.clearSensitiveData()
        }
    }

    // Collecting coin tosses or dice rolls takes minutes, and a screen timeout pauses the
    // activity, whose emergency wipe locks the app and loses the wizard. Keep the screen on
    // for the whole flow; pressing the power button or leaving the app still wipes as before.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose {
            view.keepScreenOn = false
        }
    }

    // System back steps through the wizard like the top bar's arrow instead of leaving it
    BackHandler {
        viewModel.goToPreviousStep()
    }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Create Wallet",
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
                    wordCount = uiState.wordCount,
                    onWordCountChange = { viewModel.setWordCount(it) }
                )

                2 -> Step2Entropy(
                    entropyType = uiState.entropyType,
                    collectedEntropy = uiState.collectedEntropy,
                    entropyProgress = uiState.entropyProgress,
                    bitsCollected = uiState.bitsCollected,
                    requiredEntropyBits = uiState.requiredEntropyBits,
                    entropyInputCount = uiState.entropyInputCount,
                    onEntropyTypeChange = { viewModel.setEntropyType(it) },
                    onAddEntropy = { viewModel.addEntropyInput(it) },
                    onResetEntropy = { viewModel.requestResetEntropy() },
                    onRevealSeed = { viewModel.showSecurityWarning() }
                )

                3 -> Step3SeedPhrase(
                    generatedMnemonic = uiState.generatedMnemonic,
                    onContinue = { viewModel.goToNextStep() }
                )

                4 -> Bip39PassphraseStep(
                    infoPrimaryText = "Add an extra passphrase for additional security",
                    infoSecondaryText = "WARNING: A single typo creates a completely different wallet. The passphrase is shown in plain text so you can verify it carefully.",
                    useBip39Passphrase = uiState.useBip39Passphrase,
                    bip39Passphrase = uiState.bip39Passphrase,
                    confirmBip39Passphrase = uiState.confirmBip39Passphrase,
                    realtimeFingerprint = uiState.realtimeFingerprint,
                    errorMessage = uiState.errorMessage,
                    isSubmitting = uiState.isCreatingWallet,
                    submitLabel = if (uiState.useBip39Passphrase) "Create Wallet with Passphrase" else "Create Wallet",
                    showWriteDownReminder = true,
                    savePassphraseLocally = uiState.savePassphraseLocally,
                    onUsePassphraseChange = { viewModel.setUseBip39Passphrase(it) },
                    onPassphraseChange = {
                        viewModel.setBip39Passphrase(it)
                        viewModel.updateRealtimeFingerprint()
                    },
                    onConfirmPassphraseChange = { viewModel.setConfirmBip39Passphrase(it) },
                    onSavePassphraseLocallyChange = { viewModel.setSavePassphraseLocally(it) },
                    onSubmit = { viewModel.createWallet() }
                )
            }
        }
    }

    // Entropy explanation dialog, shown before the entropy step can be used
    if (uiState.showEntropyInfoDialog) {
        InfoDialog(
            title = "How Your Seed Is Generated",
            onDismiss = { viewModel.dismissEntropyInfo() },
            buttonLabel = "Got It",
            icon = R.drawable.ic_info
        ) {
            Text(
                text = androidx.compose.ui.text.buildAnnotatedString {
                    append("Your seed phrase is generated from your device's cryptographically secure random number generator.\n\nOptionally, you can add your own randomness with coin tosses or dice rolls. Your input is combined with the device's randomness using SHA-256 — ")
                    withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                        append("it is added on top of system entropy, never used alone")
                    }
                    append(", so it can only strengthen the result.\n\nSkipping this step is safe: your seed will still use full system entropy.")
                }
            )
        }
    }

    // Warning before the new seed phrase is generated and shown
    if (uiState.showWarningDialog) {
        WarningDialog(
            title = "Show Your Seed Phrase?",
            confirmLabel = "Show Seed Phrase",
            onConfirm = { viewModel.generateMnemonic() },
            onDismiss = { viewModel.dismissSecurityWarning() },
            destructive = false
        ) {
            Text("Your seed phrase is the only way to recover this wallet if this device is lost or reset. Anyone who sees it can take your funds.")
            Text(
                text = "Write it down on paper and keep it somewhere safe and private. Never photograph it or store it digitally.",
                fontWeight = FontWeight.Bold
            )
            Text("Make sure you're somewhere private and no one can see your screen.")
        }
    }

    uiState.pendingEntropyType?.let { pendingType ->
        WarningDialog(
            title = "Switch to ${if (pendingType == "coin") "Coin Tosses" else "Dice Rolls"}?",
            message = "Switching sources discards the ${uiState.entropyInputCount} you've entered so far.",
            confirmLabel = "Switch",
            onConfirm = { viewModel.confirmEntropyTypeSwitch() },
            onDismiss = { viewModel.dismissEntropyTypeSwitch() }
        )
    }

    if (uiState.showResetEntropyDialog) {
        WarningDialog(
            title = "Reset Entropy?",
            message = "The ${uiState.entropyInputCount} you've entered will be discarded. This can't be undone.",
            confirmLabel = "Reset",
            onConfirm = { viewModel.confirmResetEntropy() },
            onDismiss = { viewModel.dismissResetEntropy() }
        )
    }

    if (uiState.showDiscardSeedDialog) {
        WarningDialog(
            title = "Discard This Seed Phrase?",
            message = "Going back discards this seed phrase. Revealing again generates a completely different one, so don't keep any words you've already written down.",
            confirmLabel = "Discard",
            onConfirm = { viewModel.confirmDiscardSeed() },
            onDismiss = { viewModel.dismissDiscardSeed() }
        )
    }
}

// ========== Step 2: Entropy ==========

@SuppressLint("DefaultLocale")
@Composable
private fun Step2Entropy(
    entropyType: String,
    collectedEntropy: List<Int>,
    entropyProgress: Float,
    bitsCollected: Double,
    requiredEntropyBits: Int,
    entropyInputCount: String,
    onEntropyTypeChange: (String) -> Unit,
    onAddEntropy: (Int) -> Unit,
    onResetEntropy: () -> Unit,
    onRevealSeed: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            text = "User Provided Entropy",
            style = MaterialTheme.typography.headlineSmall
        )

    InfoCard(
        text = "Add your own randomness to the wallet generation. This is optional but can provide additional security assurance.",
        tone = InfoTone.Neutral,
        textStyle = MaterialTheme.typography.bodyMedium
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Choose Entropy Source",
        style = MaterialTheme.typography.titleMedium
    )

    SegmentedToggle(
        options = listOf("Coin Toss", "Dice Rolls"),
        selectedIndex = when (entropyType) {
            "coin" -> 0
            "dice" -> 1
            else -> -1 // Nothing selected until the user picks a source
        },
        onSelect = { index -> onEntropyTypeChange(if (index == 0) "coin" else "dice") },
        modifier = Modifier.fillMaxWidth()
    )

    if (entropyType.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))

        if (entropyType == "coin") {
            Text(
                text = "Tap to record your coin tosses",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                CoinButton(label = "Heads", onClick = { onAddEntropy(0) })
                CoinButton(label = "Tails", onClick = { onAddEntropy(1) })
            }
        } else {
            Text(
                text = "Tap to record your dice rolls",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceEvenly
            ) {
                (1..6).forEach { value ->
                    DiceFace(value = value, onClick = { onAddEntropy(value) })
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Entropy progress display
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.secondaryContainer
            )
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Entropy Collected",
                        style = MaterialTheme.typography.titleSmall
                    )
                    if (collectedEntropy.isNotEmpty()) {
                        TextButton(onClick = onResetEntropy) {
                            Icon(
                                painter = painterResource(R.drawable.ic_refresh),
                                contentDescription = "Reset",
                                modifier = Modifier.size(16.dp)
                            )
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("Reset")
                        }
                    }
                }

                LinearProgressIndicator(
                    progress = { entropyProgress },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "${String.format("%.0f", bitsCollected)} bits collected ($requiredEntropyBits bits recommended)",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                Text(
                    text = entropyInputCount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )
            }
        }
    }

        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRevealSeed,
            modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            if (collectedEntropy.isNotEmpty()) "Reveal Seed Phrase"
            else "Skip to reveal seed"
        )
    }
    }
}

// ========== Step 3: Seed Phrase ==========

@Composable
private fun Step3SeedPhrase(
    generatedMnemonic: List<String>,
    onContinue: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        Text(
            text = "Backup Your Seed Phrase",
            style = MaterialTheme.typography.headlineSmall
        )

    InfoCard(
        text = "Write down your seed phrase and keep it safe. Never share it with anyone.",
        tone = InfoTone.Danger,
        textStyle = MaterialTheme.typography.bodyMedium
    )

    Card(modifier = Modifier.fillMaxWidth()) {
        val wordsPerColumn = generatedMnemonic.size / 2
        val column1 = generatedMnemonic.take(wordsPerColumn)
        val column2 = generatedMnemonic.drop(wordsPerColumn)

        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(6.dp)
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                column1.forEachIndexed { index, word ->
                    val wordNumber = index + 1
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "$wordNumber. $word",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                column2.forEachIndexed { index, word ->
                    val wordNumber = wordsPerColumn + index + 1
                    Card(
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Text(
                            text = "$wordNumber. $word",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 6.dp),
                            style = MaterialTheme.typography.bodyLarge
                        )
                    }
                }
            }
        }
    }

        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
        onClick = onContinue,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Continue")
    }
    }
}

// ========== Helper Composables ==========

@Composable
private fun CoinButton(
    label: String,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = CircleShape,
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(100.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimaryContainer
            )
        }
    }
}

@Composable
private fun DiceFace(
    value: Int,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.primaryContainer,
        modifier = Modifier.size(48.dp)
    ) {
        Box(
            contentAlignment = Alignment.Center,
            modifier = Modifier
                .fillMaxSize()
                .padding(6.dp)
        ) {
            val dotColor = MaterialTheme.colorScheme.onPrimaryContainer

            when (value) {
                1 -> DiceDot(color = dotColor, modifier = Modifier.align(Alignment.Center))
                2 -> {
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomEnd))
                }
                3 -> {
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.Center))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomEnd))
                }
                4 -> {
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopEnd))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomEnd))
                }
                5 -> {
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.TopEnd))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.Center))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomStart))
                    DiceDot(color = dotColor, modifier = Modifier.align(Alignment.BottomEnd))
                }
                6 -> {
                    Column(
                        modifier = Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.SpaceBetween
                    ) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DiceDot(color = dotColor)
                            DiceDot(color = dotColor)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DiceDot(color = dotColor)
                            DiceDot(color = dotColor)
                        }
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            DiceDot(color = dotColor)
                            DiceDot(color = dotColor)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun DiceDot(
    color: Color,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .size(8.dp)
            .clip(CircleShape)
            .background(color)
    )
}
