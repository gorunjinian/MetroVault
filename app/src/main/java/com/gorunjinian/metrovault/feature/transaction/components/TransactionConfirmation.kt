package com.gorunjinian.metrovault.feature.transaction.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.domain.service.PsbtDetails

/**
 * Transaction confirmation component for reviewing PSBT details before signing.
 * 
 * Displays:
 * - Inputs with addresses/UTXOs
 * - Outputs with change detection
 * - Network fee
 * - Total amount
 * - Sign and Cancel buttons
 */
@Composable
fun TransactionConfirmation(
    psbtDetails: PsbtDetails,
    outputsWithType: List<OutputWithType>,
    isProcessing: Boolean,
    errorMessage: String,
    onSign: () -> Unit,
    onCancel: () -> Unit
) {
    // Unit toggle state: true = sats, false = BTC
    var showInSats by remember { mutableStateOf(true) }
    
    // Input format toggle: true = addresses, false = UTXO (tx hash:index)
    var showAddresses by remember { mutableStateOf(true) }
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Header with title and unit toggle
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Transaction Details",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            
            // Settings toggles column
            Column(
                horizontalAlignment = Alignment.End,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                // Address/UTXO toggle
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Address option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (showAddresses) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showAddresses = true }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "Addr",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (showAddresses) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // UTXO option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (!showAddresses) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showAddresses = false }
                            .padding(horizontal = 10.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "UTXO",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!showAddresses) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
                
                // Sats/BTC toggle
                Row(
                    modifier = Modifier
                        .background(
                            MaterialTheme.colorScheme.surfaceVariant,
                            RoundedCornerShape(8.dp)
                        )
                        .padding(4.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    // Sats option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (showInSats) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showInSats = true }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "sats",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (showInSats) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                    
                    // BTC option
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(6.dp))
                            .background(
                                if (!showInSats) MaterialTheme.colorScheme.primary
                                else Color.Transparent
                            )
                            .clickable { showInSats = false }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = "BTC",
                            style = MaterialTheme.typography.labelMedium,
                            color = if (!showInSats) MaterialTheme.colorScheme.onPrimary
                                   else MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))

        // ==================== INPUTS SECTION ====================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Inputs (${psbtDetails.inputs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                psbtDetails.inputs.forEachIndexed { index, input ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            // Show input address or UTXO based on toggle - horizontally scrollable for consistency with outputs
                            Row(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .horizontalScroll(rememberScrollState()),
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Text(
                                    text = if (showAddresses) input.address 
                                           else "${input.prevTxHash.take(8)}...${input.prevTxHash.takeLast(8)}:${input.prevTxIndex}",
                                    style = MaterialTheme.typography.bodySmall,
                                    maxLines = 1,
                                    softWrap = false,
                                    overflow = TextOverflow.Ellipsis
                                )
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatAmount(input.value, showInSats),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold
                        )
                    }
                    if (index < psbtDetails.inputs.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ==================== OUTPUTS SECTION ====================
        Card(
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(
                modifier = Modifier.padding(16.dp)
            ) {
                Text(
                    text = "Outputs (${psbtDetails.outputs.size})",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )

                Spacer(modifier = Modifier.height(12.dp))

                outputsWithType.forEachIndexed { index, outputWithType ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                // Horizontally scrollable address container
                                Row(
                                    modifier = Modifier
                                        .weight(1f, fill = false)
                                        .horizontalScroll(rememberScrollState()),
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    Text(
                                        text = outputWithType.output.address,
                                        style = MaterialTheme.typography.bodySmall,
                                        color = if (outputWithType.isChangeAddress == true) 
                                            MaterialTheme.colorScheme.onSurfaceVariant 
                                            else MaterialTheme.colorScheme.onSurface,
                                        maxLines = 1,
                                        softWrap = false
                                    )
                                }
                                // Change badge
                                if (outputWithType.isChangeAddress == true) {
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "CHANGE",
                                        style = MaterialTheme.typography.labelSmall,
                                        color = MaterialTheme.colorScheme.onSecondaryContainer,
                                        modifier = Modifier
                                            .background(
                                                MaterialTheme.colorScheme.secondaryContainer,
                                                RoundedCornerShape(4.dp)
                                            )
                                            .padding(horizontal = 6.dp, vertical = 2.dp)
                                    )
                                }
                            }
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Text(
                            text = formatAmount(outputWithType.output.value, showInSats),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.Bold,
                            color = if (outputWithType.isChangeAddress == true) 
                                MaterialTheme.colorScheme.onSurfaceVariant 
                                else MaterialTheme.colorScheme.onSurface
                        )
                    }
                    if (index < outputsWithType.size - 1) {
                        HorizontalDivider()
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Fee section
        psbtDetails.fee?.let { fee ->
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Network Fee:",
                        style = MaterialTheme.typography.titleMedium
                    )
                    Text(
                        text = formatAmount(fee, showInSats),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            Spacer(modifier = Modifier.height(16.dp))
        }

        // Total amount (only count payments going out, not change returning to us)
        val totalSent = outputsWithType.filter { it.isChangeAddress != true }.sumOf { it.output.value }
        val totalAmount = totalSent + (psbtDetails.fee ?: 0)

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = MaterialTheme.colorScheme.primaryContainer
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Text(
                    text = "Total Amount:",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = formatAmount(totalAmount, showInSats),
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        Spacer(modifier = Modifier.height(32.dp))

        // Sign button
        Button(
            onClick = onSign,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp),
            enabled = !isProcessing
        ) {
            if (isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = MaterialTheme.colorScheme.onPrimary
                )
            } else {
                Text("Sign Transaction", style = MaterialTheme.typography.titleMedium)
            }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Cancel button
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Cancel")
        }

        if (errorMessage.isNotEmpty()) {
            Spacer(modifier = Modifier.height(16.dp))
            Card(
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.errorContainer
                ),
                modifier = Modifier.fillMaxWidth()
            ) {
                Text(
                    text = errorMessage,
                    modifier = Modifier.padding(16.dp),
                    color = MaterialTheme.colorScheme.onErrorContainer
                )
            }
        }
    }
}
