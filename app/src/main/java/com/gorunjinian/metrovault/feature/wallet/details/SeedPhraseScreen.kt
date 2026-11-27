package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.domain.Wallet

/**
 * SeedPhraseScreen - Displays the wallet's seed phrase.
 * This screen should only be navigated to after password confirmation.
 */

@Composable
fun SeedPhraseScreen(
    wallet: Wallet,
    onBack: () -> Unit,
    onShowSeedQR: () -> Unit
) {
    val mnemonic = wallet.getActiveMnemonic() ?: emptyList()
    val is24Words = mnemonic.size == 24

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Seed Phrase",
                onBack = onBack
            )
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 24.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            Spacer(modifier = Modifier.height(0.dp))
            
            // Warning card
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                )
            ) {
                Text(
                    text = "Keep your seed phrase secret and secure!",
                    modifier = Modifier.padding(if (is24Words) 12.dp else 16.dp),
                    style = if (is24Words) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleMedium
                )
            }

            // Seed phrase display card
            Card(
                modifier = Modifier.fillMaxWidth()
            ) {
                // Display words top-to-bottom in two columns
                val wordsPerColumn = mnemonic.size / 2
                val column1 = mnemonic.take(wordsPerColumn)
                val column2 = mnemonic.drop(wordsPerColumn)
                
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

            // Show SeedQR button
            OutlinedButton(
                onClick = onShowSeedQR,
                modifier = Modifier.fillMaxWidth()
            ) {
                Icon(
                    painter = painterResource(R.drawable.ic_qr_code_2),
                    contentDescription = null,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text("Show SeedQR")
            }

            // Hide button
            Button(
                onClick = onBack,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Hide Seed Phrase")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
