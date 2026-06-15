package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.gorunjinian.metrovault.core.qr.QRCodeGenerator
import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigScriptType

/**
 * "Verify & Register Multisig" ceremony.
 *
 * The user cross-checks the derived first receive address and the descriptor checksum against
 * their coordinator, reviews each cosigner, then registers the wallet — which is what unlocks
 * signing. Reachable both right after import and later from Wallet Details (where it doubles as a
 * "view / re-verify registration" screen).
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VerifyMultisigScreen(
    walletId: String,
    onDone: () -> Unit,
    onCancel: () -> Unit,
    viewModel: VerifyMultisigViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    LaunchedEffect(walletId) { viewModel.load(walletId) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Text(
                        when (uiState.stage) {
                            VerifyMultisigViewModel.Stage.RECEIPT -> "Multisig Registration"
                            else -> "Verify & Register"
                        }
                    )
                },
                navigationIcon = {
                    IconButton(onClick = onCancel) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .padding(horizontal = 20.dp)
        ) {
            when (uiState.stage) {
                VerifyMultisigViewModel.Stage.LOADING -> {
                    CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
                }

                VerifyMultisigViewModel.Stage.REVIEW -> ReviewContent(
                    state = uiState,
                    onConfirm = { viewModel.confirmRegistration() },
                    onCancel = onCancel
                )

                VerifyMultisigViewModel.Stage.RECEIPT -> ReceiptContent(
                    state = uiState,
                    onReVerify = { viewModel.reReview() },
                    onDone = onDone
                )

                VerifyMultisigViewModel.Stage.ERROR -> ErrorContent(
                    message = uiState.errorMessage,
                    onDismiss = onCancel
                )
            }
        }
    }
}

@Composable
private fun ReviewContent(
    state: VerifyMultisigViewModel.UiState,
    onConfirm: () -> Unit,
    onCancel: () -> Unit
) {
    val config = state.config ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Spacer(Modifier.height(4.dp))

        // Quorum header
        Text(
            text = "${config.m}-of-${config.n} multisig",
            style = MaterialTheme.typography.headlineSmall,
            fontWeight = FontWeight.Bold
        )
        Text(
            text = config.scriptType.displayLabel(),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        // Cross-checks — lead with these (strongest tamper checks)
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                Text(
                    text = "Confirm these match your coordinator",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.SemiBold
                )
                Text(
                    text = "Before registering, check that the values below match what your wallet coordinator (e.g. Sparrow) displays. A mismatch means the descriptor was altered in transit.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.9f)
                )
                LabeledMono(label = "First receive address", value = state.firstReceiveAddress.ifEmpty { "—" })
                LabeledMono(label = "Descriptor checksum", value = if (state.descriptorChecksum.isNotEmpty()) "#${state.descriptorChecksum}" else "—")
            }
        }

        // Cosigners
        Text(
            text = "Cosigners (${config.n})",
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Medium
        )
        config.cosigners.forEachIndexed { index, cosigner ->
            CosignerCard(index = index, cosigner = cosigner)
        }

        Spacer(Modifier.height(8.dp))

        Button(
            onClick = onConfirm,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isProcessing
        ) {
            if (state.isProcessing) {
                CircularProgressIndicator(
                    modifier = Modifier.size(20.dp),
                    color = MaterialTheme.colorScheme.onPrimary,
                    strokeWidth = 2.dp
                )
                Spacer(Modifier.width(8.dp))
            }
            Text(if (state.isProcessing) "Registering..." else "Confirm & Register")
        }
        OutlinedButton(
            onClick = onCancel,
            modifier = Modifier.fillMaxWidth(),
            enabled = !state.isProcessing
        ) {
            Text("Cancel")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun ReceiptContent(
    state: VerifyMultisigViewModel.UiState,
    onReVerify: () -> Unit,
    onDone: () -> Unit
) {
    val config = state.config ?: return
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState()),
        verticalArrangement = Arrangement.spacedBy(12.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Spacer(Modifier.height(16.dp))
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer)
        ) {
            Column(
                modifier = Modifier.padding(20.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Text(
                    text = "Registered",
                    style = MaterialTheme.typography.headlineSmall,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.fillMaxWidth()
                )
                Text(
                    text = "${config.m}-of-${config.n} multisig · ${config.scriptType.displayLabel()}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                    textAlign = TextAlign.Center
                )
                Text(
                    text = "This wallet is verified and can sign transactions.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSecondaryContainer.copy(alpha = 0.9f),
                    textAlign = TextAlign.Center
                )
            }
        }

        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp)
            ) {
                LabeledMono(label = "First receive address", value = state.firstReceiveAddress.ifEmpty { "—" })
                LabeledMono(label = "Descriptor checksum", value = if (state.descriptorChecksum.isNotEmpty()) "#${state.descriptorChecksum}" else "—")
            }
        }

        Spacer(Modifier.height(8.dp))
        Button(onClick = onDone, modifier = Modifier.fillMaxWidth()) {
            Text("Done")
        }
        OutlinedButton(onClick = onReVerify, modifier = Modifier.fillMaxWidth()) {
            Text("Re-verify cosigners")
        }
        Spacer(Modifier.height(16.dp))
    }
}

@Composable
private fun CosignerCard(index: Int, cosigner: CosignerInfo) {
    var expanded by remember { mutableStateOf(false) }
    var showQr by remember { mutableStateOf(false) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (cosigner.isLocal)
                MaterialTheme.colorScheme.secondaryContainer
            else
                MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "Key ${index + 1}",
                        style = MaterialTheme.typography.titleSmall,
                        fontWeight = FontWeight.Medium
                    )
                    Text(
                        text = cosigner.fingerprint.uppercase(),
                        style = MaterialTheme.typography.bodyMedium,
                        fontFamily = FontFamily.Monospace
                    )
                    Text(
                        text = "m/${cosigner.derivationPath}",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                if (cosigner.isLocal) {
                    Surface(
                        color = MaterialTheme.colorScheme.primary,
                        contentColor = MaterialTheme.colorScheme.onPrimary,
                        shape = RoundedCornerShape(4.dp)
                    ) {
                        Text(
                            text = "This device",
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp),
                            style = MaterialTheme.typography.labelSmall,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }

            TextButton(onClick = { expanded = !expanded }) {
                Text(if (expanded) "Hide key" else "Show key")
            }

            if (expanded) {
                Text(
                    text = cosigner.xpub,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                    modifier = Modifier.fillMaxWidth()
                )
                TextButton(onClick = { showQr = !showQr }) {
                    Text(if (showQr) "Hide QR" else "Show QR")
                }
                if (showQr) {
                    val qr = remember(cosigner.xpub) { QRCodeGenerator.generateQRCode(cosigner.xpub, 768) }
                    qr?.let {
                        Image(
                            bitmap = it.asImageBitmap(),
                            contentDescription = "Cosigner ${index + 1} key QR",
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(vertical = 8.dp)
                                .size(240.dp)
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun LabeledMono(label: String, value: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelMedium,
            fontWeight = FontWeight.Medium
        )
        Text(
            text = value,
            style = MaterialTheme.typography.bodyMedium,
            fontFamily = FontFamily.Monospace
        )
    }
}

@Composable
private fun ErrorContent(message: String, onDismiss: () -> Unit) {
    Column(
        modifier = Modifier.fillMaxSize(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Text(
            text = message.ifEmpty { "Something went wrong." },
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.error,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onDismiss) { Text("Go back") }
    }
}

private fun MultisigScriptType.displayLabel(): String = when (this) {
    MultisigScriptType.P2WSH -> "Native SegWit · P2WSH"
    MultisigScriptType.P2SH_P2WSH -> "Nested SegWit · P2SH-P2WSH"
    MultisigScriptType.P2SH -> "Legacy · P2SH"
}
