package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * DerivedSeedQRScreen - Displays a BIP85-derived seed phrase as a SeedQR code.
 *
 * Supports Standard SeedQR, CompactSeedQR, and Generic (plain text) formats.
 * Thin wrapper over [SeedQRContent] (same UI as [SeedQRScreen]) for derived seeds.
 */
@Composable
fun DerivedSeedQRScreen(
    derivedMnemonic: List<String>,
    onBack: () -> Unit
) {
    SeedQRContent(
        mnemonic = derivedMnemonic,
        title = "Derived SeedQR",
        warningText = "This QR contains a derived seed phrase.\nAnyone who scans it can access its funds.",
        qrContentDescription = "Derived SeedQR Code",
        onBack = onBack
    ) {
        // Back button
        Button(
            onClick = onBack,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Back to Derived Seed")
        }
    }
}
