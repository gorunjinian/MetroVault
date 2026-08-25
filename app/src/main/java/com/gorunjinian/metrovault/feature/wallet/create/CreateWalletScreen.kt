package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
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
    var showDiscardDraftDialog by remember { mutableStateOf(false) }

    fun handleBack() {
        when {
            uiState.currentStep > 1 -> viewModel.goToPreviousStep()
            uiState.hasUnsavedDraft -> showDiscardDraftDialog = true
            else -> viewModel.discardDraftAndExit()
        }
    }

    BackHandler(onBack = ::handleBack)

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

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Create Wallet",
                onBack = ::handleBack,
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
                    cardsWithReplacement = uiState.cardsWithReplacement,
                    physicalEntropyMode = uiState.physicalEntropyMode,
                    entropyProgress = uiState.entropyProgress,
                    bitsCollected = uiState.bitsCollected,
                    requiredEntropyBits = uiState.requiredEntropyBits,
                    entropyInputCount = uiState.entropyInputCount,
                    entropyErrorMessage = uiState.entropyErrorMessage,
                    canGenerateMnemonic = uiState.canGenerateMnemonic,
                    onEntropyTypeChange = { viewModel.setEntropyType(it) },
                    onAddEntropy = { viewModel.addEntropyInput(it) },
                    onRemoveLastEntropy = { viewModel.removeLastEntropyInput() },
                    onRemoveEntropyAt = { viewModel.removeEntropyInput(it) },
                    onCardsWithReplacementChange = { viewModel.setCardsWithReplacement(it) },
                    onPhysicalEntropyModeChange = { viewModel.setPhysicalEntropyMode(it) },
                    onResetEntropy = { viewModel.resetEntropy() },
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

    if (showDiscardDraftDialog) {
        AlertDialog(
            onDismissRequest = { showDiscardDraftDialog = false },
            title = { Text("Discard unfinished wallet?") },
            text = {
                Text(
                    "Leaving now will permanently wipe this wallet draft, including its captured " +
                        "entropy, generated seed phrase, passphrase, and configuration."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        showDiscardDraftDialog = false
                        viewModel.discardDraftAndExit()
                    }
                ) { Text("Discard wallet draft") }
            },
            dismissButton = {
                TextButton(onClick = { showDiscardDraftDialog = false }) { Text("Continue editing") }
            },
            icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) }
        )
    }

    // Entropy explanation dialog, shown before the entropy step can be used
    if (uiState.showEntropyInfoDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissEntropyInfo() },
            title = { Text("How Your Seed Is Generated") },
            text = {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        append("By default, your seed phrase uses the device's cryptographically secure random number generator. Optional coin tosses, dice rolls, or card draws are normalized and mixed into that randomness.\n\n")
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("Physical only (reproducible)")
                        }
                        append(" instead derives the wallet deterministically from the recorded sequence without device randomness. It is available only after enough estimated physical entropy is entered. Hashing does not create entropy.\n\nSkipping physical input is safe in the default mode.")
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.dismissEntropyInfo() }) {
                    Text("Got It")
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.ic_info), contentDescription = null) }
        )
    }

    // Security warning dialog
    if (uiState.showWarningDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissSecurityWarning() },
            title = { Text("Security Warning") },
            text = {
                Text(
                    text = androidx.compose.ui.text.buildAnnotatedString {
                        append("Your seed phrase is the master key to your funds. Never share it with anyone.\n\nEnsure you are in a private location and no one is watching your screen.\n\n")
                        if (uiState.physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY) {
                            withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                                append("Reproducible physical-only mode is active. ")
                            }
                            append("No device randomness will be added. Security is limited by the quality and secrecy of your recorded physical sequence. The same source, sequence, mode, and word count reproduce the same mnemonic.\n\n")
                        }
                        withStyle(androidx.compose.ui.text.SpanStyle(fontWeight = FontWeight.Bold)) {
                            append("Write it down and keep it somewhere secure and private")
                        }
                    }
                )
            },
            confirmButton = {
                TextButton(onClick = { viewModel.generateMnemonic() }) {
                    Text("I Understand")
                }
            },
            dismissButton = {
                TextButton(onClick = { viewModel.dismissSecurityWarning() }) {
                    Text("Cancel")
                }
            },
            icon = { Icon(painter = painterResource(R.drawable.ic_warning), contentDescription = null) }
        )
    }
}

// ========== Step 2: Entropy ==========

private sealed class PendingEntropyChange {
    data class Source(val type: String) : PendingEntropyChange()
    data class CardMode(val withReplacement: Boolean) : PendingEntropyChange()
    data object Reset : PendingEntropyChange()
}

private val CARD_SUIT_DISPLAY_ORDER = listOf(
    CardSuit.SPADES,
    CardSuit.HEARTS,
    CardSuit.CLUBS,
    CardSuit.DIAMONDS
)

@Composable
private fun cardSuitColor(suit: CardSuit): Color =
    if (suit.isRed) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface

@SuppressLint("DefaultLocale")
@Composable
private fun Step2Entropy(
    entropyType: String,
    collectedEntropy: List<Int>,
    cardsWithReplacement: Boolean,
    physicalEntropyMode: PhysicalEntropyMode,
    entropyProgress: Float,
    bitsCollected: Double,
    requiredEntropyBits: Int,
    entropyInputCount: String,
    entropyErrorMessage: String,
    canGenerateMnemonic: Boolean,
    onEntropyTypeChange: (String) -> Unit,
    onAddEntropy: (Int) -> Unit,
    onRemoveLastEntropy: () -> Unit,
    onRemoveEntropyAt: (Int) -> Unit,
    onCardsWithReplacementChange: (Boolean) -> Unit,
    onPhysicalEntropyModeChange: (PhysicalEntropyMode) -> Unit,
    onResetEntropy: () -> Unit,
    onRevealSeed: () -> Unit
) {
    var pendingChange by remember { mutableStateOf<PendingEntropyChange?>(null) }

    fun requestSourceChange(type: String) {
        if (type == entropyType) return
        if (collectedEntropy.isEmpty()) onEntropyTypeChange(type)
        else pendingChange = PendingEntropyChange.Source(type)
    }

    fun requestCardModeChange(withReplacement: Boolean) {
        if (withReplacement == cardsWithReplacement) return
        if (collectedEntropy.isEmpty()) onCardsWithReplacementChange(withReplacement)
        else pendingChange = PendingEntropyChange.CardMode(withReplacement)
    }

    fun requestReset() {
        if (collectedEntropy.isNotEmpty()) pendingChange = PendingEntropyChange.Reset
    }

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
        text = if (physicalEntropyMode == PhysicalEntropyMode.MIX_WITH_DEVICE) {
            "Recommended: secure device randomness generates the seed. Any physical sequence is SHA-256-normalized and mixed into it."
        } else {
            "Reproducible mode: the recorded physical sequence alone determines the BIP39 mnemonic. Device randomness is not added, and hashing does not create entropy."
        },
        tone = if (physicalEntropyMode == PhysicalEntropyMode.MIX_WITH_DEVICE) InfoTone.Neutral else InfoTone.Warning,
        textStyle = MaterialTheme.typography.bodyMedium
    )

    Text("Seed Randomness", style = MaterialTheme.typography.titleMedium)

    SegmentedToggle(
        options = listOf("Device + physical", "Physical only"),
        selectedIndex = if (physicalEntropyMode == PhysicalEntropyMode.MIX_WITH_DEVICE) 0 else 1,
        onSelect = { index ->
            onPhysicalEntropyModeChange(
                if (index == 0) PhysicalEntropyMode.MIX_WITH_DEVICE else PhysicalEntropyMode.PHYSICAL_ONLY
            )
        },
        modifier = Modifier.fillMaxWidth()
    )

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Choose Entropy Source",
        style = MaterialTheme.typography.titleMedium
    )

    SegmentedToggle(
        options = listOf("Coin Toss", "Dice Rolls", "Cards"),
        selectedIndex = when (entropyType) {
            "coin" -> 0
            "dice" -> 1
            "cards" -> 2
            else -> -1 // Nothing selected until the user picks a source
        },
        onSelect = { index ->
            requestSourceChange(when (index) {
                0 -> "coin"
                1 -> "dice"
                else -> "cards"
            })
        },
        modifier = Modifier.fillMaxWidth()
    )

    if (entropyType.isNotEmpty()) {
        Spacer(modifier = Modifier.height(16.dp))

        if (collectedEntropy.isNotEmpty()) {
            RecordedEntropySequence(
                entropyType = entropyType,
                inputs = collectedEntropy,
                onRemoveAt = onRemoveEntropyAt
            )
            Spacer(modifier = Modifier.height(8.dp))
        }

        if (entropyType == "coin") {
            Text(
                text = "Tap anywhere on the left for Heads or anywhere on the right for Tails",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(128.dp)
                    .clip(RoundedCornerShape(20.dp))
            ) {
                CoinCaptureZone(
                    label = "Heads",
                    shortcut = "LEFT • H",
                    containerColor = MaterialTheme.colorScheme.primary,
                    contentColor = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.weight(1f),
                    onClick = { onAddEntropy(0) }
                )
                Spacer(
                    modifier = Modifier
                        .fillMaxHeight()
                        .width(3.dp)
                        .background(MaterialTheme.colorScheme.surface)
                )
                CoinCaptureZone(
                    label = "Tails",
                    shortcut = "RIGHT • T",
                    containerColor = MaterialTheme.colorScheme.tertiary,
                    contentColor = MaterialTheme.colorScheme.onTertiary,
                    modifier = Modifier.weight(1f),
                    onClick = { onAddEntropy(1) }
                )
            }
        } else if (entropyType == "dice") {
            Text(
                text = "Tap a die or type each roll using the numeric keyboard",
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

            var diceKeyboardBuffer by remember { mutableStateOf("") }
            var rejectedDiceKey by remember { mutableStateOf(false) }
            var keyboardRequest by remember { mutableIntStateOf(0) }
            val diceKeyboardFocusRequester = remember { FocusRequester() }
            val softwareKeyboardController = LocalSoftwareKeyboardController.current

            DisposableEffect(Unit) {
                onDispose { diceKeyboardBuffer = "" }
            }

            LaunchedEffect(keyboardRequest) {
                if (keyboardRequest > 0) {
                    diceKeyboardFocusRequester.requestFocus()
                    withFrameNanos { }
                    softwareKeyboardController?.show()
                }
            }

            BasicTextField(
                value = diceKeyboardBuffer,
                onValueChange = { newText ->
                    val inserted = insertedText(diceKeyboardBuffer, newText)
                    var rejected = false
                    inserted.forEach { character ->
                        if (character in '1'..'6') onAddEntropy(character.digitToInt())
                        else if (!character.isWhitespace()) rejected = true
                    }
                    diceKeyboardBuffer = newText.filter { it in '1'..'6' }
                    rejectedDiceKey = rejected
                },
                modifier = Modifier
                    .size(1.dp)
                    .focusRequester(diceKeyboardFocusRequester)
                    .clearAndSetSemantics { },
                textStyle = MaterialTheme.typography.bodySmall.copy(color = Color.Transparent),
                cursorBrush = SolidColor(Color.Transparent),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                singleLine = true,
                decorationBox = { innerTextField -> innerTextField() }
            )

            OutlinedButton(
                onClick = { keyboardRequest++ },
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_keyboard),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Open numeric keyboard (1–6)")
            }
            Text(
                text = if (rejectedDiceKey) {
                    "Only dice values 1–6 are recorded."
                } else {
                    "Keyboard presses appear only as captured dice above; use × to delete a roll."
                },
                style = MaterialTheme.typography.bodySmall,
                color = if (rejectedDiceKey) MaterialTheme.colorScheme.error
                else MaterialTheme.colorScheme.onSurfaceVariant
            )
        } else {
            CardEntropyInput(
                selectedCardIds = collectedEntropy,
                withReplacement = cardsWithReplacement,
                onWithReplacementChange = ::requestCardModeChange,
                onCardSelected = onAddEntropy
            )
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
                        Row {
                            TextButton(onClick = onRemoveLastEntropy) {
                                Text("Undo")
                            }
                            TextButton(onClick = ::requestReset) {
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
                }

                LinearProgressIndicator(
                    progress = { entropyProgress },
                    modifier = Modifier.fillMaxWidth(),
                )

                Text(
                    text = "${String.format("%.2f", bitsCollected)} estimated bits ($requiredEntropyBits bits ${if (physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY) "required" else "recommended"})",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                Text(
                    text = entropyInputCount,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer
                )

                if (entropyType == "cards" && !cardsWithReplacement) {
                    Text(
                        text = "A full shuffled deck has a maximum of about 225.58 bits, so this mode cannot independently reach 256 bits.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }
    }

        if (entropyErrorMessage.isNotEmpty()) {
            Text(
                text = entropyErrorMessage,
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodyMedium
            )
        }

        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onRevealSeed,
            enabled = canGenerateMnemonic,
            modifier = Modifier.fillMaxWidth()
    ) {
        Text(
            when {
                physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY && canGenerateMnemonic ->
                    "Reveal Reproducible Seed Phrase"
                physicalEntropyMode == PhysicalEntropyMode.PHYSICAL_ONLY ->
                    "Enter $requiredEntropyBits bits to continue"
                collectedEntropy.isNotEmpty() -> "Reveal Seed Phrase"
                else -> "Skip to reveal seed"
            }
        )
    }

    pendingChange?.let { change ->
        val actionDescription = when (change) {
            is PendingEntropyChange.Source -> "switch entropy sources"
            is PendingEntropyChange.CardMode -> "change the card draw mode"
            PendingEntropyChange.Reset -> "reset the captured sequence"
        }
        AlertDialog(
            onDismissRequest = { pendingChange = null },
            title = { Text("Discard captured entropy?") },
            text = {
                Text(
                    "You have ${collectedEntropy.size} captured ${if (collectedEntropy.size == 1) "entry" else "entries"}. " +
                        "If you $actionDescription, this partial sequence will be permanently cleared."
                )
            },
            confirmButton = {
                TextButton(
                    onClick = {
                        when (change) {
                            is PendingEntropyChange.Source -> onEntropyTypeChange(change.type)
                            is PendingEntropyChange.CardMode -> onCardsWithReplacementChange(change.withReplacement)
                            PendingEntropyChange.Reset -> onResetEntropy()
                        }
                        pendingChange = null
                    }
                ) { Text("Discard and continue") }
            },
            dismissButton = {
                TextButton(onClick = { pendingChange = null }) { Text("Keep sequence") }
            },
            icon = { Icon(painterResource(R.drawable.ic_warning), contentDescription = null) }
        )
    }
    }
}

private fun insertedText(previous: String, current: String): String {
    var commonPrefix = 0
    while (
        commonPrefix < previous.length &&
        commonPrefix < current.length &&
        previous[commonPrefix] == current[commonPrefix]
    ) {
        commonPrefix++
    }

    var commonSuffix = 0
    val previousRemaining = previous.length - commonPrefix
    val currentRemaining = current.length - commonPrefix
    while (
        commonSuffix < previousRemaining &&
        commonSuffix < currentRemaining &&
        previous[previous.lastIndex - commonSuffix] == current[current.lastIndex - commonSuffix]
    ) {
        commonSuffix++
    }

    return current.substring(commonPrefix, current.length - commonSuffix)
}

@Composable
private fun RecordedEntropySequence(
    entropyType: String,
    inputs: List<Int>,
    onRemoveAt: (Int) -> Unit
) {
    val scrollState = rememberScrollState()

    LaunchedEffect(inputs.size) {
        withFrameNanos { }
        scrollState.animateScrollTo(scrollState.maxValue)
    }

    Text("Captured sequence", style = MaterialTheme.typography.titleSmall)
    Text(
        "Newest entry is shown at the right. Tap × on any item to remove it.",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(scrollState),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        inputs.forEachIndexed { index, value ->
            CapturedEntropyItem(
                entropyType = entropyType,
                value = value,
                position = index + 1,
                onRemove = { onRemoveAt(index) }
            )
        }
    }
    if (scrollState.maxValue > 0) {
        LinearProgressIndicator(
            progress = { scrollState.value.toFloat() / scrollState.maxValue.toFloat() },
            modifier = Modifier.fillMaxWidth()
        )
        Text(
            "Swipe the sequence left or right to review all ${inputs.size} entries.",
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun CapturedEntropyItem(
    entropyType: String,
    value: Int,
    position: Int,
    onRemove: () -> Unit
) {
    val label = when (entropyType) {
        "coin" -> if (value == 0) "H" else "T"
        "cards" -> PlayingCard.fromId(value).label
        else -> value.toString()
    }
    val spokenType = when (entropyType) {
        "coin" -> "coin toss"
        "cards" -> "card draw"
        else -> "dice roll"
    }
    val capturedCard = if (entropyType == "cards") PlayingCard.fromId(value) else null
    val containerColor = when {
        entropyType == "coin" && value == 0 -> MaterialTheme.colorScheme.primary
        entropyType == "coin" -> MaterialTheme.colorScheme.tertiary
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    val contentColor = when {
        entropyType == "coin" && value == 0 -> MaterialTheme.colorScheme.onPrimary
        entropyType == "coin" -> MaterialTheme.colorScheme.onTertiary
        capturedCard?.suit?.isRed == true -> MaterialTheme.colorScheme.error
        else -> MaterialTheme.colorScheme.onSurfaceVariant
    }

    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Box(modifier = Modifier.padding(top = 8.dp, end = 5.dp)) {
            if (entropyType == "dice") {
                DiceFace(value = value, onClick = {})
            } else {
                Surface(
                    shape = RoundedCornerShape(8.dp),
                    color = containerColor,
                    modifier = Modifier
                        .height(48.dp)
                        .widthIn(min = 48.dp)
                ) {
                    Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier.padding(horizontal = 10.dp)
                    ) {
                        Text(
                            label,
                            style = MaterialTheme.typography.titleMedium,
                            color = contentColor
                        )
                    }
                }
            }
            Icon(
                painter = painterResource(R.drawable.ic_close),
                contentDescription = "Delete $spokenType $label at position $position",
                tint = MaterialTheme.colorScheme.onError,
                modifier = Modifier
                    .align(Alignment.TopEnd)
                    .offset(x = 7.dp, y = (-7).dp)
                    .size(22.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.error)
                    .clickable(onClick = onRemove)
                    .padding(4.dp)
            )
        }
        Text("#$position", style = MaterialTheme.typography.labelSmall)
    }
}

@Composable
private fun CardEntropyInput(
    selectedCardIds: List<Int>,
    withReplacement: Boolean,
    onWithReplacementChange: (Boolean) -> Unit,
    onCardSelected: (Int) -> Unit
) {
    var selectedSuit by remember { mutableStateOf(CardSuit.SPADES) }

    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text("Draw with replacement", style = MaterialTheme.typography.titleSmall)
            Text(
                if (withReplacement) "Repeated cards are allowed (default)"
                else "Each card can be drawn once",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = withReplacement, onCheckedChange = onWithReplacementChange)
    }

    InfoCard(
        text = if (withReplacement) {
            "After recording each draw, put that card at the bottom of the deck. Before the next draw, cut/split the deck at unpredictable positions a random 1–5 times. Choose the number of cuts independently each round. The 5.70-bit estimate assumes the next card is effectively uniform."
        } else {
            "Keep each drawn card out of the deck. Previously drawn cards are disabled below."
        },
        tone = InfoTone.Info,
        textStyle = MaterialTheme.typography.bodySmall
    )

    Text("1. Choose the suit", style = MaterialTheme.typography.titleSmall)
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        CARD_SUIT_DISPLAY_ORDER.forEach { suit ->
            val isSelected = selectedSuit == suit
            OutlinedButton(
                onClick = { selectedSuit = suit },
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 8.dp),
                colors = ButtonDefaults.outlinedButtonColors(
                    containerColor = if (isSelected) MaterialTheme.colorScheme.secondaryContainer else Color.Transparent,
                    contentColor = cardSuitColor(suit)
                ),
                border = BorderStroke(
                    width = if (isSelected) 2.dp else 1.dp,
                    color = if (isSelected) cardSuitColor(suit) else MaterialTheme.colorScheme.outlineVariant
                )
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = "${if (isSelected) "✓ " else ""}${suit.symbol}",
                        style = MaterialTheme.typography.titleLarge,
                        fontWeight = FontWeight.Bold
                    )
                    Text(suit.displayName, style = MaterialTheme.typography.labelSmall)
                }
            }
        }
    }

    Text(
        "2. Tap the ${selectedSuit.displayName.lowercase()} card drawn",
        style = MaterialTheme.typography.titleSmall
    )

    selectedSuit.let { suit ->
        PlayingCard.RANKS.chunked(4).forEach { ranks ->
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                ranks.forEach { rank ->
                    val cardId = suit.ordinal * PlayingCard.RANKS.size + PlayingCard.RANKS.indexOf(rank)
                    val alreadyUsed = !withReplacement && cardId in selectedCardIds
                    OutlinedButton(
                        onClick = { onCardSelected(cardId) },
                        enabled = !alreadyUsed,
                        modifier = Modifier.weight(1f),
                        contentPadding = PaddingValues(horizontal = 6.dp, vertical = 10.dp)
                    ) {
                        Text(
                            text = "$rank${suit.symbol}",
                            color = if (alreadyUsed) {
                                MaterialTheme.colorScheme.onSurface.copy(alpha = 0.38f)
                            } else {
                                cardSuitColor(suit)
                            },
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                repeat(4 - ranks.size) { Spacer(modifier = Modifier.weight(1f)) }
            }
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
private fun CoinCaptureZone(
    label: String,
    shortcut: String,
    containerColor: Color,
    contentColor: Color,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Surface(
        onClick = onClick,
        shape = RoundedCornerShape(0.dp),
        color = containerColor,
        modifier = modifier.fillMaxHeight()
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
            modifier = Modifier.fillMaxSize()
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                color = contentColor
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = shortcut,
                style = MaterialTheme.typography.labelMedium,
                color = contentColor.copy(alpha = 0.85f)
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
