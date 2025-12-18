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
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.gorunjinian.metrovault.data.model.DerivationPaths

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    onWalletImported: () -> Unit
) {
    // Step navigation (1 = Configuration, 2 = Seed Phrase, 3 = Passphrase)
    var currentStep by remember { mutableIntStateOf(1) }
    
    // Step 1: Configuration state
    var expectedWordCount by remember { mutableIntStateOf(12) }
    var selectedDerivationPath by remember { mutableStateOf(DerivationPaths.NATIVE_SEGWIT) }
    var accountNumber by remember { mutableIntStateOf(0) }
    
    // Step 2: Mnemonic input state
    var mnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentWord by remember { mutableStateOf("") }
    var isKeyboardVisible by remember { mutableStateOf(true) }
    
    // Step 3: Passphrase state
    var useBip39Passphrase by remember { mutableStateOf(false) }
    var bip39Passphrase by remember { mutableStateOf("") }
    var confirmBip39Passphrase by remember { mutableStateOf("") }
    var savePassphraseLocally by remember { mutableStateOf(true) }
    var realtimeFingerprint by remember { mutableStateOf("") }
    var errorMessage by remember { mutableStateOf("") }
    var isImportingWallet by remember { mutableStateOf(false) }
    
    // Focus requester for BIP39 passphrase confirmation field
    val confirmPassphraseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val scope = rememberCoroutineScope()
    
    // Calculate fingerprint in real-time when passphrase or mnemonic changes
    LaunchedEffect(mnemonicWords, bip39Passphrase, useBip39Passphrase) {
        if (mnemonicWords.size == expectedWordCount && validateMnemonic(mnemonicWords)) {
            delay(150)  // Debounce
            val passphrase = if (useBip39Passphrase) bip39Passphrase else ""
            realtimeFingerprint = wallet.calculateFingerprint(mnemonicWords, passphrase) ?: ""
        } else {
            realtimeFingerprint = ""
        }
    }
    
    // Handle back navigation
    fun handleBack() {
        when (currentStep) {
            1 -> onBack()
            2 -> currentStep = 1
            3 -> currentStep = 2
        }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
                navigationIcon = {
                    IconButton(onClick = { handleBack() }) {
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
            when (currentStep) {
                1 -> Step1Configuration(
                    expectedWordCount = expectedWordCount,
                    selectedDerivationPath = selectedDerivationPath,
                    accountNumber = accountNumber,
                    onWordCountChange = { expectedWordCount = it },
                    onDerivationPathChange = { selectedDerivationPath = it },
                    onAccountNumberChange = { accountNumber = it },
                    onNext = { currentStep = 2 }
                )
                
                2 -> Step2SeedPhrase(
                    mnemonicWords = mnemonicWords,
                    currentWord = currentWord,
                    expectedWordCount = expectedWordCount,
                    isKeyboardVisible = isKeyboardVisible,
                    onMnemonicWordsChange = { mnemonicWords = it },
                    onCurrentWordChange = { currentWord = it },
                    onKeyboardVisibilityChange = { isKeyboardVisible = it },
                    onNext = { currentStep = 3 }
                )
                
                3 -> Step3Passphrase(
                    wallet = wallet,
                    mnemonicWords = mnemonicWords,
                    selectedDerivationPath = selectedDerivationPath,
                    accountNumber = accountNumber,
                    useBip39Passphrase = useBip39Passphrase,
                    bip39Passphrase = bip39Passphrase,
                    confirmBip39Passphrase = confirmBip39Passphrase,
                    savePassphraseLocally = savePassphraseLocally,
                    realtimeFingerprint = realtimeFingerprint,
                    errorMessage = errorMessage,
                    isImportingWallet = isImportingWallet,
                    confirmPassphraseFocusRequester = confirmPassphraseFocusRequester,
                    keyboardController = keyboardController,
                    onUsePassphraseChange = { useBip39Passphrase = it },
                    onPassphraseChange = { bip39Passphrase = it },
                    onConfirmPassphraseChange = { confirmBip39Passphrase = it },
                    onSavePassphraseLocallyChange = { savePassphraseLocally = it },
                    onErrorMessageChange = { errorMessage = it },
                    onIsImportingChange = { isImportingWallet = it },
                    onWalletImported = {
                        // Clear sensitive data
                        mnemonicWords = emptyList()
                        currentWord = ""
                        bip39Passphrase = ""
                        confirmBip39Passphrase = ""
                        onWalletImported()
                    }
                )
            }
        }
    }
}

// ========== Step 1: Configuration ==========

@Composable
private fun Step1Configuration(
    expectedWordCount: Int,
    selectedDerivationPath: String,
    accountNumber: Int,
    onWordCountChange: (Int) -> Unit,
    onDerivationPathChange: (String) -> Unit,
    onAccountNumberChange: (Int) -> Unit,
    onNext: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Text(
            text = "Select Seed Phrase Length",
            style = MaterialTheme.typography.headlineSmall
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

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Address Type",
            style = MaterialTheme.typography.titleMedium
        )

        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            val options = listOf(
                Triple("Taproot (Bech32m)", "bc1p...", DerivationPaths.TAPROOT),
                Triple("Native SegWit (Bech32)", "bc1q...", DerivationPaths.NATIVE_SEGWIT),
                Triple("Nested SegWit", "3...", DerivationPaths.NESTED_SEGWIT),
                Triple("Legacy", "1...", DerivationPaths.LEGACY)
            )

            options.forEach { (label, example, path) ->
                Card(
                    onClick = { onDerivationPathChange(path) },
                    colors = CardDefaults.cardColors(
                        containerColor = if (selectedDerivationPath == path)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    ),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        RadioButton(
                            selected = selectedDerivationPath == path,
                            onClick = { onDerivationPathChange(path) }
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Column {
                            Text(text = label, style = MaterialTheme.typography.bodyLarge)
                            Text(
                                text = "Example: $example",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
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
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedTextField(
                    value = if (accountNumber == 0) "" else accountNumber.toString(),
                    onValueChange = { value ->
                        val num = value.filter { it.isDigit() }.take(4).toIntOrNull() ?: 0
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

        Spacer(modifier = Modifier.weight(1f))

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
            
            Spacer(modifier = Modifier.height(8.dp))
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
                    if (mnemonicWords.size < expectedWordCount) {
                        onMnemonicWordsChange(mnemonicWords + word)
                        onCurrentWordChange("")
                    }
                }
            )
        }
    }
}

// ========== Step 3: Passphrase and Import ==========

@Composable
private fun Step3Passphrase(
    wallet: Wallet,
    mnemonicWords: List<String>,
    selectedDerivationPath: String,
    accountNumber: Int,
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
    onErrorMessageChange: (String) -> Unit,
    onIsImportingChange: (Boolean) -> Unit,
    onWalletImported: () -> Unit
) {
    val scope = rememberCoroutineScope()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
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
                    text = "⚠️ The passphrase is shown in plain text to avoid typos",
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
                            text = "⚠️ You will need to re-enter this passphrase every time you open the app.",
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

        Spacer(modifier = Modifier.weight(1f))

        Button(
            onClick = {
                when {
                    useBip39Passphrase && bip39Passphrase != confirmBip39Passphrase -> {
                        onErrorMessageChange("BIP39 passphrases do not match")
                    }
                    else -> {
                        val finalPassphrase = if (useBip39Passphrase) bip39Passphrase else ""
                        onErrorMessageChange("")
                        onIsImportingChange(true)

                        scope.launch {
                            val result = wallet.createWallet(
                                name = "Imported Wallet",
                                mnemonic = mnemonicWords,
                                derivationPath = selectedDerivationPath,
                                passphrase = finalPassphrase,
                                savePassphraseLocally = savePassphraseLocally,
                                accountNumber = accountNumber
                            )

                            onIsImportingChange(false)
                            when (result) {
                                is com.gorunjinian.metrovault.data.model.WalletCreationResult.Success -> {
                                    onWalletImported()
                                }
                                is com.gorunjinian.metrovault.data.model.WalletCreationResult.Error -> {
                                    onErrorMessageChange(result.reason.message)
                                }
                            }
                        }
                    }
                }
            },
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
