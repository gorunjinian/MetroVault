package com.gorunjinian.metrovault.feature.wallet.details

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.gorunjinian.metrovault.R
import com.gorunjinian.metrovault.core.ui.components.ActionCard
import com.gorunjinian.metrovault.core.ui.components.MetroTopBar
import com.gorunjinian.metrovault.domain.Wallet
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.core.ui.dialogs.PasswordGatedWarningDialog
import com.gorunjinian.metrovault.data.repository.UserPreferencesRepository

/**
 * Recovery-material view requiring the warning + password gate before navigating.
 * [risk] explains what exposing this particular material gives away.
 */
private enum class SensitiveViewTarget(val title: String, val confirmLabel: String, val risk: String) {
    SEED_PHRASE(
        title = "Show Seed Phrase?",
        confirmLabel = "Show Seed Phrase",
        risk = "Your seed phrase is the master backup of this wallet. Anyone who sees it or scans " +
            "its SeedQR can take your funds, together with your passphrase if you use one."
    ),
    ROOT_KEY(
        title = "Show Root Key?",
        confirmLabel = "Show Root Key",
        risk = "Your BIP32 root key controls every account and address in this wallet, with any " +
            "passphrase already applied. Anyone who sees it can take your funds."
    )
}

/**
 * ExportOptionsScreen - Navigation hub for the wallet's exports.
 *
 * Watch-only exports on top: guided coordinator setup, raw account keys and descriptors, and
 * Silent Payments material — each gated to the wallet types it applies to. Below the divider,
 * the recovery material (BIP32 root key, seed phrase) sits behind a security warning plus
 * password confirmation.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ExportOptionsScreen(
    wallet: Wallet,
    secureStorage: SecureStorage,
    userPreferencesRepository: UserPreferencesRepository,
    isStatelessWallet: Boolean = false,
    onBack: () -> Unit,
    onExportCoordinator: () -> Unit,
    onViewAccountKeys: () -> Unit,
    onViewDescriptors: () -> Unit,
    onViewRootKey: () -> Unit,
    onViewSeedPhrase: () -> Unit,
    onViewSilentPayments: () -> Unit = {}
) {
    // BIP-352 silent-payment export visibility = (toggle ON OR SP-flagged wallet) AND seed loaded.
    // SP-flagged wallets always show the export; regular wallets only show it when the user has
    // opted in via Advanced Settings.
    val silentPaymentsEnabled by userPreferencesRepository.silentPaymentsEnabled.collectAsState()
    val isSpFlaggedWallet = remember { wallet.isActiveSilentPayment() }
    val canDeriveSilentPayments = remember { wallet.canExportSilentPaymentForActiveWallet() }
    val canExportSilentPayments = canDeriveSilentPayments && (silentPaymentsEnabled || isSpFlaggedWallet)
    // Coordinator export is single-sig only. Hide the card rather than letting the user tap
    // through to an "Unsupported wallet" screen, matching how the cards below are gated.
    val canExportToCoordinator = remember { !wallet.isActiveMultisig() && !wallet.isActiveSilentPayment() }

    // Sensitive-view gate: pick a target, acknowledge the warning, then confirm the password.
    var pendingTarget by remember { mutableStateOf<SensitiveViewTarget?>(null) }

    Scaffold(
        topBar = {
            MetroTopBar(
                title = "Export",
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

            Text(
                text = "Export Options",
                style = MaterialTheme.typography.headlineSmall
            )

            if (canExportToCoordinator) {
                ActionCard(
                    icon = R.drawable.ic_qr_code_scanner,
                    title = "Export to Wallet Coordinator",
                    description = "Watch-only setup for Sparrow, Nunchuk & Coldcard-compatible apps",
                    onClick = onExportCoordinator
                )
            }

            // Cards 1 & 2: View Account Extended Keys and View Output Descriptors.
            // Hidden for SP-flagged wallets — BIP-352 wallets have no meaningful xpub or wpkh/tr
            // descriptor; the SP-equivalent export is the spscan/descriptor inside the Silent
            // Payments card. Root Key and Seed Phrase still expose the underlying master.
            if (!isSpFlaggedWallet) {
                ActionCard(
                    icon = R.drawable.ic_key,
                    title = "View Account Extended Keys",
                    description = "Extended public & private keys",
                    onClick = onViewAccountKeys
                )

                ActionCard(
                    icon = R.drawable.ic_desciption,
                    title = "View Output Descriptors",
                    description = "Public & spending descriptors",
                    onClick = onViewDescriptors
                )
            }

            // Card: Silent Payments (sp1q… + gated spscan…) — single-sig seed-based wallets only
            if (canExportSilentPayments) {
                ActionCard(
                    icon = R.drawable.ic_person_shield,
                    title = "Silent Payments",
                    description = "SP Scan key & descriptor for Silent Payments",
                    onClick = onViewSilentPayments
                )
            }

            // Separates the exports above from the recovery material below
            HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))

            // Card 3: View Root Key
            ActionCard(
                icon = R.drawable.ic_root,
                title = "View BIP32 Root Key",
                description = "Show your wallet's main BIP32 root key",
                onClick = { pendingTarget = SensitiveViewTarget.ROOT_KEY },
                iconTint = MaterialTheme.colorScheme.error,
                descriptionColor = MaterialTheme.colorScheme.error
            )

            // Card 4: View Seed Phrase (hidden for stateless wallets)
            if (!isStatelessWallet) {
                ActionCard(
                    icon = R.drawable.ic_privacy_tip,
                    title = "View Seed Phrase",
                    description = "Show your recovery seed phrase or SeedQR",
                    onClick = { pendingTarget = SensitiveViewTarget.SEED_PHRASE },
                    iconTint = MaterialTheme.colorScheme.error,
                    descriptionColor = MaterialTheme.colorScheme.error
                )
            }

            Spacer(modifier = Modifier.height(24.dp))
        }
    }

    pendingTarget?.let { target ->
        PasswordGatedWarningDialog(
            title = target.title,
            confirmLabel = target.confirmLabel,
            secureStorage = secureStorage,
            isDecoyMode = wallet.isDecoyMode,
            onDismiss = { pendingTarget = null },
            onVerified = {
                pendingTarget = null
                when (target) {
                    SensitiveViewTarget.SEED_PHRASE -> onViewSeedPhrase()
                    SensitiveViewTarget.ROOT_KEY -> onViewRootKey()
                }
            },
            destructive = false
        ) {
            Text(target.risk)
            Text("Never share it or enter it into a website or online device. Make sure you're somewhere private and no one can see your screen.")
        }
    }
}
