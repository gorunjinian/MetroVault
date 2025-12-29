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
 * - Inputs with addresses/UTXOs and amounts
 * - Outputs with change detection
 * - Transaction Summary (net amount sent, tx size, fee rate, structure)
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
            .padding(horizontal = 24.dp, vertical = 16.dp)
            .verticalScroll(rememberScrollState()),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Centered title
        Text(
            text = "Transaction Details",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        
        Spacer(modifier = Modifier.height(16.dp))
        
        // ==================== MULTISIG INFO CARD ====================
        if (psbtDetails.isMultisig) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (psbtDetails.isReadyToBroadcast) 
                        MaterialTheme.colorScheme.tertiaryContainer
                    else 
                        MaterialTheme.colorScheme.secondaryContainer
                )
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(16.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text(
                        text = "Multisig Transaction",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                        color = if (psbtDetails.isReadyToBroadcast)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(8.dp))
                    
                    // Signature progress
                    Text(
                        text = "${psbtDetails.currentSignatures} of ${psbtDetails.requiredSignatures} signatures",
                        style = MaterialTheme.typography.bodyLarge,
                        fontWeight = FontWeight.SemiBold,
                        color = if (psbtDetails.isReadyToBroadcast)
                            MaterialTheme.colorScheme.onTertiaryContainer
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    // Progress bar
                    LinearProgressIndicator(
                        progress = { 
                            psbtDetails.currentSignatures.toFloat() / psbtDetails.requiredSignatures.toFloat() 
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(6.dp)
                            .clip(RoundedCornerShape(3.dp)),
                        color = if (psbtDetails.isReadyToBroadcast)
                            MaterialTheme.colorScheme.tertiary
                        else
                            MaterialTheme.colorScheme.secondary,
                        trackColor = if (psbtDetails.isReadyToBroadcast)
                            MaterialTheme.colorScheme.onTertiaryContainer.copy(alpha = 0.2f)
                        else
                            MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.2f)
                    )
                    
                    if (psbtDetails.isReadyToBroadcast) {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "✓ Ready to broadcast",
                            style = MaterialTheme.typography.labelMedium,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onTertiaryContainer
                        )
                    } else {
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = "${psbtDetails.requiredSignatures - psbtDetails.currentSignatures} more signature${if (psbtDetails.requiredSignatures - psbtDetails.currentSignatures != 1) "s" else ""} needed",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSecondaryContainer
                        )
                    }
                }
            }
            
            Spacer(modifier = Modifier.height(16.dp))
        }
        
        // Toggles row - both side by side with equal width
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            // Address/UTXO toggle
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
                // Address option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (showAddresses) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { showAddresses = true }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
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
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (!showAddresses) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { showAddresses = false }
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
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
                    .weight(1f)
                    .background(
                        MaterialTheme.colorScheme.surfaceVariant,
                        RoundedCornerShape(8.dp)
                    )
                    .padding(4.dp),
                horizontalArrangement = Arrangement.Center,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Sats option
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (showInSats) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { showInSats = true }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
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
                        .weight(1f)
                        .clip(RoundedCornerShape(6.dp))
                        .background(
                            if (!showInSats) MaterialTheme.colorScheme.primary
                            else Color.Transparent
                        )
                        .clickable { showInSats = false }
                        .padding(horizontal = 12.dp, vertical = 6.dp),
                    contentAlignment = Alignment.Center
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
        
        // ==================== TRANSACTION SUMMARY ====================
        // Calculate values for the summary
        val netSent = outputsWithType.filter { it.isChangeAddress != true }.sumOf { it.output.value }
        val feeRate = if (psbtDetails.fee != null && psbtDetails.virtualSize > 0) {
            psbtDetails.fee.toDouble() / psbtDetails.virtualSize
        } else null
        
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
                    text = "Transaction Summary",
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold
                )
                
                HorizontalDivider(modifier = Modifier.padding(vertical = 4.dp))
                
                // Net Amount Sent (excluding change)
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Sending:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = formatAmount(netSent, showInSats),
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // Transaction Size
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Transaction Size:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${psbtDetails.virtualSize} vBytes",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
                }
                
                // Fee Rate
                feeRate?.let { rate ->
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween
                    ) {
                        Text(
                            text = "Fee Rate:",
                            style = MaterialTheme.typography.bodyMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Text(
                            text = "%.2f sats/vB".format(rate),
                            style = MaterialTheme.typography.bodyMedium,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                }
                
                // Input/Output count
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text(
                        text = "Structure:",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        text = "${psbtDetails.inputs.size} input${if (psbtDetails.inputs.size != 1) "s" else ""} → ${psbtDetails.outputs.size} output${if (psbtDetails.outputs.size != 1) "s" else ""}",
                        style = MaterialTheme.typography.bodyMedium,
                        fontWeight = FontWeight.SemiBold
                    )
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
