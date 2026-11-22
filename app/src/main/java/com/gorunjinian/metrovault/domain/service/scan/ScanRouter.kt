package com.gorunjinian.metrovault.domain.service.scan

import com.gorunjinian.metrovault.core.util.NetworkUtils
import com.gorunjinian.metrovault.data.model.WalletFeature
import com.gorunjinian.metrovault.data.model.WalletKind
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletProfile
import com.gorunjinian.vaultovich.DeterministicWallet

/**
 * A wallet as the router sees it: only what deciding where a scan goes needs. Built from stored
 * metadata, so no wallet has to be opened (and no key loaded) to route a scan from Home.
 *
 * @property id Null for a stateless wallet, which has no stored record
 * @property fingerprints Lowercase master fingerprints of the wallet's keys: its own, plus every
 *   local cosigner key for a multisig
 * @property silentPaymentScanPubKey Compressed-point hex of B_scan; empty unless silent payment
 */
data class ScanWallet(
    val id: String?,
    val profile: WalletProfile,
    val isTestnet: Boolean,
    val fingerprints: Set<String>,
    val silentPaymentScanPubKey: String = "",
    val silentPaymentSpendPubKey: String = ""
) {
    fun acceptsNetwork(network: ScanNetwork): Boolean =
        if (network == ScanNetwork.MAINNET) !isTestnet else isTestnet

    companion object {
        fun of(metadata: WalletMetadata): ScanWallet {
            val config = metadata.multisigConfig
            // A multisig's derivationPath is a display label ("multisig/2of3"); its cosigners carry the chain.
            val isTestnet = config?.isTestnet() ?: NetworkUtils.isTestnetPath(metadata.derivationPath)
            val fingerprints = (listOf(metadata.masterFingerprint) + config?.localKeyFingerprints.orEmpty())
                .map { it.trim().lowercase() }
                .filterTo(mutableSetOf()) { it.isNotEmpty() }
            return ScanWallet(
                id = metadata.id,
                profile = WalletProfile.of(metadata),
                isTestnet = isTestnet,
                fingerprints = fingerprints,
                silentPaymentScanPubKey = metadata.silentPaymentScanPubKey.lowercase(),
                silentPaymentSpendPubKey = metadata.silentPaymentSpendPubKey.lowercase()
            )
        }
    }
}

/**
 * Where the scanner was opened.
 *
 * @property wallets Every stored wallet of the current vault (main or decoy)
 * @property activeWallet The wallet the scanner was opened from; null when opened from Home
 */
data class ScanContext(
    val wallets: List<ScanWallet>,
    val activeWallet: ScanWallet? = null
)

/** Which wallet an action runs against. */
sealed interface WalletTarget {
    /** The wallet the scanner was opened from. */
    data object ActiveWallet : WalletTarget

    /** Exactly one stored wallet holds a key the payload names. */
    data class Resolved(val walletId: String) : WalletTarget

    /** The user picks from [walletIds], never empty, in vault order. */
    data class Choose(val walletIds: List<String>, val reason: ChooseReason) : WalletTarget
}

enum class ChooseReason {
    /** Several wallets hold a key the payload names. */
    SEVERAL_MATCH,

    /** The payload names keys, none of them in this vault. */
    NO_MATCH,

    /** The payload doesn't say which wallet it is for (addresses, text requests). */
    NOT_DETERMINABLE
}

/** Whether the target wallet holds a key the payload names by fingerprint. */
enum class KeyMatch { MATCHED, NOT_MATCHED, NO_KEY_INFO }

/** What to do with a scan. Nothing here has been signed, stored or verified yet. */
sealed interface ScanAction {
    data class SignPsbt(
        val psbt: ScannedPayload.Psbt,
        val target: WalletTarget,
        val keyMatch: KeyMatch
    ) : ScanAction

    data class SignMessagePsbt(
        val psbt: ScannedPayload.MessagePsbt,
        val target: WalletTarget,
        val keyMatch: KeyMatch
    ) : ScanAction

    data class SignTextRequest(val request: ScannedPayload.SignRequest, val target: WalletTarget) : ScanAction

    data class CheckAddress(val address: ScannedPayload.Address, val target: WalletTarget) : ScanAction

    /**
     * Answered from stored metadata alone: a silent payment address is one fixed pair of keys.
     * Only a wallet's base (unlabeled) address for its stored keys counts.
     *
     * @property activeWalletOwns Whether the open wallet owns it; null when scanned from Home
     * @property ownerWalletIds Every stored wallet that owns it
     */
    data class SilentPaymentAddressOwnership(
        val address: ScannedPayload.SilentPaymentAddress,
        val activeWalletOwns: Boolean?,
        val ownerWalletIds: List<String>
    ) : ScanAction

    data class CheckExtendedKeys(
        val keys: ScannedPayload.ExtendedKeys,
        val target: WalletTarget,
        val keyMatch: KeyMatch
    ) : ScanAction

    data class VerifySignedMessage(val message: ScannedPayload.SignedMessage) : ScanAction

    /** Open Verify with the signature filled in; the address and message come from the user. */
    data class VerifySignature(val signature: ScannedPayload.Signature) : ScanAction

    /**
     * Offer a permanent import or a stateless session.
     *
     * @property existingWalletIds Stored wallets already made from this seed without a passphrase
     */
    data class ImportSeed(val seed: ScannedPayload.Seed, val existingWalletIds: List<String>) : ScanAction

    data class ImportMultisig(val setup: ScannedPayload.MultisigSetup) : ScanAction

    data class ShowTransaction(val transaction: ScannedPayload.RawTransaction) : ScanAction

    data class ShowText(val text: ScannedPayload.Text) : ScanAction

    /** A valid payload that no wallet here can act on; [reason] is fit to show the user. */
    data class Unsupported(val payload: ScannedPayload, val reason: String) : ScanAction

    /** The scan claimed to be something it isn't; [ScannedPayload.Invalid.reason] says what. */
    data class Rejected(val invalid: ScannedPayload.Invalid) : ScanAction
}

/**
 * Turns a [ScannedPayload] into a [ScanAction] for the [ScanContext] the scanner was opened in.
 *
 * - **In a wallet**: every wallet action targets the open wallet, or is refused when that wallet
 *   can't do it (a multisig can't sign messages, a silent payment wallet has no address list, a
 *   testnet wallet can't check a mainnet address). A PSBT that doesn't name the wallet's key is
 *   still routed to it, flagged [KeyMatch.NOT_MATCHED], since signing can fall back to address
 *   lookup.
 * - **From Home**: PSBTs and keys go to the wallet whose stored fingerprint they name; the user
 *   picks when several match, none match, or the payload names no key at all (addresses and text
 *   requests). Candidates are narrowed to wallets that support the action on the right network.
 *
 * Pure apart from [seedFingerprint], which the caller supplies.
 *
 * @param seedFingerprint The master fingerprint (lowercase hex) of a mnemonic with no passphrase,
 *   or null if it can't be computed; used to warn that a scanned seed is already in the vault
 */
class ScanRouter(private val seedFingerprint: (List<String>) -> String?) {

    fun route(payload: ScannedPayload, context: ScanContext): ScanAction = when (payload) {
        is ScannedPayload.Psbt -> routeWalletAction(payload, context, payload.inputFingerprints, PSBT_SIGNING) { target, match ->
            ScanAction.SignPsbt(payload, target, match)
        }
        is ScannedPayload.MessagePsbt -> routeWalletAction(payload, context, payload.inputFingerprints, MESSAGE_PSBT_SIGNING) { target, match ->
            ScanAction.SignMessagePsbt(payload, target, match)
        }
        is ScannedPayload.SignRequest -> routeWalletAction(payload, context, emptySet(), textRequestSigning(payload)) { target, _ ->
            ScanAction.SignTextRequest(payload, target)
        }
        is ScannedPayload.Address -> routeWalletAction(payload, context, emptySet(), addressCheck(payload.network)) { target, _ ->
            ScanAction.CheckAddress(payload, target)
        }
        is ScannedPayload.ExtendedKeys -> routeWalletAction(
            payload,
            context,
            payload.keys.mapNotNullTo(mutableSetOf()) { it.fingerprint },
            keyCheck(payload.keys.map { it.network }.toSet())
        ) { target, match ->
            ScanAction.CheckExtendedKeys(payload, target, match)
        }
        is ScannedPayload.SilentPaymentAddress -> routeSilentPaymentAddress(payload, context)
        is ScannedPayload.Seed -> routeSeed(payload, context)
        is ScannedPayload.SignedMessage -> ScanAction.VerifySignedMessage(payload)
        is ScannedPayload.Signature -> ScanAction.VerifySignature(payload)
        is ScannedPayload.MultisigSetup -> ScanAction.ImportMultisig(payload)
        is ScannedPayload.RawTransaction -> ScanAction.ShowTransaction(payload)
        is ScannedPayload.Text -> ScanAction.ShowText(payload)
        is ScannedPayload.Invalid -> ScanAction.Rejected(payload)
    }

    /**
     * Which wallets can take an action: [refusal] is null for a wallet that can, or the reason it
     * can't (shown when it is the open wallet). [noWalletReason] is shown when, from Home, no
     * stored wallet can.
     */
    private class Eligibility(
        val noWalletReason: String,
        val refusal: (ScanWallet) -> String?
    )

    private fun routeWalletAction(
        payload: ScannedPayload,
        context: ScanContext,
        fingerprints: Set<String>,
        eligibility: Eligibility,
        action: (WalletTarget, KeyMatch) -> ScanAction
    ): ScanAction {
        val active = context.activeWallet
        if (active != null) {
            eligibility.refusal(active)?.let { return ScanAction.Unsupported(payload, it) }
            val match = when {
                fingerprints.isEmpty() -> KeyMatch.NO_KEY_INFO
                active.fingerprints.any { it in fingerprints } -> KeyMatch.MATCHED
                else -> KeyMatch.NOT_MATCHED
            }
            return action(WalletTarget.ActiveWallet, match)
        }

        val eligible = context.wallets.filter { it.id != null && eligibility.refusal(it) == null }
        if (eligible.isEmpty()) return ScanAction.Unsupported(payload, eligibility.noWalletReason)
        if (fingerprints.isEmpty()) {
            return action(WalletTarget.Choose(eligible.ids(), ChooseReason.NOT_DETERMINABLE), KeyMatch.NO_KEY_INFO)
        }
        val matched = eligible.filter { wallet -> wallet.fingerprints.any { it in fingerprints } }
        return when (matched.size) {
            0 -> action(WalletTarget.Choose(eligible.ids(), ChooseReason.NO_MATCH), KeyMatch.NOT_MATCHED)
            1 -> action(WalletTarget.Resolved(matched.single().id!!), KeyMatch.MATCHED)
            else -> action(WalletTarget.Choose(matched.ids(), ChooseReason.SEVERAL_MATCH), KeyMatch.MATCHED)
        }
    }

    private fun routeSilentPaymentAddress(
        payload: ScannedPayload.SilentPaymentAddress,
        context: ScanContext
    ): ScanAction {
        fun owns(wallet: ScanWallet) = wallet.profile.kind == WalletKind.SILENT_PAYMENT &&
            wallet.acceptsNetwork(payload.network) &&
            wallet.silentPaymentScanPubKey == payload.scanPubKeyHex &&
            wallet.silentPaymentSpendPubKey == payload.spendPubKeyHex

        return ScanAction.SilentPaymentAddressOwnership(
            address = payload,
            activeWalletOwns = context.activeWallet?.let { owns(it) },
            ownerWalletIds = context.wallets.filter { it.id != null && owns(it) }.ids()
        )
    }

    private fun routeSeed(payload: ScannedPayload.Seed, context: ScanContext): ScanAction {
        val fingerprint = seedFingerprint(payload.words)?.lowercase()
        val existing = if (fingerprint == null) {
            emptyList()
        } else {
            // A multisig shares a cosigner's fingerprint without being a wallet "made from" the seed.
            context.wallets.filter {
                it.id != null && it.profile.kind != WalletKind.MULTISIG && fingerprint in it.fingerprints
            }.ids()
        }
        return ScanAction.ImportSeed(payload, existing)
    }

    private fun List<ScanWallet>.ids(): List<String> = mapNotNull { it.id }

    private companion object {
        val PSBT_SIGNING = Eligibility(
            noWalletReason = "There's no wallet in this vault to sign it with.",
            refusal = { null }
        )

        val MESSAGE_PSBT_SIGNING = Eligibility(
            noWalletReason = "No wallet in this vault can sign messages."
        ) { wallet ->
            if (wallet.profile.supports(WalletFeature.SIGN_MESSAGE)) null else "Multisig wallets can't sign messages."
        }

        fun textRequestSigning(payload: ScannedPayload.SignRequest): Eligibility {
            // The requester derived its address from this path, so its coin type fixes the chain.
            val network = when (payload.request.keyPath.path.getOrNull(1)) {
                DeterministicWallet.hardened(0) -> ScanNetwork.MAINNET
                DeterministicWallet.hardened(1) -> ScanNetwork.TESTNET
                else -> null
            }
            return Eligibility(noWalletReason = "No wallet in this vault can sign this request.") { wallet ->
                when {
                    !wallet.profile.supports(WalletFeature.SIGN_MESSAGE) -> "Multisig wallets can't sign messages."
                    wallet.profile.kind == WalletKind.SILENT_PAYMENT ->
                        "Silent payment wallets sign messages only from the watching wallet's message-signing PSBT QR."
                    network != null && !wallet.acceptsNetwork(network) -> networkMismatch("request", network)
                    else -> null
                }
            }
        }

        fun addressCheck(network: ScanNetwork) = Eligibility(
            noWalletReason = "No wallet in this vault can check a ${network.label} address."
        ) { wallet ->
            when {
                !wallet.profile.supports(WalletFeature.CHECK_ADDRESS) ->
                    "Silent payment wallets don't have a fixed address list to check against."
                !wallet.acceptsNetwork(network) -> networkMismatch("address", network)
                else -> null
            }
        }

        fun keyCheck(networks: Set<ScanNetwork>) = Eligibility(
            noWalletReason = "No single-signature wallet in this vault is on this key's network."
        ) { wallet ->
            when {
                wallet.profile.kind != WalletKind.SINGLE_SIG -> "Only single-signature wallets can check extended public keys."
                networks.none { wallet.acceptsNetwork(it) } -> networkMismatch("key", networks.first())
                else -> null
            }
        }

        fun networkMismatch(what: String, network: ScanNetwork): String {
            val walletNetwork = if (network == ScanNetwork.MAINNET) "testnet" else "mainnet"
            return "This is a ${network.label} $what, but this wallet is on $walletNetwork."
        }

        val ScanNetwork.label: String
            get() = when (this) {
                ScanNetwork.MAINNET -> "mainnet"
                ScanNetwork.TESTNET -> "testnet"
                ScanNetwork.REGTEST -> "regtest"
            }
    }
}
