package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.TapToCopyQRCard
import com.gorunjinian.metrovault.data.model.CoordinatorExportData
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorExportScreen(
    wallet: Wallet,
    onBack: () -> Unit
) {
    val result by produceState<CoordinatorExportResult?>(initialValue = null, key1 = wallet) {
        value = withContext(Dispatchers.Default) { wallet.getActiveCoordinatorExport() }
    }
    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Coordinator Export",
                onBack = onBack
            )
        }
    ) { padding ->
        when (val current = result) {
            null -> Column(
                modifier = Modifier.fillMaxSize().padding(padding),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) { CircularProgressIndicator() }

            is CoordinatorExportResult.Available -> CoordinatorExportContent(
                data = current.data,
                modifier = Modifier.padding(padding)
            )

            is CoordinatorExportResult.Unsupported -> CoordinatorExportUnavailable(
                title = "Unsupported wallet",
                message = current.message,
                modifier = Modifier.padding(padding)
            )

            is CoordinatorExportResult.Error -> CoordinatorExportUnavailable(
                title = "Export unavailable",
                message = current.message,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun CoordinatorExportContent(
    data: CoordinatorExportData,
    modifier: Modifier = Modifier
) {
    var visibleQr by remember { mutableStateOf<CoordinatorQrFormat?>(null) }
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))
        Text("Export to wallet coordinator", style = MaterialTheme.typography.headlineSmall)
        Surface(
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                "Public data only — cannot spend funds",
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                style = MaterialTheme.typography.labelLarge
            )
        }
        Text(
            "This export contains public wallet information only. It lets Nunchuk monitor addresses and construct transactions, but it cannot sign.",
            style = MaterialTheme.typography.bodyMedium
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ExportDetail("Wallet name", data.walletName)
                ExportDetail("Network", data.networkName)
                ExportDetail("Address type", data.addressType)
                ExportDetail("Account number", data.accountNumber.toString())
                ExportDetail("Derivation path", data.derivationPath)
                ExportDetail("Master fingerprint", data.masterFingerprint)
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
            shape = MaterialTheme.shapes.medium
        ) {
            Text(
                "Privacy warning: anyone who receives this export can discover this account's addresses, balances, and transaction history.",
                modifier = Modifier.padding(16.dp),
                style = MaterialTheme.typography.bodySmall
            )
        }

        Button(
            onClick = {
                visibleQr = if (visibleQr == CoordinatorQrFormat.NUNCHUK) null else CoordinatorQrFormat.NUNCHUK
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (visibleQr == CoordinatorQrFormat.NUNCHUK) "Hide Nunchuk QR" else "Show Nunchuk QR")
        }
        if (visibleQr == CoordinatorQrFormat.NUNCHUK) {
            TapToCopyQRCard(
                data = data.nunchukSignerRecord,
                clipboardLabel = "Nunchuk public signer record",
                tapToCopyEnabled = true,
                contentDescription = "Nunchuk public signer QR",
                modifier = Modifier.fillMaxWidth()
            )
        }

        Button(
            onClick = {
                visibleQr = if (visibleQr == CoordinatorQrFormat.SPARROW_COLDCARD) {
                    null
                } else {
                    CoordinatorQrFormat.SPARROW_COLDCARD
                }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(
                if (visibleQr == CoordinatorQrFormat.SPARROW_COLDCARD) {
                    "Hide Sparrow / Coldcard QR"
                } else {
                    "Show Sparrow / Coldcard QR"
                }
            )
        }
        if (visibleQr == CoordinatorQrFormat.SPARROW_COLDCARD) {
            Text(
                "In Sparrow, choose Airgapped Hardware Wallet, then scan this QR using the Coldcard option.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            TapToCopyQRCard(
                data = data.nunchukJson,
                clipboardLabel = "Sparrow Coldcard public wallet JSON",
                tapToCopyEnabled = true,
                contentDescription = "Sparrow Coldcard public wallet QR",
                modifier = Modifier.fillMaxWidth()
            )
        }
        Spacer(Modifier.height(24.dp))
    }
}

private enum class CoordinatorQrFormat {
    NUNCHUK,
    SPARROW_COLDCARD
}

@Composable
private fun ExportDetail(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.bodyLarge)
        HorizontalDivider(modifier = Modifier.padding(top = 8.dp))
    }
}

@Composable
private fun CoordinatorExportUnavailable(title: String, message: String, modifier: Modifier = Modifier) {
    Column(
        modifier = modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text(title, style = MaterialTheme.typography.headlineSmall)
        Spacer(Modifier.height(12.dp))
        Text(message, style = MaterialTheme.typography.bodyLarge)
    }
}

@Preview(showBackground = true, widthDp = 412, heightDp = 915)
@Composable
private fun CoordinatorExportPreview() {
    MaterialTheme {
        CoordinatorExportContent(
            data = CoordinatorExportData(
                walletName = "Cold savings",
                networkName = "Bitcoin mainnet",
                addressType = "Native SegWit (BIP84)",
                accountNumber = 0,
                derivationPath = "m/84h/0h/0h",
                masterFingerprint = "3CA02B0D",
                standardAccountXpub = "xpub6DBqChRqCJXqVDNaczA3SfbP2WodPn5HQAdH2BCZZMrA1SsTfc7NH5Q7zNAVJqSWj8fTpt2DefyFy9tyFGWuQseDLArFS95k7re8rGrtGeD",
                slip132AccountXpub = "zpub6rrMp2mfVfcoBokpHhjHrqnPNT6XH24HEPfiaxzLKNbv7eVvAvSVXCiQ2n5fJekMYQu5KqDLZzgMjj86gfLw1M1R4rF6bxiifJmRdP2smxV",
                descriptor = "wpkh([3ca02b0d/84h/0h/0h]xpub.../<0;1>/*)#example1",
                firstReceiveAddress = "bc1qexample",
                nunchukSignerRecord = "[3CA02B0D/84h/0h/0h]xpub6DBqChRqCJXqVDNaczA3SfbP2WodPn5HQAdH2BCZZMrA1SsTfc7NH5Q7zNAVJqSWj8fTpt2DefyFy9tyFGWuQseDLArFS95k7re8rGrtGeD/<0;1>/*",
                nunchukJson = "{}"
            )
        )
    }
}
