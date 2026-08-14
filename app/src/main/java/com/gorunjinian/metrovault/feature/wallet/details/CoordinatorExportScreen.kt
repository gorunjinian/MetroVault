package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.core.qr.AnimatedQRResult
import com.gorunjinian.metrovault.core.qr.CoordinatorQREncoder
import com.gorunjinian.metrovault.core.qr.CoordinatorQrEncoding
import com.gorunjinian.metrovault.core.ui.components.AnimatedQrDisplay
import com.gorunjinian.metrovault.core.ui.components.InfoCard
import com.gorunjinian.metrovault.core.ui.components.InfoTone
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.core.ui.components.SegmentedToggle
import com.gorunjinian.metrovault.core.ui.components.TapToCopyQRCard
import com.gorunjinian.metrovault.data.model.CoordinatorExportData
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.feature.wallet.details.components.AccountSelectorDropdown
import com.gorunjinian.metrovault.feature.wallet.details.components.Bip48ScriptTypeToggle
import com.gorunjinian.metrovault.feature.wallet.details.components.rememberAccountExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Which coordinator format is on screen, selected by the app-bar toggle. */
private enum class CoordinatorFormat {
    NUNCHUK,
    SPARROW
}

/**
 * CoordinatorExportScreen - Exports the wallet to a watch-only coordinator as a QR code.
 *
 * The app-bar toggle picks the coordinator format:
 * - Nunchuk: the one-line public signer record, single-sig or BIP48 multisig, always one static QR.
 * - Sparrow / Coldcard: the Coldcard Generic JSON. Single-sig mode is one static QR of the
 *   wallet's own address type; Combined mode bundles all address types plus both BIP48 multisig
 *   keys (GitHub issue #2) and animates as BBQr or BC-UR frames.
 *
 * The verification details (fingerprint, path, first address) are computed from the same account
 * selection as the QR, so what the user cross-checks always matches what they scanned.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun CoordinatorExportScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    var format by remember { mutableStateOf(CoordinatorFormat.NUNCHUK) }

    // Nunchuk mode: false = single-sig signer record, true = BIP48 multisig key
    var nunchukMultisig by remember { mutableStateOf(false) }
    var bip48ScriptType by remember { mutableStateOf(DerivationPaths.Bip48ScriptType.P2WSH) }

    // Sparrow mode: false = single-section JSON (active address type), true = combined JSON
    var sparrowCombined by remember { mutableStateOf(false) }
    var sparrowEncoding by remember { mutableStateOf(CoordinatorQrEncoding.BBQR) }

    val accountState = rememberAccountExportState(wallet)
    val baseDerivationPath = accountState.baseDerivationPath
    val selectedAccountNumber = accountState.selectedAccountNumber

    // Single-sig export for the selected account: powers both formats' single-sig modes and the
    // verification details card. Null while computing.
    var singleResult by remember { mutableStateOf<CoordinatorExportResult?>(null) }
    LaunchedEffect(selectedAccountNumber) {
        singleResult = null
        singleResult = withContext(Dispatchers.Default) {
            wallet.getCoordinatorExportForAccount(selectedAccountNumber)
        }
    }

    // Nunchuk BIP48 record: a single cheap derivation, computed synchronously like the
    // descriptor and account-key screens do.
    val nunchukBip48Record = remember(selectedAccountNumber, bip48ScriptType) {
        wallet.getNunchukBip48SignerRecordForAccount(selectedAccountNumber, bip48ScriptType)
    }

    // Combined export, fetched only while the Sparrow Combined mode is active. Loading starts
    // true so the first frame after entering the mode shows the spinner, not the failure card.
    val combinedActive = format == CoordinatorFormat.SPARROW && sparrowCombined
    var combinedJson by remember { mutableStateOf<String?>(null) }
    var combinedLoading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedAccountNumber, combinedActive) {
        combinedLoading = true
        if (!combinedActive) return@LaunchedEffect
        combinedJson = withContext(Dispatchers.Default) {
            wallet.getCombinedCoordinatorExportForAccount(selectedAccountNumber)
        }
        combinedLoading = false
    }

    // Animated QR state for the combined payload
    var qrResult by remember { mutableStateOf<AnimatedQRResult?>(null) }
    var qrLoading by remember { mutableStateOf(false) }

    // Security: Clear QR data when leaving the screen
    DisposableEffect(Unit) {
        onDispose {
            qrResult?.frames?.forEach { it.recycle() }
            qrResult = null
            System.gc()
        }
    }

    LaunchedEffect(combinedJson, sparrowEncoding) {
        val json = combinedJson
        if (json == null) {
            qrResult = null
            return@LaunchedEffect
        }
        qrLoading = true
        qrResult = withContext(Dispatchers.IO) {
            CoordinatorQREncoder.encode(json, sparrowEncoding)
        }
        qrLoading = false
    }

    val exportLabel = when {
        format == CoordinatorFormat.NUNCHUK && nunchukMultisig -> "Nunchuk Multisig Key"
        format == CoordinatorFormat.NUNCHUK -> "Nunchuk Signer Record"
        sparrowCombined -> "Combined Wallet Export"
        else -> "Sparrow / Coldcard Export"
    }

    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Coordinator Export",
                onBack = onBack,
                actions = {
                    // Coordinator format toggle in App Bar
                    SegmentedToggle(
                        firstOption = "Nunchuk",
                        secondOption = "Sparrow",
                        isSecondSelected = format == CoordinatorFormat.SPARROW,
                        onSelectFirst = { format = CoordinatorFormat.NUNCHUK },
                        onSelectSecond = { format = CoordinatorFormat.SPARROW },
                        modifier = Modifier.padding(end = 8.dp),
                        compact = true
                    )
                }
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

            AccountSelectorDropdown(
                label = exportLabel,
                state = accountState,
                accountDetail = { accountNum ->
                    when {
                        format == CoordinatorFormat.NUNCHUK && nunchukMultisig ->
                            DerivationPaths.bip48(accountNum, bip48ScriptType, wallet.isActiveWalletTestnet())
                        combinedActive -> "All address types + multisig keys"
                        else -> DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                    }
                }
            )

            if (format == CoordinatorFormat.NUNCHUK) {
                SegmentedToggle(
                    firstOption = "Single-sig",
                    secondOption = "Multisig",
                    isSecondSelected = nunchukMultisig,
                    onSelectFirst = { nunchukMultisig = false },
                    onSelectSecond = { nunchukMultisig = true },
                    modifier = Modifier.fillMaxWidth()
                )
                if (nunchukMultisig) {
                    Bip48ScriptTypeToggle(
                        scriptType = bip48ScriptType,
                        onScriptTypeChange = { bip48ScriptType = it },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            } else {
                SegmentedToggle(
                    firstOption = "Single-sig",
                    secondOption = "Combined",
                    isSecondSelected = sparrowCombined,
                    onSelectFirst = { sparrowCombined = false },
                    onSelectSecond = { sparrowCombined = true },
                    modifier = Modifier.fillMaxWidth()
                )
                if (sparrowCombined) {
                    // QR encoding toggle: BBQr / BC-UR
                    SegmentedToggle(
                        options = CoordinatorQrEncoding.entries.map { it.displayName },
                        selectedIndex = CoordinatorQrEncoding.entries.indexOf(sparrowEncoding),
                        onSelect = { index -> sparrowEncoding = CoordinatorQrEncoding.entries[index] },
                        modifier = Modifier.fillMaxWidth()
                    )
                }
            }

            InfoCard(
                text = when {
                    format == CoordinatorFormat.NUNCHUK && nunchukMultisig ->
                        "In Nunchuk, add this device as a key for a multisig wallet by scanning this QR."
                    format == CoordinatorFormat.NUNCHUK ->
                        "In Nunchuk, choose Add Signer via QR and scan this code. It contains only " +
                            "public information and cannot sign."
                    combinedActive ->
                        "One JSON with every address type (BIP44/49/84/86) plus both BIP48 " +
                            "multisig keys. The importing software picks what it needs."
                    else ->
                        "In Sparrow, choose Airgapped Hardware Wallet, then scan this QR using " +
                            "the Coldcard option."
                },
                tone = InfoTone.Neutral,
                textStyle = MaterialTheme.typography.bodyMedium
            )

            when {
                format == CoordinatorFormat.NUNCHUK && nunchukMultisig -> {
                    // The record itself carries the fingerprint, path, and key, so it doubles as
                    // the verification data for this mode.
                    if (nunchukBip48Record.isEmpty()) {
                        InfoCard(
                            text = "This export is unavailable for the active wallet.",
                            tone = InfoTone.Danger,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                    } else {
                        TapToCopyQRCard(
                            data = nunchukBip48Record,
                            clipboardLabel = exportLabel,
                            tapToCopyEnabled = tapToCopyEnabled,
                            contentDescription = "$exportLabel QR Code - Tap to copy"
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = nunchukBip48Record,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall
                            )
                        }
                    }
                }

                combinedActive -> when {
                    combinedLoading || qrLoading -> LoadingBox()

                    combinedJson == null -> InfoCard(
                        text = "The combined export needs the wallet's seed and is unavailable " +
                            "for this wallet.",
                        tone = InfoTone.Danger,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    else -> AnimatedQrDisplay(
                        qrResult = qrResult,
                        contentDescription = "Combined wallet export QR Code"
                    )
                }

                else -> when (val current = singleResult) {
                    null -> LoadingBox()

                    is CoordinatorExportResult.Available -> SingleSigExportContent(
                        data = current.data,
                        isNunchuk = format == CoordinatorFormat.NUNCHUK,
                        exportLabel = exportLabel,
                        tapToCopyEnabled = tapToCopyEnabled
                    )

                    is CoordinatorExportResult.Unsupported -> InfoCard(
                        text = current.reason.message,
                        tone = InfoTone.Danger,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )

                    is CoordinatorExportResult.Error -> InfoCard(
                        text = current.reason.message,
                        tone = InfoTone.Danger,
                        textStyle = MaterialTheme.typography.bodyMedium
                    )
                }
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }
}

@Composable
private fun LoadingBox() {
    Box(
        contentAlignment = Alignment.Center,
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f)
    ) { CircularProgressIndicator() }
}

/**
 * Single-sig export body shared by both formats: the verification details for the selected
 * account, the cross-check advice, then the format's QR and payload text.
 */
@Composable
private fun SingleSigExportContent(
    data: CoordinatorExportData,
    isNunchuk: Boolean,
    exportLabel: String,
    tapToCopyEnabled: Boolean
) {
    ElevatedCard(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            ExportDetail("Wallet Name", data.walletName)
            ExportDetail("Network", data.networkName)
            ExportDetail("Address type", data.addressType)
            ExportDetail("Account", data.accountNumber.toString(), mono = true)
            ExportDetail("Derivation path", data.derivationPath, mono = true)
            ExportDetail("Master fingerprint", data.masterFingerprint, mono = true)
            ExportDetail("First address", data.firstReceiveAddress, mono = true)
        }
    }

    val payload = if (isNunchuk) data.nunchukSignerRecord else data.coldcardJson
    TapToCopyQRCard(
        data = payload,
        clipboardLabel = exportLabel,
        tapToCopyEnabled = tapToCopyEnabled,
        contentDescription = "$exportLabel QR Code - Tap to copy"
    )
    Card(modifier = Modifier.fillMaxWidth()) {
        Text(
            text = payload,
            modifier = Modifier.padding(16.dp),
            style = MaterialTheme.typography.bodySmall,
            fontFamily = if (isNunchuk) null else FontFamily.Monospace
        )
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
