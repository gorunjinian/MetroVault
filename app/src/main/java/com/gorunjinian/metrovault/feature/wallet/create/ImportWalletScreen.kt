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
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import kotlinx.coroutines.launch
import kotlinx.coroutines.delay
import com.gorunjinian.metrovault.data.model.DerivationPaths
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ImportWalletScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    onWalletImported: () -> Unit
) {
    // Mnemonic input state
    var mnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentWord by remember { mutableStateOf("") }
    
    // Word count selection (12 or 24) - must be before LaunchedEffect that uses it
    var expectedWordCount by remember { mutableIntStateOf(12) }
    var bip39Passphrase by remember { mutableStateOf("") }
    var confirmBip39Passphrase by remember { mutableStateOf("") }
    var useBip39Passphrase by remember { mutableStateOf(false) }
    var savePassphraseLocally by remember { mutableStateOf(true) }  // toggle for saving passphrase
    var realtimeFingerprint by remember { mutableStateOf("") }     // live fingerprint preview
    var errorMessage by remember { mutableStateOf("") }
    var isImportingWallet by remember { mutableStateOf(false) }
    
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

    // Derivation Path Selection
    var selectedDerivationPath by remember { mutableStateOf(DerivationPaths.NATIVE_SEGWIT) }
    
    // Keyboard visibility toggle
    var isKeyboardVisible by remember { mutableStateOf(true) }

    // Focus requester for BIP39 passphrase confirmation field
    val confirmPassphraseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    val scope = rememberCoroutineScope()

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Import Wallet") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
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
            // Scrollable content area
            Column(
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 24.dp)
                    .padding(top = 16.dp),
                verticalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Text(
                    text = "Enter Seed Phrase",
                    style = MaterialTheme.typography.headlineSmall
                )
                
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Toggle switch for 12/24 words
                    Row(
                        modifier = Modifier
                            .weight(1f)
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
                                .clickable { 
                                    expectedWordCount = 12
                                    if (mnemonicWords.size > 12) {
                                        mnemonicWords = mnemonicWords.take(12)
                                    }
                                }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "12 words",
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
                                .clickable { expectedWordCount = 24 }
                                .padding(vertical = 8.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = "24 words",
                                style = MaterialTheme.typography.labelLarge,
                                color = if (expectedWordCount == 24) MaterialTheme.colorScheme.onPrimary
                                       else MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.width(8.dp))
                    
                    // Keyboard visibility toggle
                    FilledTonalIconButton(
                        onClick = { isKeyboardVisible = !isKeyboardVisible }
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
                        mnemonicWords = mnemonicWords.toMutableList().apply { removeAt(index) }
                    },
                    onClearAll = {
                        mnemonicWords = emptyList()
                        currentWord = ""
                    }
                )

                Spacer(modifier = Modifier.height(8.dp))
                
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
                            onClick = { selectedDerivationPath = path },
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
                                    onClick = { selectedDerivationPath = path }
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

                Spacer(modifier = Modifier.height(8.dp))

                Text(
                    text = "BIP39 Passphrase (Optional)",
                    style = MaterialTheme.typography.titleMedium
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
                        onCheckedChange = { useBip39Passphrase = it }
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Text("Use BIP39 passphrase")
                }

                if (useBip39Passphrase) {
                    SecureOutlinedTextField(
                        value = bip39Passphrase,
                        onValueChange = { bip39Passphrase = it },
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
                        onValueChange = { confirmBip39Passphrase = it },
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
                            onCheckedChange = { savePassphraseLocally = !it }
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

                Button(
                    onClick = {
                        when {
                            mnemonicWords.size != expectedWordCount -> {
                                errorMessage = "Please enter all $expectedWordCount words"
                            }
                            !validateMnemonic(mnemonicWords) -> {
                                errorMessage = "Invalid seed phrase"
                            }
                            useBip39Passphrase && bip39Passphrase != confirmBip39Passphrase -> {
                                errorMessage = "BIP39 passphrases do not match"
                            }
                            else -> {
                                val finalPassphrase = if (useBip39Passphrase) bip39Passphrase else ""
                                errorMessage = ""
                                isImportingWallet = true

                                scope.launch {
                                    val result = wallet.createWallet(
                                        name = "Imported Wallet",
                                        mnemonic = mnemonicWords,
                                        derivationPath = selectedDerivationPath,
                                        passphrase = finalPassphrase,
                                        savePassphraseLocally = savePassphraseLocally
                                    )

                                    isImportingWallet = false
                                    when (result) {
                                        is com.gorunjinian.metrovault.data.model.WalletCreationResult.Success -> {
                                            onWalletImported()
                                            mnemonicWords = emptyList()
                                            currentWord = ""
                                            bip39Passphrase = ""
                                            confirmBip39Passphrase = ""
                                        }
                                        is com.gorunjinian.metrovault.data.model.WalletCreationResult.Error -> {
                                            errorMessage = result.reason.message
                                        }
                                    }
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isImportingWallet && mnemonicWords.size == expectedWordCount
                ) {
                    if (isImportingWallet) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(24.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                            strokeWidth = 2.dp
                        )
                    } else {
                        Text("Import Wallet")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Secure keyboard at the bottom (visible when toggled on and not complete)
            if (isKeyboardVisible && mnemonicWords.size < expectedWordCount) {
                SecureMnemonicKeyboard(
                    currentWord = currentWord,
                    onKeyPress = { char ->
                        currentWord += char
                    },
                    onBackspace = {
                        if (currentWord.isNotEmpty()) {
                            currentWord = currentWord.dropLast(1)
                        } else if (mnemonicWords.isNotEmpty()) {
                            // Remove last word and put it back for editing
                            currentWord = mnemonicWords.last()
                            mnemonicWords = mnemonicWords.dropLast(1)
                        }
                    },
                    onWordSelected = { word ->
                        if (mnemonicWords.size < expectedWordCount) {
                            mnemonicWords = mnemonicWords + word
                            currentWord = ""
                        }
                    }
                )
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
