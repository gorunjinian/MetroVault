package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
import com.gorunjinian.metrovault.core.ui.components.SecureOutlinedTextField
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * BIP-85 Derivation Screen
 * 
 * Allows deriving child seed phrases from the master seed.
 * Uses column-based layout for seed display (matching ExportOptionsScreen).
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
    // Track the current index for quick navigation
    var currentIndex by remember { mutableIntStateOf(0) }

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
                    onValueChange = { 
                        indexInput = it.filter { char -> char.isDigit() }
                        // Update currentIndex when user types
                        indexInput.toIntOrNull()?.let { idx -> currentIndex = idx }
                    },
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
                        currentIndex = index

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

                // Card with index info and quick navigation arrows
                Card(
                    colors = CardDefaults.cardColors(
                        containerColor = MaterialTheme.colorScheme.primaryContainer
                    )
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 16.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = "Index: $currentIndex | $wordCount words",
                            style = MaterialTheme.typography.titleMedium
                        )
                        
                        // Quick navigation arrows
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            // Decrement button
                            IconButton(
                                onClick = {
                                    if (currentIndex > 0) {
                                        currentIndex--
                                        indexInput = currentIndex.toString()
                                        // Re-derive with new index
                                        deriveSeed(wallet, currentIndex, wordCount)?.let { 
                                            derivedSeed = it 
                                        }
                                    }
                                },
                                enabled = currentIndex > 0
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_circle_left),
                                    contentDescription = "Previous index",
                                    tint = if (currentIndex > 0) 
                                        MaterialTheme.colorScheme.onPrimaryContainer 
                                    else 
                                        MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.38f)
                                )
                            }
                            
                            // Increment button
                            IconButton(
                                onClick = {
                                    currentIndex++
                                    indexInput = currentIndex.toString()
                                    // Re-derive with new index
                                    deriveSeed(wallet, currentIndex, wordCount)?.let { 
                                        derivedSeed = it 
                                    }
                                }
                            ) {
                                Icon(
                                    painter = painterResource(R.drawable.ic_arrow_circle_right),
                                    contentDescription = "Next index",
                                    tint = MaterialTheme.colorScheme.onPrimaryContainer
                                )
                            }
                        }
                    }
                }

                // Column-based seed display
                // For 12 words: Column 1 = 1-6, Column 2 = 7-12
                // For 24 words: Column 1 = 1-12, Column 2 = 13-24
                val is24Words = derivedSeed!!.size == 24
                val wordsPerColumn = derivedSeed!!.size / 2
                val column1 = derivedSeed!!.take(wordsPerColumn)
                val column2 = derivedSeed!!.drop(wordsPerColumn)

                Card(
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(if (is24Words) 8.dp else 16.dp),
                        horizontalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
                    ) {
                        // First column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
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
                                        modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
                                }
                            }
                        }
                        // Second column
                        Column(
                            modifier = Modifier.weight(1f),
                            verticalArrangement = Arrangement.spacedBy(if (is24Words) 4.dp else 8.dp)
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
                                        modifier = Modifier.padding(if (is24Words) 8.dp else 12.dp),
                                        style = MaterialTheme.typography.bodyMedium
                                    )
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

/**
 * Helper function to derive a seed at a specific index.
 * Returns null if derivation fails.
 */
private fun deriveSeed(wallet: Wallet, index: Int, wordCount: Int): List<String>? {
    return try {
        val walletState = wallet.getActiveWalletState() ?: return null
        val masterPrivateKey = walletState.getMasterPrivateKey() ?: return null

        val path = listOf(
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(83696968),
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(39),
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(0),
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(wordCount.toLong()),
            com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet.hardened(index.toLong())
        )

        val derivedKey = masterPrivateKey.derivePrivateKey(path)
        val entropy = derivedKey.secretkeybytes.toByteArray()

        val hmacKey = "bip-entropy-from-k".toByteArray()
        val hmac = hmacSha512(hmacKey, entropy)

        val finalEntropy = if (wordCount == 12) {
            hmac.sliceArray(0..15)
        } else {
            hmac.sliceArray(0..31)
        }

        MnemonicCode.toMnemonics(finalEntropy)
    } catch (e: Exception) {
        null
    }
}