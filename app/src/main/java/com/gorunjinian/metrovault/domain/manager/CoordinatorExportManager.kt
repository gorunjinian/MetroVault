package com.gorunjinian.metrovault.domain.manager

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.CoordinatorExportError
import com.gorunjinian.metrovault.data.model.CoordinatorExportResult
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.bitcoin.CoordinatorExportService
import com.gorunjinian.metrovault.domain.service.bitcoin.KeyEncodingService
import com.gorunjinian.vaultovich.DeterministicWallet

/**
 * Watch-only coordinator exports for a loaded single-sig wallet: the single-account export, the
 * combined Coldcard Generic JSON, and the Nunchuk BIP48 signer record.
 *
 * This is the private-key side of those exports. It derives the account public keys from the
 * wallet's master key and hands only public keys to [CoordinatorExportService], which never sees
 * a mnemonic, seed, private descriptor or extended private key. It touches no storage, so the
 * caller decides which wallet is active and whether an export applies to it.
 */
class CoordinatorExportManager(
    private val keyEncodingService: KeyEncodingService,
    private val coordinatorExportService: CoordinatorExportService
) {
    companion object {
        private const val TAG = "CoordinatorExportManager"
    }

    /**
     * Single-account export for [accountNumber] under [state]'s own purpose. The active account
     * reuses its cached public key, so xpub-only stateless wallets can still export it; other
     * accounts are derived from the master key when one is loaded.
     */
    fun buildExport(state: WalletState, accountNumber: Int): CoordinatorExportResult {
        val accountPublicKey = getAccountPublicKeyForAccount(state, accountNumber)
            ?: return CoordinatorExportResult.Error(CoordinatorExportError.ACCOUNT_KEY_UNAVAILABLE)
        return try {
            CoordinatorExportResult.Available(
                coordinatorExportService.buildExport(
                    walletName = state.name,
                    masterFingerprint = state.fingerprint,
                    derivationPath = DerivationPaths.withAccountNumber(state.derivationPath, accountNumber),
                    accountPublicKey = accountPublicKey
                )
            )
        } catch (e: IllegalArgumentException) {
            AppLog.w(TAG, e) { "Coordinator export rejected account $accountNumber" }
            CoordinatorExportResult.Unsupported(CoordinatorExportError.UNSUPPORTED_DERIVATION_PATH)
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Coordinator export failed for account $accountNumber" }
            CoordinatorExportResult.Error(CoordinatorExportError.BUILD_FAILED)
        }
    }

    /**
     * Combined Coldcard Generic JSON for [accountNumber]: all four single-sig sections plus both
     * BIP48 multisig sections in one payload. Five of the six sections are outside the wallet's
     * own account path, hence the master key. Null if the export can't be built.
     */
    fun buildCombinedExport(
        master: DeterministicWallet.ExtendedPrivateKey,
        masterFingerprint: String,
        accountNumber: Int,
        isTestnet: Boolean
    ): String? {
        return try {
            coordinatorExportService.buildCombinedExport(
                masterFingerprint = masterFingerprint,
                accountNumber = accountNumber,
                isTestnet = isTestnet,
                keys = CoordinatorExportService.CombinedAccountKeys(
                    bip44 = master.derivePrivateKey(DerivationPaths.legacy(accountNumber, isTestnet)).extendedPublicKey,
                    bip49 = master.derivePrivateKey(DerivationPaths.nestedSegwit(accountNumber, isTestnet)).extendedPublicKey,
                    bip84 = master.derivePrivateKey(DerivationPaths.nativeSegwit(accountNumber, isTestnet)).extendedPublicKey,
                    bip86 = master.derivePrivateKey(DerivationPaths.taproot(accountNumber, isTestnet)).extendedPublicKey,
                    bip48P2shP2wsh = master.derivePrivateKey(
                        DerivationPaths.bip48(accountNumber, DerivationPaths.Bip48ScriptType.P2SH_P2WSH, isTestnet)
                    ).extendedPublicKey,
                    bip48P2wsh = master.derivePrivateKey(
                        DerivationPaths.bip48(accountNumber, DerivationPaths.Bip48ScriptType.P2WSH, isTestnet)
                    ).extendedPublicKey
                )
            )
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Combined coordinator export failed for account $accountNumber" }
            null
        }
    }

    /** Nunchuk BIP48 multisig signer record for [accountNumber]. Empty on error. */
    fun buildNunchukBip48SignerRecord(
        master: DeterministicWallet.ExtendedPrivateKey,
        masterFingerprint: String,
        accountNumber: Int,
        bip48ScriptType: DerivationPaths.Bip48ScriptType,
        isTestnet: Boolean
    ): String {
        return try {
            val bip48Key = master.derivePrivateKey(
                DerivationPaths.bip48(accountNumber, bip48ScriptType, isTestnet)
            ).extendedPublicKey
            CoordinatorExportService.buildNunchukBip48SignerRecord(
                masterFingerprint = masterFingerprint,
                accountNumber = accountNumber,
                bip48ScriptType = bip48ScriptType,
                standardAccountXpub = keyEncodingService.getStandardAccountXpub(bip48Key, isTestnet),
                isTestnet = isTestnet
            )
        } catch (e: Exception) {
            AppLog.w(TAG, e) { "Nunchuk BIP48 signer record unavailable for account $accountNumber" }
            ""
        }
    }

    /**
     * Account public key at [accountNumber] under [state]'s own purpose. Prefers the cached key
     * when the account matches the active one; otherwise derives from the master key.
     */
    private fun getAccountPublicKeyForAccount(
        state: WalletState,
        accountNumber: Int
    ): DeterministicWallet.ExtendedPublicKey? {
        if (DerivationPaths.getAccountNumber(state.derivationPath) == accountNumber) {
            state.getAccountPublicKey()?.let { return it }
        }
        val master = state.getMasterPrivateKey() ?: return null
        return try {
            master.derivePrivateKey(
                DerivationPaths.withAccountNumber(state.derivationPath, accountNumber)
            ).extendedPublicKey
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to derive the account public key for account $accountNumber" }
            null
        }
    }
}
