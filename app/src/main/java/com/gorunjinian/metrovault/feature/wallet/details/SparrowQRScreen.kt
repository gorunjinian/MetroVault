package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontFamily
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
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.feature.wallet.details.components.AccountSelectorDropdown
import com.gorunjinian.metrovault.feature.wallet.details.components.rememberAccountExportState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * SparrowQRScreen - Displays the Coldcard Generic JSON export Sparrow reads under
 * Airgapped Hardware Wallet.
 *
 * Two payloads, chosen by the app-bar toggle:
 * - Single-sig: today's single-section JSON for the wallet's own address type, small enough
 *   for one static QR.
 * - Combined: all four single-sig sections plus both BIP48 multisig keys in one JSON
 *   (GitHub issue #2), carried as animated BBQr or BC-UR frames.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SparrowQRScreen(
    wallet: Wallet,
    userPreferencesRepository: UserPreferencesRepository,
    onBack: () -> Unit
) {
    // Export mode: false = single-section JSON (active address type), true = combined JSON
    var combinedMode by remember { mutableStateOf(false) }

    // QR encoding for the combined payload
    var selectedEncoding by remember { mutableStateOf(CoordinatorQrEncoding.BBQR) }

    val accountState = rememberAccountExportState(wallet)
    val baseDerivationPath = accountState.baseDerivationPath
    val selectedAccountNumber = accountState.selectedAccountNumber

    // Single-section export for the selected account. Null while computing.
    var singleResult by remember { mutableStateOf<CoordinatorExportResult?>(null) }
    LaunchedEffect(selectedAccountNumber, combinedMode) {
        // Reset when leaving single mode so re-entry starts on the spinner, not stale data.
        singleResult = null
        if (combinedMode) return@LaunchedEffect
        singleResult = withContext(Dispatchers.Default) {
            wallet.getCoordinatorExportForAccount(selectedAccountNumber)
        }
    }

    // Combined export for the selected account. Loading starts true so the first frame after
    // entering combined mode shows the spinner rather than the "unavailable" card.
    var combinedJson by remember { mutableStateOf<String?>(null) }
    var combinedLoading by remember { mutableStateOf(true) }
    LaunchedEffect(selectedAccountNumber, combinedMode) {
        combinedLoading = true
        if (!combinedMode) return@LaunchedEffect
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

    LaunchedEffect(combinedJson, selectedEncoding) {
        val json = combinedJson
        if (json == null) {
            qrResult = null
            return@LaunchedEffect
        }
        qrLoading = true
        qrResult = withContext(Dispatchers.IO) {
            CoordinatorQREncoder.encode(json, selectedEncoding)
        }
        qrLoading = false
    }

    val exportLabel = if (combinedMode) "Combined Wallet Export" else "Sparrow / Coldcard Export"

    val tapToCopyEnabled by userPreferencesRepository.tapToCopyEnabled.collectAsState()

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Sparrow / Coldcard",
                onBack = onBack,
                actions = {
                    // Single-sig / Combined Toggle in App Bar
                    SegmentedToggle(
                        firstOption = "Single-sig",
                        secondOption = "Combined",
                        isSecondSelected = combinedMode,
                        onSelectFirst = { combinedMode = false },
                        onSelectSecond = { combinedMode = true },
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
                    if (combinedMode) {
                        "All address types + multisig keys"
                    } else {
                        DerivationPaths.withAccountNumber(baseDerivationPath, accountNum)
                    }
                }
            )

            if (combinedMode) {
                // QR encoding toggle: BBQr / BC-UR
                SegmentedToggle(
                    options = CoordinatorQrEncoding.entries.map { it.displayName },
                    selectedIndex = CoordinatorQrEncoding.entries.indexOf(selectedEncoding),
                    onSelect = { index -> selectedEncoding = CoordinatorQrEncoding.entries[index] },
                    modifier = Modifier.fillMaxWidth()
                )

                InfoCard(
                    text = "One JSON with every address type (BIP44/49/84/86) plus both BIP48 " +
                        "multisig keys. The importing software picks what it needs — no need to " +
                        "choose single-sig or multisig up front.",
                    tone = InfoTone.Neutral,
                    textStyle = MaterialTheme.typography.bodyMedium
                )

                when {
                    combinedLoading || qrLoading -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) { CircularProgressIndicator() }

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
            } else {
                when (val current = singleResult) {
                    null -> Box(
                        contentAlignment = Alignment.Center,
                        modifier = Modifier
                            .fillMaxWidth()
                            .aspectRatio(1f)
                    ) { CircularProgressIndicator() }

                    is CoordinatorExportResult.Available -> {
                        InfoCard(
                            text = "In Sparrow, choose Airgapped Hardware Wallet, then scan this " +
                                "QR using the Coldcard option.",
                            tone = InfoTone.Neutral,
                            textStyle = MaterialTheme.typography.bodyMedium
                        )
                        TapToCopyQRCard(
                            data = current.data.coldcardJson,
                            clipboardLabel = "Sparrow Coldcard public wallet JSON",
                            tapToCopyEnabled = tapToCopyEnabled,
                            contentDescription = "Sparrow Coldcard public wallet QR",
                            modifier = Modifier.fillMaxWidth()
                        )
                        Card(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = current.data.coldcardJson,
                                modifier = Modifier.padding(16.dp),
                                style = MaterialTheme.typography.bodySmall,
                                fontFamily = FontFamily.Monospace
                            )
                        }
                    }

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
