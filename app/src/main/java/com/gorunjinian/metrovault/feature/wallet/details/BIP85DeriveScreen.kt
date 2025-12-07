package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * FIX: Critical Bug #2 - Duplicate word index bug (same as ExportOptionsScreen)
 *
 * Problem: Used derivedSeed!!.indexOf(word) which returns the FIRST occurrence.
 * If a mnemonic has duplicate words (valid in BIP39), both would show the same index.
 *
 * Solution: Track the actual index using withIndex(), not indexOf().
 */

private fun hmacSha512(key: ByteArray, data: ByteArray): ByteArray {
    val mac = Mac.getInstance("HmacSHA512")
    val secretKey = SecretKeySpec(key, "HmacSHA512")
    mac.init(secretKey)
    return mac.doFinal(data)
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BIP85DeriveScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    var indexInput by remember { mutableStateOf("") }
    var wordCount by remember { mutableIntStateOf(12) }
    var derivedSeed by remember { mutableStateOf<List<String>?>(null) }
    var errorMessage by remember { mutableStateOf("") }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("BIP-85 Derivation") },
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
                .padding(24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            if (derivedSeed == null) {
                Text(
                    text = "Derive Child Seed",
                    style = MaterialTheme.typography.headlineSmall
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Text(
                        text = "BIP-85 allows you to derive multiple child seed phrases from your master seed. Each child seed is deterministic based on the index number.",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.bodyMedium
                    )
                }

                SecureOutlinedTextField(
                    value = indexInput,
                    onValueChange = { indexInput = it.filter { char -> char.isDigit() } },
                    label = { Text("Index Number") },
                    modifier = Modifier.fillMaxWidth(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )

                Text(
                    text = "Seed Length",
                    style = MaterialTheme.typography.titleMedium
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    FilterChip(
                        selected = wordCount == 12,
                        onClick = { wordCount = 12 },
                        label = { Text("12 Words") },
                        modifier = Modifier.weight(1f)
                    )
                    FilterChip(
                        selected = wordCount == 24,
                        onClick = { wordCount = 24 },
                        label = { Text("24 Words") },
                        modifier = Modifier.weight(1f)
                    )
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

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = {
                        val index = indexInput.toIntOrNull()
                        if (index == null) {
                            errorMessage = "Please enter a valid index"
                            return@Button
                        }

                        val walletState = wallet.getActiveWalletState()
                        if (walletState == null) {
                            errorMessage = "No active wallet loaded"
                            return@Button
                        }

                        try {
                            // BIP-85: Derive child key from master private key
                            // Path: m/83696968'/39'/0'/wordCount'/index'
                            val masterPrivateKey = walletState.getMasterPrivateKey()
                            if (masterPrivateKey == null) {
                                errorMessage = "Master private key not available"
                                return@Button
                            }

                            val path = listOf(
                                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(83696968),
                                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(39),
                                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(0),
                                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(wordCount.toLong()),
                                com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(index.toLong())
                            )

                            val derivedKey = masterPrivateKey.derivePrivateKey(path)
                            val entropy = derivedKey.secretkeybytes.toByteArray()

                            // BIP-85 entropy derivation using HMAC-SHA512
                            val hmacKey = "bip-entropy-from-k".toByteArray()
                            val hmac = hmacSha512(hmacKey, entropy)

                            // Extract the appropriate entropy size
                            val finalEntropy = if (wordCount == 12) {
                                hmac.sliceArray(0..15)  // 16 bytes for 12 words
                            } else {
                                hmac.sliceArray(0..31)  // 32 bytes for 24 words
                            }

                            // Generate mnemonic from entropy
                            derivedSeed = MnemonicCode.toMnemonics(finalEntropy)
                            errorMessage = ""
                        } catch (e: Exception) {
                            errorMessage = "Failed to derive key: ${e.message}"
                        }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Derive Seed")
                }
            } else {
                Text(
                    text = "Derived Child Seed",
                    style = MaterialTheme.typography.headlineSmall
                )

                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Text(
                        text = "Index: $indexInput | $wordCount words",
                        modifier = Modifier.padding(16.dp),
                        style = MaterialTheme.typography.titleMedium
                    )
                }

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(16.dp),
                        verticalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        // FIX: Use withIndex() to get proper indices for each word
                        // This handles duplicate words correctly
                        derivedSeed!!.withIndex().chunked(2).forEach { chunk ->
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(8.dp)
                            ) {
                                chunk.forEach { (index, word) ->
                                    // FIX: Use the actual index from the list, not indexOf()
                                    val wordNumber = index + 1  // 1-based numbering
                                    Card(
                                        modifier = Modifier.weight(1f),
                                        colors = CardDefaults.cardColors(
                                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                                        )
                                    ) {
                                        Text(
                                            text = "$wordNumber. $word",
                                            modifier = Modifier.padding(12.dp)
                                        )
                                    }
                                }
                                // Handle odd number of words in last row
                                if (chunk.size == 1) {
                                    Spacer(modifier = Modifier.weight(1f))
                                }
                            }
                        }
                    }
                }

                Spacer(modifier = Modifier.weight(1f))

                Button(
                    onClick = { derivedSeed = null },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("Derive Another Seed")
                }
            }
        }
    }
}