package com.gorunjinian.metrovault.feature.wallet.create

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.vaultovich.MnemonicCode
import com.gorunjinian.vaultovich.BIP39Wordlist
import com.gorunjinian.metrovault.core.ui.components.MnemonicInputField
import com.gorunjinian.metrovault.core.ui.components.SecureMnemonicKeyboard
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CompleteMnemonicScreen(
    onBack: () -> Unit
) {
    
    // Mnemonic input state
    var mnemonicWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var currentWord by remember { mutableStateOf("") }
    
    var possibleWords by remember { mutableStateOf<List<String>>(emptyList()) }
    var errorMessage by remember { mutableStateOf("") }
    var isCalculating by remember { mutableStateOf(false) }
    val scope = rememberCoroutineScope()
    
    // Word count selection (11 or 23 - one less than full mnemonic)
    var expectedWordCount by remember { mutableIntStateOf(11) }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Complete Mnemonic",
                onBack = onBack
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
                    text = "Calculate Last Word",
                    style = MaterialTheme.typography.headlineSmall
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "Enter the first $expectedWordCount words of your seed phrase to calculate all possible checksum words.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }
                
                // Word count selector
                SegmentedToggle(
                    firstOption = "11 words",
                    secondOption = "23 words",
                    isSecondSelected = expectedWordCount == 23,
                    onSelectFirst = {
                        expectedWordCount = 11
                        if (mnemonicWords.size > 11) {
                            mnemonicWords = mnemonicWords.take(11)
                        }
                        possibleWords = emptyList()
                    },
                    onSelectSecond = {
                        expectedWordCount = 23
                        possibleWords = emptyList()
                    },
                    modifier = Modifier.fillMaxWidth()
                )

                // Mnemonic input field with chips
                MnemonicInputField(
                    words = mnemonicWords,
                    currentWord = currentWord,
                    expectedWordCount = expectedWordCount,
                    onWordRemoved = { index ->
                        mnemonicWords = mnemonicWords.toMutableList().apply { removeAt(index) }
                        possibleWords = emptyList()
                    },
                    onClearAll = {
                        mnemonicWords = emptyList()
                        currentWord = ""
                        possibleWords = emptyList()
                        errorMessage = ""
                    }
                )

                if (possibleWords.isNotEmpty()) {
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
                            Text(
                                text = "Possible Last Words:",
                                style = MaterialTheme.typography.titleMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                            Text(
                                text = possibleWords.joinToString(", "),
                                style = MaterialTheme.typography.headlineSmall,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                if (errorMessage.isNotEmpty()) {
                    Card(
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
                        if (mnemonicWords.size != expectedWordCount) {
                            errorMessage = "Please enter exactly $expectedWordCount words"
                            possibleWords = emptyList()
                        } else {
                            errorMessage = ""
                            isCalculating = true

                            scope.launch {
                                val validWords = withContext(Dispatchers.Default) {
                                    calculatePossibleLastWords(mnemonicWords)
                                }

                                isCalculating = false
                                if (validWords.isEmpty()) {
                                    errorMessage = "No valid last words found. Please check your input."
                                    possibleWords = emptyList()
                                } else {
                                    possibleWords = validWords
                                    errorMessage = ""
                                }
                            }
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !isCalculating && mnemonicWords.size == expectedWordCount
                ) {
                    if (isCalculating) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(20.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary
                        )
                    } else {
                        Text("Calculate Last Word")
                    }
                }

                if (possibleWords.isNotEmpty()) {
                    OutlinedButton(
                        onClick = {
                            possibleWords = emptyList()
                            mnemonicWords = emptyList()
                            currentWord = ""
                            errorMessage = ""
                        },
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Clear")
                    }
                }
                
                Spacer(modifier = Modifier.height(8.dp))
            }

            // Secure keyboard at the bottom (always visible when not complete)
            if (mnemonicWords.size < expectedWordCount) {
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
 * Calculates all possible valid last words for an incomplete mnemonic.
 * Uses the BIP39 English wordlist (2048 words, indices 0-2047) and validates
 *
 * BIP39 Checksum Details:
 * - 12-word mnemonic: 128 bits entropy + 4 bits checksum = 132 bits total
 *   With 11 words given (121 bits), last word has 11 bits (7 entropy + 4 checksum)
 *   Result: ~8-16 valid last words
 *
 * - 24-word mnemonic: 256 bits entropy + 8 bits checksum = 264 bits total
 *   With 23 words given (253 bits), last word has 11 bits (3 entropy + 8 checksum)
 *   Result: ~8-256 valid last words
 *
 * @param incompleteWords List of 11 or 23 words
 * @return List of valid last words that complete the mnemonic with a valid checksum
 */
private fun calculatePossibleLastWords(incompleteWords: List<String>): List<String> {
    val validLastWords = mutableListOf<String>()

    try {
        // Load the BIP39 English wordlist (2048 words, indices 0-2047)
        val bip39Words = BIP39Wordlist.getEnglishWordlist()

        if (bip39Words.isEmpty()) {
            AppLog.e("CompleteMnemonic") { "Failed to load BIP39 wordlist" }
            return emptyList()
        }

        AppLog.d("CompleteMnemonic") { "Loaded ${bip39Words.size} BIP39 words" }

        // Try each word from the wordlist as the last word
        for (candidateWord in bip39Words) {
            try {
                val completeMnemonic = incompleteWords + candidateWord
                // MnemonicCode.validate will check the checksum
                MnemonicCode.validate(completeMnemonic)
                // If we get here without exception, the mnemonic is valid
                validLastWords.add(candidateWord)
            } catch (_: Exception) {
                // Invalid checksum, skip this word
            }
        }

        AppLog.d("CompleteMnemonic") { "Found ${validLastWords.size} valid last words" }

    } catch (e: Exception) {
        AppLog.e("CompleteMnemonic", e) { "Error calculating last words: ${e.message}" }
    }

    return validLastWords.sorted()
}
