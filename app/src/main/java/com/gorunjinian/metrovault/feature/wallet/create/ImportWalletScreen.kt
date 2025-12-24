package com.gorunjinian.metrovault.feature.wallet.create

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Keyboard
import androidx.compose.material.icons.filled.KeyboardHide
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import kotlinx.coroutines.delay
import com.gorunjinian.metrovault.data.model.DerivationPaths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    viewModel: ImportWalletViewModel = viewModel(),
    onBack: () -> Unit,
    onWalletImported: () -> Unit
) {
    val uiState by viewModel.uiState.collectAsState()
    
    // Focus requester for BIP39 passphrase confirmation field
    val confirmPassphraseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

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
        delay(150)  // Debounce
        viewModel.updateRealtimeFingerprint()
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
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
                    expectedWordCount = uiState.expectedWordCount,
                    selectedDerivationPath = uiState.selectedDerivationPath,
                    accountNumber = uiState.accountNumber,
                    isTestnet = uiState.isTestnet,
                    onWordCountChange = { viewModel.setWordCount(it) },
                    onDerivationPathChange = { viewModel.setDerivationPath(it) },
                    onAccountNumberChange = { viewModel.setAccountNumber(it) },
                    onTestnetChange = { viewModel.setTestnetMode(it) },
                    onNext = { viewModel.goToNextStep() }
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
                
                3 -> Step3Passphrase(
                    useBip39Passphrase = uiState.useBip39Passphrase,
                    bip39Passphrase = uiState.bip39Passphrase,
                    confirmBip39Passphrase = uiState.confirmBip39Passphrase,
                    savePassphraseLocally = uiState.savePassphraseLocally,
                    realtimeFingerprint = uiState.realtimeFingerprint,
                    errorMessage = uiState.errorMessage,
                    isImportingWallet = uiState.isImportingWallet,
                    confirmPassphraseFocusRequester = confirmPassphraseFocusRequester,
                    keyboardController = keyboardController,
                    onUsePassphraseChange = { viewModel.setUseBip39Passphrase(it) },
                    onPassphraseChange = { viewModel.setBip39Passphrase(it) },
                    onConfirmPassphraseChange = { viewModel.setConfirmBip39Passphrase(it) },
                    onSavePassphraseLocallyChange = { viewModel.setSavePassphraseLocally(it) },
                    onImportWallet = { viewModel.importWallet() }
                )
            }
        }
    }
}


// ========== Step 1: Configuration ==========

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun Step1Configuration(
    expectedWordCount: Int,
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
                        if (expectedWordCount == 12) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onWordCountChange(12) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "12 Words",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (expectedWordCount == 12) MaterialTheme.colorScheme.onPrimary
                           else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            
            // 24 Words option
            Box(
                modifier = Modifier
                    .weight(1f)
                    .clip(RoundedCornerShape(6.dp))
                    .background(
                        if (expectedWordCount == 24) MaterialTheme.colorScheme.primary
                        else Color.Transparent
                    )
                    .clickable { onWordCountChange(24) }
                    .padding(vertical = 10.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = "24 Words",
                    style = MaterialTheme.typography.labelLarge,
                    color = if (expectedWordCount == 24) MaterialTheme.colorScheme.onPrimary
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
                    Triple("Taproot (Bech32m)", "tb1p...", DerivationPaths.TAPROOT_TESTNET),
                    Triple("Native SegWit (Bech32)", "tb1q...", DerivationPaths.NATIVE_SEGWIT_TESTNET),
                    Triple("Nested SegWit", "2...", DerivationPaths.NESTED_SEGWIT_TESTNET),
                    Triple("Legacy", "m/n...", DerivationPaths.LEGACY_TESTNET)
                )
            } else {
                listOf(
                    Triple("Taproot (Bech32m)", "bc1p...", DerivationPaths.TAPROOT),
                    Triple("Native SegWit (Bech32)", "bc1q...", DerivationPaths.NATIVE_SEGWIT),
                    Triple("Nested SegWit", "3...", DerivationPaths.NESTED_SEGWIT),
                    Triple("Legacy", "1...", DerivationPaths.LEGACY)
                )
            }
            
            val currentPurpose = DerivationPaths.getPurpose(selectedDerivationPath)
            val selectedOption = options.find { DerivationPaths.getPurpose(it.third) == currentPurpose } ?: options[1]
            
            ExposedDropdownMenuBox(
                expanded = addressTypeExpanded,
                onExpandedChange = { addressTypeExpanded = it }
            ) {
                OutlinedTextField(
                    value = "${selectedOption.first} (${selectedOption.second})",
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Address Type") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = addressTypeExpanded) },
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
    val isMnemonicValid = mnemonicWords.size == expectedWordCount && validateMnemonic(mnemonicWords)
    var validationError by remember { mutableStateOf("") }

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
                        imageVector = if (isKeyboardVisible) Icons.Default.KeyboardHide else Icons.Default.Keyboard,
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

        }

        // Pin button to bottom of content area (above keyboard)
        Column(
            modifier = Modifier
                .padding(horizontal = 24.dp)
                .padding(bottom = 16.dp)
        ) {
            if (validationError.isNotEmpty()) {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.errorContainer
                    )
                ) {
                    Text(
                        text = validationError,
                        modifier = Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

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
        if (isKeyboardVisible && mnemonicWords.size < expectedWordCount) {
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

// ========== Step 3: Passphrase and Import ==========

@Composable
private fun Step3Passphrase(
    useBip39Passphrase: Boolean,
    bip39Passphrase: String,
    confirmBip39Passphrase: String,
    savePassphraseLocally: Boolean,
    realtimeFingerprint: String,
    errorMessage: String,
    isImportingWallet: Boolean,
    confirmPassphraseFocusRequester: FocusRequester,
    keyboardController: androidx.compose.ui.platform.SoftwareKeyboardController?,
    onUsePassphraseChange: (Boolean) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirmPassphraseChange: (String) -> Unit,
    onSavePassphraseLocallyChange: (Boolean) -> Unit,
    onImportWallet: () -> Unit
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
                    text = "If your seed phrase has a BIP39 passphrase (25th word), enable it here",
                    style = MaterialTheme.typography.bodyMedium
                )
                Text(
                    text = "The passphrase is shown in plain text to avoid typos",
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
            Text("Use BIP39 passphrase")
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
                            color = MaterialTheme.colorScheme.primary
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
                            text = "You will need to re-enter this passphrase every time you open the app.",
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
            onClick = { onImportWallet() },
            modifier = Modifier.fillMaxWidth(),
            enabled = !isImportingWallet
        ) {
            if (isImportingWallet) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(if (useBip39Passphrase) "Import Wallet with Passphrase" else "Import Wallet")
            }
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
