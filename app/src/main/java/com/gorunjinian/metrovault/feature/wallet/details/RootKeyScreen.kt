package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.TapToCopyQRCard
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

/**
 * RootKeyScreen - Displays the wallet's BIP32 root private key (xprv/tprv).
 * This screen should only be navigated to after password confirmation.
 */

@Composable
fun RootKeyScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit,
    onBackToExportOptions: () -> Unit
) {
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    // Get the BIP32 root key
    val rootKey = remember { wallet.getBIP32RootKey() }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "BIP32 Root Key",
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
                Column(
                    modifier = Modifier.padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    Text(
                        text = "This is your BIP32 master private key",
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                    Text(
                        text = "This key can derive all private keys for your wallet. Anyone with this key has complete control over your funds.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onErrorContainer
                    )
                }
            }

            // QR Code
            TapToCopyQRCard(
                data = rootKey,
                clipboardLabel = "BIP32 Root Key",
                tapToCopyEnabled = tapToCopyEnabled,
                contentDescription = "BIP32 Root Key QR Code - Tap to copy"
            )

            // Key text display
            InfoCard(
                text = rootKey,
                tone = InfoTone.Danger,
                textStyle = MaterialTheme.typography.bodySmall,
                containerAlpha = 0.3f
            )
            
            // Hide button
            Button(
                onClick = onBackToExportOptions,
                modifier = Modifier.fillMaxWidth()
            ) {
                Text("Hide Root Key")
            }
            
            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}
