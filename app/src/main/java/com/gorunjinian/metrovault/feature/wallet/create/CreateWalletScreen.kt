package com.gorunjinian.metrovault.feature.wallet.create

import android.annotation.SuppressLint
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.R
import androidx.compose.ui.res.painterResource
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
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

    // Focus requester for BIP39 passphrase confirmation field
    val confirmPassphraseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
            TopAppBar(
                title = { Text("Create Wallet") },
                navigationIcon = {
                    IconButton(onClick = { viewModel.goToPreviousStep() }) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            when (uiState.currentStep) {
                1 -> Step1Configuration(
                    wordCount = uiState.wordCount,
                    selectedDerivationPath = uiState.selectedDerivationPath,
                    accountNumber = uiState.accountNumber,
                    isTestnet = uiState.isTestnet,
                    onWordCountChange = { viewModel.setWordCount(it) },
                    onDerivationPathChange = { viewModel.setDerivationPath(it) },
                    onAccountNumberChange = { viewModel.setAccountNumber(it) },
                    onTestnetChange = { viewModel.setTestnetMode(it) },
                    onNext = { viewModel.goToNextStep() }
                )

                2 -> Step2Entropy(
                    entropyType = uiState.entropyType,
                    collectedEntropy = uiState.collectedEntropy,
                    entropyProgress = uiState.entropyProgress,
                    bytesCollected = uiState.bytesCollected,
                    requiredEntropyBytes = uiState.requiredEntropyBytes,
                    entropyInputCount = uiState.entropyInputCount,
                    onEntropyTypeChange = { viewModel.setEntropyType(it) },
                    onAddEntropy = { viewModel.addEntropyInput(it) },
                    onResetEntropy = { viewModel.resetEntropy() },
                    onRevealSeed = { viewModel.showSecurityWarning() }
                )

                3 -> Step3SeedPhrase(
                    generatedMnemonic = uiState.generatedMnemonic,
                    onContinue = { viewModel.goToNextStep() }
                )

                4 -> Step4Passphrase(
                    useBip39Passphrase = uiState.useBip39Passphrase,
                    bip39Passphrase = uiState.bip39Passphrase,
                    confirmBip39Passphrase = uiState.confirmBip39Passphrase,
                    savePassphraseLocally = uiState.savePassphraseLocally,
                    realtimeFingerprint = uiState.realtimeFingerprint,
                    errorMessage = uiState.errorMessage,
                    isCreatingWallet = uiState.isCreatingWallet,
                    confirmPassphraseFocusRequester = confirmPassphraseFocusRequester,
                    keyboardController = keyboardController,
                    onUsePassphraseChange = { viewModel.setUseBip39Passphrase(it) },
                    onPassphraseChange = { 
                        viewModel.setBip39Passphrase(it)
                        viewModel.updateRealtimeFingerprint()
                    },
                    onConfirmPassphraseChange = { viewModel.setConfirmBip39Passphrase(it) },
                    onSavePassphraseLocallyChange = { viewModel.setSavePassphraseLocally(it) },
                    onCreateWallet = { viewModel.createWallet() }
                )
            }
        }
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

// ========== Step 1: Configuration ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step1Configuration(
    wordCount: Int,
    selectedDerivationPath: String,
    accountNumber: Int,
    isTestnet: Boolean,
    onWordCountChange: (Int) -> Unit,
    onDerivationPathChange: (String) -> Unit,
    onAccountNumberChange: (Int) -> Unit,
    onTestnetChange: (Boolean) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
        // Title row with testnet toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Seed Phrase Length",
                style = MaterialTheme.typography.headlineSmall
            )
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Testnet",
                    style = MaterialTheme.typography.labelMedium,
                    color = if (isTestnet) MaterialTheme.colorScheme.primary 
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))
                Switch(
                    checked = isTestnet,
                    onCheckedChange = onTestnetChange
                )
            }
        }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // 12 Words option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (wordCount == 12) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onWordCountChange(12) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "12 Words",
                style = MaterialTheme.typography.labelLarge,
                color = if (wordCount == 12) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // 24 Words option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (wordCount == 24) MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onWordCountChange(24) }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "24 Words",
                style = MaterialTheme.typography.labelLarge,
                color = if (wordCount == 24) MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

    Text(
        text = "Address Type",
        style = MaterialTheme.typography.titleMedium
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "Select the Bitcoin address type for this wallet.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            
            // Address type dropdown
            var addressTypeExpanded by remember { mutableStateOf(false) }
            
            // Dynamic options based on testnet mode
            val options = if (isTestnet) {
                listOf(
                    Triple("Taproot", "tb1p...", DerivationPaths.TAPROOT_TESTNET),
                    Triple("Native SegWit", "tb1q...", DerivationPaths.NATIVE_SEGWIT_TESTNET),
                    Triple("Nested SegWit", "2...", DerivationPaths.NESTED_SEGWIT_TESTNET),
                    Triple("Legacy", "m/n...", DerivationPaths.LEGACY_TESTNET)
                )
            } else {
                listOf(
                    Triple("Taproot", "bc1p...", DerivationPaths.TAPROOT),
                    Triple("Native SegWit", "bc1q...", DerivationPaths.NATIVE_SEGWIT),
                    Triple("Nested SegWit", "3...", DerivationPaths.NESTED_SEGWIT),
                    Triple("Legacy", "1...", DerivationPaths.LEGACY)
                )
            }
            
            val currentPurpose = DerivationPaths.getPurpose(selectedDerivationPath)
            val selectedOption = options.find { DerivationPaths.getPurpose(it.third) == currentPurpose } ?: options[1]
            val isDefaultAddressType = currentPurpose == 84 // Native SegWit is the default
            
            ExposedDropdownMenuBox(
                expanded = addressTypeExpanded,
                onExpandedChange = { addressTypeExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedOption.first,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Address Format") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = addressTypeExpanded) },
                    suffix = if (isDefaultAddressType) {
                        {
                            Surface(
                                color = MaterialTheme.colorScheme.tertiaryContainer,
                                contentColor = MaterialTheme.colorScheme.onTertiaryContainer,
                                shape = RoundedCornerShape(4.dp)
                            ) {
                                Text(
                                    text = "Default",
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp),
                                    style = MaterialTheme.typography.labelSmall,
                                    fontWeight = FontWeight.Medium
                                )
                            }
                        }
                    } else null,
                    modifier = Modifier
                        .fillMaxWidth()
                        .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryNotEditable, true)
                )
                ExposedDropdownMenu(
                    expanded = addressTypeExpanded,
                    onDismissRequest = { addressTypeExpanded = false }
                ) {
                    options.forEachIndexed { index, (label, example, path) ->
                        DropdownMenuItem(
                            text = {
                                Column(
                                    modifier = Modifier.padding(vertical = 4.dp)
                                ) {
                                    Text(
                                        text = label,
                                        style = MaterialTheme.typography.titleMedium
                                    )
                                    Text(
                                        text = "Example: $example",
                                        style = MaterialTheme.typography.bodyMedium,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            },
                            onClick = {
                                onDerivationPathChange(path)
                                addressTypeExpanded = false
                            },
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 12.dp)
                        )
                        if (index < options.size - 1) {
                            HorizontalDivider(
                                modifier = Modifier.padding(horizontal = 16.dp),
                                color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                            )
                        }
                    }
                }
            }
        }
    }

    Spacer(modifier = Modifier.height(4.dp))

    // Account Number
    Text(
        text = "Account Number (Advanced)",
        style = MaterialTheme.typography.titleMedium
    )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Text(
                text = "BIP44 account index in derivation path. Default is 0.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.height(12.dp))
            OutlinedTextField(
                value = accountNumber.toString(),
                onValueChange = { value ->
                    val num = value.filter { it.isDigit() }.take(2).toIntOrNull() ?: 0
                    onAccountNumberChange(num)
                },
                label = { Text("Account Number") },
                placeholder = { Text("0") },
                modifier = Modifier.fillMaxWidth(),
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
            )
        }
    }

        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
        onClick = onNext,
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("Next")
    }
    }
}

// ========== Step 2: Entropy ==========

@SuppressLint("DefaultLocale")
@Composable
private fun Step2Entropy(
    entropyType: String,
    collectedEntropy: List<Int>,
    entropyProgress: Float,
    bytesCollected: Double,
    requiredEntropyBytes: Int,
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Text(
            text = "Add your own randomness to the wallet generation. This is optional but can provide additional security assurance.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }

    Spacer(modifier = Modifier.height(8.dp))

    Text(
        text = "Choose Entropy Source",
        style = MaterialTheme.typography.titleMedium
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                MaterialTheme.colorScheme.surfaceVariant,
                RoundedCornerShape(8.dp)
            )
            .padding(4.dp),
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically
    ) {
        // Coin Toss option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (entropyType == "coin") MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onEntropyTypeChange("coin") }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Coin Toss",
                style = MaterialTheme.typography.labelLarge,
                color = if (entropyType == "coin") MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        
        // Dice Rolls option
        Box(
            modifier = Modifier
                .weight(1f)
                .clip(RoundedCornerShape(6.dp))
                .background(
                    if (entropyType == "dice") MaterialTheme.colorScheme.primary
                    else Color.Transparent
                )
                .clickable { onEntropyTypeChange("dice") }
                .padding(vertical = 10.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                text = "Dice Rolls",
                style = MaterialTheme.typography.labelLarge,
                color = if (entropyType == "dice") MaterialTheme.colorScheme.onPrimary
                       else MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }

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
                                Icons.Default.Refresh,
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
                    text = "${String.format("%.1f", bytesCollected)} bytes collected ($requiredEntropyBytes bytes recommended)",
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

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer
        )
    ) {
        Text(
            text = "Write down your seed phrase and keep it safe. Never share it with anyone.",
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodyMedium
        )
    }

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

// ========== Step 4: Passphrase ==========

@Composable
private fun Step4Passphrase(
    useBip39Passphrase: Boolean,
    bip39Passphrase: String,
    confirmBip39Passphrase: String,
    savePassphraseLocally: Boolean,
    realtimeFingerprint: String,
    errorMessage: String,
    isCreatingWallet: Boolean,
    confirmPassphraseFocusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onUsePassphraseChange: (Boolean) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirmPassphraseChange: (String) -> Unit,
    onSavePassphraseLocallyChange: (Boolean) -> Unit,
    onCreateWallet: () -> Unit
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
            text = "BIP39 Passphrase (Optional)",
            style = MaterialTheme.typography.headlineSmall
        )

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = "Add an extra passphrase for additional security",
                style = MaterialTheme.typography.bodyMedium
            )
            Text(
                text = "WARNING: A single typo creates a completely different wallet. The passphrase is shown in plain text so you can verify it carefully.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error
            )
        }
    }

    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Switch(
            checked = useBip39Passphrase,
            onCheckedChange = onUsePassphraseChange
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text("Use BIP39 passphrase (25th word)")
    }

    if (useBip39Passphrase) {
        SecureOutlinedTextField(
            value = bip39Passphrase,
            onValueChange = onPassphraseChange,
            label = { Text("BIP39 Passphrase (visible)") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            isPasswordField = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
            keyboardActions = KeyboardActions(
                onNext = { confirmPassphraseFocusRequester.requestFocus() }
            )
        )

        SecureOutlinedTextField(
            value = confirmBip39Passphrase,
            onValueChange = onConfirmPassphraseChange,
            label = { Text("Confirm BIP39 Passphrase") },
            modifier = Modifier
                .fillMaxWidth()
                .focusRequester(confirmPassphraseFocusRequester),
            singleLine = true,
            isPasswordField = true,
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(
                onDone = { keyboardController?.hide() }
            )
        )

        // Write down passphrase reminder (moved before fingerprint)
        if (bip39Passphrase.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Text(
                    text = "Write down your passphrase: \"$bip39Passphrase\"",
                    modifier = Modifier.padding(16.dp),
                    style = MaterialTheme.typography.bodyMedium
                )
            }
        }

        // Real-time fingerprint preview
        if (realtimeFingerprint.isNotEmpty()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Row(
                    modifier = Modifier.padding(12.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = "Master Fingerprint: ",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    Text(
                        text = realtimeFingerprint,
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace,
                        fontWeight = FontWeight.Bold,
                        color = MaterialTheme.colorScheme.onSecondaryContainer
                    )
                }
            }
        }

        // "Don't save passphrase" toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Switch(
                checked = !savePassphraseLocally,
                onCheckedChange = { onSavePassphraseLocallyChange(!it) }
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Don't save passphrase on device",
                style = MaterialTheme.typography.bodyMedium
            )
        }

        // Warning when "don't save" is enabled
        if (!savePassphraseLocally) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        text = "You will need to re-enter this passphrase every time you open this wallet.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "If you enter a different passphrase later, the Master Fingerprint will be displayed in red to indicate it does not match the original.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onErrorContainer.copy(alpha = 0.8f)
                    )
                }
            }
        }
    }

    if (errorMessage.isNotEmpty()) {
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.errorContainer
            )
        ) {
            Text(
                text = errorMessage,
                modifier = Modifier.padding(16.dp),
                color = MaterialTheme.colorScheme.onErrorContainer
            )
        }
    }

        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
        onClick = onCreateWallet,
        modifier = Modifier.fillMaxWidth(),
        enabled = !isCreatingWallet
    ) {
        if (isCreatingWallet) {
            CircularProgressIndicator(
                modifier = Modifier.size(24.dp),
                color = MaterialTheme.colorScheme.onPrimary,
                strokeWidth = 2.dp
            )
        } else {
            Text(if (useBip39Passphrase) "Create Wallet with Passphrase" else "Create Wallet")
        }
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
