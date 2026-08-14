package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
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
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.ui.components.CopyableValueCard
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.data.model.CoordinatorExportData
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorExportScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onShowNunchukQr: () -> Unit,
    onShowSparrowQr: () -> Unit,
    onBack: () -> Unit
) {
    val result by produceState<CoordinatorExportResult?>(initialValue = null, key1 = wallet) {
        value = withContext(Dispatchers.Default) { wallet.getActiveCoordinatorExport() }
    }
    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()
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
                tapToCopyEnabled = tapToCopyEnabled,
                onShowNunchukQr = onShowNunchukQr,
                onShowSparrowQr = onShowSparrowQr,
                modifier = Modifier.padding(padding)
            )

            is CoordinatorExportResult.Unsupported -> CoordinatorExportUnavailable(
                title = "Unsupported wallet",
                message = current.reason.message,
                modifier = Modifier.padding(padding)
            )

            is CoordinatorExportResult.Error -> CoordinatorExportUnavailable(
                title = "Export unavailable",
                message = current.reason.message,
                modifier = Modifier.padding(padding)
            )
        }
    }
}

@Composable
private fun CoordinatorExportContent(
    data: CoordinatorExportData,
    tapToCopyEnabled: Boolean,
    onShowNunchukQr: () -> Unit,
    onShowSparrowQr: () -> Unit,
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Spacer(Modifier.height(0.dp))

        InfoCard(
            text = "Public wallet information only. A coordinator can watch addresses and build " +
                "transactions with this, but it cannot sign.",
            tone = InfoTone.Info,
            textStyle = MaterialTheme.typography.bodyMedium
        )

        ElevatedCard(modifier = Modifier.fillMaxWidth()) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                ExportDetail("Wallet", data.walletName)
                ExportDetail("Network", data.networkName)
                ExportDetail("Address type", data.addressType)
                ExportDetail("Account", data.accountNumber.toString(), mono = true)
                ExportDetail("Derivation path", data.derivationPath, mono = true)
                ExportDetail("Master fingerprint", data.masterFingerprint, mono = true)
            }
        }

        CopyableValueCard(
            value = data.standardAccountXpub,
            clipboardLabel = "Account xpub",
            label = "Account xpub",
            sensitive = false,
            enabled = tapToCopyEnabled
        )

        CopyableValueCard(
            value = data.descriptor,
            clipboardLabel = "Output descriptor",
            label = "Output descriptor",
            sensitive = false,
            enabled = tapToCopyEnabled
        )

        CopyableValueCard(
            value = data.firstReceiveAddress,
            clipboardLabel = "First receive address",
            label = "First receive address",
            sensitive = false,
            enabled = tapToCopyEnabled
        )

        InfoCard(
            text = "After importing, confirm the coordinator shows this same master fingerprint, " +
                "derivation path, and first receive address.",
            tone = InfoTone.Neutral,
            textStyle = MaterialTheme.typography.bodySmall
        )

        Button(
            onClick = onShowNunchukQr,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Nunchuk QR")
        }

        Button(
            onClick = onShowSparrowQr,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Show Sparrow / Coldcard QR")
        }

        Spacer(Modifier.height(24.dp))
    }
}

/** Two-column metadata row: muted label on the left, value right-aligned. */
@Composable
private fun ExportDetail(label: String, value: String, mono: Boolean = false) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(16.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            modifier = Modifier.weight(1f),
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = if (mono) FontFamily.Monospace else null,
            textAlign = TextAlign.End
        )
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
