package com.gorunjinian.metrovault.feature.wallet.create

import android.Manifest
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.qr.SeedQRUtils
import com.gorunjinian.metrovault.core.qr.configureForQRScanning
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.journeyapps.barcodescanner.CompoundBarcodeView

/**
 * Steps shared by the Create, Import, and Stateless wallet wizards.
 *
 * Each wizard previously carried its own near-verbatim copy of the configuration
 * and passphrase steps; the differences that remain are expressed as parameters
 * and content slots here.
 */

// ========== Configuration step ==========

/**
 * Wallet configuration step: optional 12/24-word picker, address type, and
 * account number, with a testnet toggle in the title row.
 *
 * @param wordCount when null the word-count picker (and the separate
 *   "Address Type" section title) is omitted.
 * @param includeSilentPayments whether Silent Payments appears as an address type.
 * @param topContent optional content rendered above the title row.
 */
@Composable
internal fun WalletConfigurationStep(
    title: String,
    selectedDerivationPath: String,
    accountNumber: Int,
    isTestnet: Boolean,
    includeSilentPayments: Boolean,
    onDerivationPathChange: (String) -> Unit,
    onAccountNumberChange: (Int) -> Unit,
    onTestnetChange: (Boolean) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier,
    wordCount: Int? = null,
    onWordCountChange: (Int) -> Unit = {},
    topContent: (@Composable () -> Unit)? = null
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(start = 24.dp, end = 24.dp, top = 8.dp, bottom = 24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            topContent?.invoke()

            // Title row with testnet toggle
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.headlineSmall
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
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

            if (wordCount != null) {
                SegmentedToggle(
                    firstOption = "12 Words",
                    secondOption = "24 Words",
                    isSecondSelected = wordCount == 24,
                    onSelectFirst = { onWordCountChange(12) },
                    onSelectSecond = { onWordCountChange(24) },
                    modifier = Modifier.fillMaxWidth()
                )

                Text(
                    text = "Address Type",
                    style = MaterialTheme.typography.titleMedium
                )
            }

            AddressTypeCard(
                selectedDerivationPath = selectedDerivationPath,
                isTestnet = isTestnet,
                includeSilentPayments = includeSilentPayments,
                onDerivationPathChange = onDerivationPathChange
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = "Account Number (Advanced)",
                style = MaterialTheme.typography.titleMedium
            )

            AccountNumberCard(
                accountNumber = accountNumber,
                onAccountNumberChange = onAccountNumberChange
            )
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

private fun addressTypeOptions(
    isTestnet: Boolean,
    includeSilentPayments: Boolean
): List<Triple<String, String, String>> {
    val base = if (isTestnet) {
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
    if (!includeSilentPayments) return base
    return base + if (isTestnet) {
        Triple("Silent Payments", "tsp1q...", DerivationPaths.SILENT_PAYMENT_TESTNET)
    } else {
        Triple("Silent Payments", "sp1q...", DerivationPaths.SILENT_PAYMENT)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun AddressTypeCard(
    selectedDerivationPath: String,
    isTestnet: Boolean,
    includeSilentPayments: Boolean,
    onDerivationPathChange: (String) -> Unit
) {
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

            var addressTypeExpanded by remember { mutableStateOf(false) }

            val options = addressTypeOptions(isTestnet, includeSilentPayments)
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
                    label = { Text("Address Type") },
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
                        if (index != options.lastIndex) {
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
}

@Composable
private fun AccountNumberCard(
    accountNumber: Int,
    onAccountNumberChange: (Int) -> Unit
) {
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

// ========== Passphrase step ==========

/**
 * BIP39 passphrase step: opt-in switch, passphrase + confirmation fields,
 * live fingerprint preview, and the submit button.
 *
 * @param savePassphraseLocally when null the "Don't save passphrase on device"
 *   toggle and its warning are omitted (stateless wallets never persist it).
 * @param realtimeFingerprint the inline fingerprint card is hidden when empty.
 * @param showWriteDownReminder shows the "Write down your passphrase" card while
 *   a passphrase is entered (used when creating a brand-new passphrase).
 * @param onConfirmDone IME Done action on the confirmation field; defaults to
 *   hiding the keyboard.
 * @param topContent optional content rendered above the step title.
 * @param aboveButtonContent optional content pinned between the scrollable
 *   content and the submit button.
 */
@Composable
internal fun Bip39PassphraseStep(
    infoPrimaryText: String,
    useBip39Passphrase: Boolean,
    bip39Passphrase: String,
    confirmBip39Passphrase: String,
    realtimeFingerprint: String,
    errorMessage: String,
    isSubmitting: Boolean,
    submitLabel: String,
    onUsePassphraseChange: (Boolean) -> Unit,
    onPassphraseChange: (String) -> Unit,
    onConfirmPassphraseChange: (String) -> Unit,
    onSubmit: () -> Unit,
    modifier: Modifier = Modifier,
    infoSecondaryText: String? = null,
    showWriteDownReminder: Boolean = false,
    savePassphraseLocally: Boolean? = null,
    onSavePassphraseLocallyChange: (Boolean) -> Unit = {},
    onConfirmDone: (() -> Unit)? = null,
    topContent: (@Composable () -> Unit)? = null,
    aboveButtonContent: (@Composable () -> Unit)? = null
) {
    val confirmPassphraseFocusRequester = remember { FocusRequester() }
    val keyboardController = LocalSoftwareKeyboardController.current

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(24.dp)
    ) {
        Column(
            modifier = Modifier
                .weight(1f)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            topContent?.invoke()

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
                        text = infoPrimaryText,
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (infoSecondaryText != null) {
                        Text(
                            text = infoSecondaryText,
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.error
                        )
                    }
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
                        onDone = { onConfirmDone?.invoke() ?: keyboardController?.hide() }
                    )
                )

                if (showWriteDownReminder && bip39Passphrase.isNotEmpty()) {
                    InfoCard(
                        text = "Write down your passphrase: \"$bip39Passphrase\"",
                        tone = InfoTone.Info,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
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

                if (savePassphraseLocally != null) {
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
            }

            if (errorMessage.isNotEmpty()) {
                InfoCard(
                    text = errorMessage,
                    tone = InfoTone.Danger
                )
            }
        }

        if (aboveButtonContent != null) {
            Spacer(modifier = Modifier.height(16.dp))
            aboveButtonContent()
        }

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onSubmit,
            modifier = Modifier.fillMaxWidth(),
            enabled = !isSubmitting
        ) {
            if (isSubmitting) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
            } else {
                Text(submitLabel)
            }
        }
    }
}

// ========== Seed phrase entry step ==========

/**
 * Seed phrase entry step: word chips fed by the secure on-screen keyboard, plus an
 * inline SeedQR scanner. Shared by the Import and Stateless wizards.
 *
 * A scanned SeedQR of either supported length (12 or 24 words) is accepted regardless
 * of [expectedWordCount]; the caller receives the words via [onSeedQrScanned] and is
 * expected to adopt their length as the new expected word count.
 */
@Composable
internal fun SeedPhraseEntryStep(
    mnemonicWords: List<String>,
    currentWord: String,
    expectedWordCount: Int,
    isKeyboardVisible: Boolean,
    onMnemonicWordsChange: (List<String>) -> Unit,
    onCurrentWordChange: (String) -> Unit,
    onKeyboardVisibilityChange: (Boolean) -> Unit,
    onAddWord: (String) -> Unit,
    onSeedQrScanned: (List<String>) -> Unit,
    onNext: () -> Unit,
    modifier: Modifier = Modifier
) {
    val haptic = LocalHapticFeedback.current
    val lifecycleOwner = LocalLifecycleOwner.current

    // The scanner callback is registered once in the AndroidView factory; read the
    // latest handler through state so a recomposition can't leave it stale.
    val currentOnSeedQrScanned by rememberUpdatedState(onSeedQrScanned)

    var validationError by remember { mutableStateOf("") }

    // SeedQR Scanner state
    var isScanning by remember { mutableStateOf(false) }
    var barcodeView: CompoundBarcodeView? by remember { mutableStateOf(null) }

    // Camera permission launcher
    val permissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { isGranted ->
        if (isGranted) {
            isScanning = true
        } else {
            validationError = "Camera permission is required to scan SeedQR codes"
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
        modifier = modifier.fillMaxSize()
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
                                            // Raw bytes are needed for CompactSeedQR (binary data gets corrupted in text)
                                            val decodedWords = SeedQRUtils.decodeSeedQR(scannedText, result.rawBytes)

                                            if (decodedWords != null) {
                                                // Either supported length is accepted; the caller adopts it
                                                validationError = ""
                                                currentOnSeedQrScanned(decodedWords)
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
                        !isValidBip39Mnemonic(mnemonicWords) -> {
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
