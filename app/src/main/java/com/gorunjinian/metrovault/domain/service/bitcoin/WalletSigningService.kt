package com.gorunjinian.metrovault.domain.service.bitcoin

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.InputSigningRefusal
import com.gorunjinian.metrovault.data.model.SpSpendingError
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.psbt.PsbtUtils
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentReceiveSigner
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentSender
import com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentWalletService
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.PrivateKey
import com.gorunjinian.metrovault.lib.bitcoin.Psbt
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either

/**
 * Orchestrates PSBT signing for both single-sig and multi-sig wallets.
 *
 * Extracted from Wallet.kt to follow single responsibility principle.
 *
 * Signing strategy:
 * 1. For single-sig: Use loaded wallet state directly
 * 2. For multi-sig: Iterate through all local keys and sign with each
 *    - Try loaded wallet states first (faster)
 *    - Fall back to direct derivation from stored seeds
 */
class WalletSigningService(
    private val secureStorage: SecureStorage,
    private val bitcoinService: BitcoinService
) {
    companion object {
        private const val TAG = "WalletSigningService"
    }

    /**
     * Result of PSBT signing operation.
     */
    sealed class SigningResult {
        /**
         * Signing succeeded.
         * @property signedPsbt The signed PSBT in base64 format
         * @property alternativePathsUsed List of alternative derivation paths used (for diagnostics)
         * @property usedAddressLookupFallback true if any input was signed via Stage 3
         *   (address-lookup fallback) rather than via BIP-174 derivation path matching.
         *   UI should surface a non-blocking warning when this is true.
         * @property addressLookupInputIndices The input indices that fell through to
         *   Stage 3. Empty when `usedAddressLookupFallback` is false.
         */
        data class Success(
            val signedPsbt: String,
            val alternativePathsUsed: List<String> = emptyList(),
            val usedAddressLookupFallback: Boolean = false,
            val addressLookupInputIndices: List<Int> = emptyList(),
            /**
             * Inputs that belong to this wallet but which MetroVault declined to sign. Non-empty
             * means the PSBT came back only *partially* signed — the UI must say so, because a
             * partial result is otherwise indistinguishable from a complete one.
             */
            val refusals: List<InputSigningRefusal> = emptyList(),
        ) : SigningResult()

        /**
         * Signing failed with a specific error.
         * @property error The error type
         * @property message Human-readable error message
         */
        data class Failure(
            val error: SigningError,
            val message: String,
            /** Per-input refusals, when inputs matched this wallet but signing was declined. */
            val refusals: List<InputSigningRefusal> = emptyList(),
        ) : SigningResult()
    }

    /**
     * Specific error types for signing failures.
     */
    enum class SigningError {
        /** No wallet is currently active/loaded */
        NO_ACTIVE_WALLET,
        /** Wallet state is not loaded (keys not in memory) */
        WALLET_NOT_LOADED,
        /** Multisig wallet has no local keys configured */
        NO_LOCAL_KEYS,
        /** Failed to load key material from storage */
        KEY_LOAD_FAILED,
        /** Multisig config is missing or invalid */
        INVALID_MULTISIG_CONFIG,
        /** PSBT signing operation failed (no inputs could be signed) */
        SIGNING_FAILED,
        /** Key derivation failed */
        KEY_DERIVATION_FAILED,
        /** A silent-payment send could not be resolved (e.g. ineligible inputs, unsafe sighash) */
        SILENT_PAYMENT_REJECTED,
        /** Multisig wallet has not been verified/registered yet — signing is blocked until it is */
        WALLET_NOT_VERIFIED,
        /** A PSBT output claims to be our change but does not derive from the registered descriptor */
        CHANGE_OUTPUT_MISMATCH
    }

    /**
     * Signs a PSBT for a single-sig wallet.
     */
    fun signSingleSig(
        psbtString: String,
        walletState: WalletState,
        isTestnet: Boolean
    ): SigningResult {
        val masterPrivateKey = walletState.getMasterPrivateKey()
        val accountPrivateKey = walletState.getAccountPrivateKey()

        if (masterPrivateKey == null || accountPrivateKey == null) {
            return SigningResult.Failure(
                SigningError.KEY_DERIVATION_FAILED,
                "Failed to access wallet keys. Try reloading the wallet."
            )
        }

        val scriptType = DerivationPaths.getScriptType(walletState.derivationPath)
        val accountPath = KeyPath(walletState.derivationPath)

        // The BIP-352 spend key, for resolving/signing inputs that spend received SP outputs.
        val spSpendPrivateKey = if (DerivationPaths.getPurpose(walletState.derivationPath) == 352) {
            SilentPaymentWalletService.deriveKeys(
                masterPrivateKey, DerivationPaths.getAccountNumber(walletState.derivationPath), isTestnet
            ).spendPrivateKey
        } else null

        // Silent-payments (Story A) pre-stage: if the PSBT pays to one or more sp1q… recipients,
        // derive the real taproot outputs from our input keys and splice them in before signing.
        // The output scripts are committed to in every input's sighash, so this must run first.
        // The spend key lets it resolve inputs that are themselves received SP outputs.
        val psbtToSign = when (val resolved = resolveSilentPayments(psbtString, masterPrivateKey, accountPrivateKey, scriptType, isTestnet, spSpendPrivateKey)) {
            is Either.Left -> return SigningResult.Failure(SigningError.SILENT_PAYMENT_REJECTED, resolved.value)
            is Either.Right -> resolved.value
        }

        // Silent-payments receive pre-stage: if the PSBT spends a received SP output
        // (carries PSBT_IN_SP_TWEAK), sign those inputs with d = b_spend + tweak. Runs before the
        // normal signer, which then handles any remaining (non-SP) inputs in a mixed transaction.
        val (psbtAfterReceive, spInputsSigned) = when (
            val r = signSilentPaymentReceives(psbtToSign, spSpendPrivateKey)
        ) {
            is Either.Left -> return SigningResult.Failure(SigningError.SILENT_PAYMENT_REJECTED, r.value)
            is Either.Right -> r.value
        }

        val signingResult = bitcoinService.signPsbt(
            psbtAfterReceive,
            masterPrivateKey,
            accountPrivateKey,
            scriptType,
            isTestnet,
            accountPath,
        )

        return when {
            signingResult is Either.Right -> SigningResult.Success(
                signedPsbt = signingResult.value.signedPsbt,
                alternativePathsUsed = signingResult.value.alternativePathsUsed,
                usedAddressLookupFallback = signingResult.value.usedAddressLookupFallback,
                addressLookupInputIndices = signingResult.value.addressLookupInputIndices,
                refusals = signingResult.value.refusals,
            )
            // A pure silent-payment spend: the SP inputs were signed above and there were no other
            // inputs for the normal signer to handle, so it's still a success.
            spInputsSigned -> SigningResult.Success(signedPsbt = psbtAfterReceive)
            else -> {
                val refusals = (signingResult as Either.Left).value
                SigningResult.Failure(
                    SigningError.SIGNING_FAILED,
                    describeSigningFailure(refusals),
                    refusals = refusals,
                )
            }
        }
    }

    /**
     * Build the user-facing message for a failed signing attempt.
     *
     * A refusal is only ever recorded for an input we matched to this wallet, so its presence flips
     * the meaning of the failure entirely: not "this isn't your transaction" but "this is yours and
     * MetroVault would not sign it". Saying the former when the latter is true is both false and
     * unactionable, which is what the generic message used to do.
     */
    private fun describeSigningFailure(refusals: List<InputSigningRefusal>): String = when {
        refusals.isEmpty() ->
            "Failed to sign PSBT. The transaction may not contain inputs for this wallet."
        else ->
            "MetroVault found inputs belonging to this wallet but would not sign them:\n\n" +
                refusals.joinToString("\n\n") { it.message }
    }

    /**
     * Receive-side silent-payment signing. Returns the PSBT base64 to continue signing and
     * whether any SP input was signed. If the PSBT carries `PSBT_IN_SP_TWEAK` it's only signable by a
     * silent-payment wallet (path `m/352'/…`); a tweak on a non-SP wallet fails loudly. Returns
     * [Either.Left] with a user-facing message if the SP spend cannot be signed.
     */
    private fun signSilentPaymentReceives(
        psbtString: String,
        spSpendPrivateKey: PrivateKey?,
    ): Either<String, Pair<String, Boolean>> {
        val parsed = PsbtUtils.parsePsbt(psbtString) ?: return Either.Right(psbtString to false)
        if (!SilentPaymentReceiveSigner.hasSilentPaymentTweaks(parsed)) return Either.Right(psbtString to false)

        // The spend key is derived iff the wallet is a silent-payment wallet (path m/352'/…).
        if (spSpendPrivateKey == null) {
            return Either.Left(SpSpendingError.NotSilentPaymentWallet.message)
        }

        return when (val r = SilentPaymentReceiveSigner.signTweakedInputs(parsed, spSpendPrivateKey)) {
            is Either.Right -> {
                val bytes = Psbt.write(r.value).toByteArray()
                Either.Right(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP) to true)
            }
            is Either.Left -> {
                AppLog.w(TAG) { "Silent payment receive signing rejected: ${r.value.message}" }
                Either.Left(r.value.message)
            }
        }
    }

    /**
     * Silent-payment send pre-stage. Returns the PSBT base64 to actually sign:
     * the original string if there are no SP recipients (or it can't be parsed here, in which case
     * the downstream signer reports the parse failure), or a PSBT with the derived P2TR output
     * scripts spliced in. Returns [Either.Left] with a user-facing message if an SP send was
     * detected but cannot be safely resolved.
     *
     * Scope: single-sig only. Multi-sig SP sending is collaborative (needs every cosigner's input
     * keys to derive the shared secret) and is deliberately out of scope.
     */
    private fun resolveSilentPayments(
        psbtString: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean,
        spSpendPrivateKey: PrivateKey? = null,
    ): Either<String, String> {
        val parsed = PsbtUtils.parsePsbt(psbtString) ?: return Either.Right(psbtString)
        if (!SilentPaymentSender.hasSilentPaymentRecipients(parsed)) return Either.Right(psbtString)

        return when (val result = SilentPaymentSender.resolve(parsed, masterPrivateKey, accountPrivateKey, scriptType, isTestnet, spSpendPrivateKey)) {
            is Either.Right -> {
                val bytes = Psbt.write(result.value).toByteArray()
                Either.Right(android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP))
            }
            is Either.Left -> {
                AppLog.w(TAG) { "Silent payment resolution rejected: ${result.value.message}" }
                Either.Left(result.value.message)
            }
        }
    }

    /**
     * Signs a PSBT for a multi-sig wallet.
     * Iterates through all local keys and signs with each.
     *
     * @param psbtString The PSBT to sign
     * @param metadata The wallet metadata
     * @param isDecoyMode Whether we're in decoy mode
     * @param walletStates Map of loaded wallet states (for faster signing if available)
     * @param getSessionKeySeed Function to get session seed for a key (for passphrase wallets)
     */
    fun signMultiSig(
        psbtString: String,
        metadata: WalletMetadata,
        isDecoyMode: Boolean,
        walletStates: Map<String, WalletState>,
        getSessionKeySeed: (String) -> String?
    ): SigningResult {
        val keyIds = metadata.keyIds
        AppLog.d(TAG) { "Multisig signing: ${keyIds.size} local key(s)" }

        if (keyIds.isEmpty()) {
            AppLog.e(TAG) { "No keyIds found for multisig wallet" }
            return SigningResult.Failure(
                SigningError.NO_LOCAL_KEYS,
                "This multisig wallet has no local signing keys. Import a key that matches one of the cosigner fingerprints."
            )
        }

        val config = metadata.multisigConfig
        if (config == null) {
            AppLog.e(TAG) { "No multisigConfig found" }
            return SigningResult.Failure(
                SigningError.INVALID_MULTISIG_CONFIG,
                "Multisig configuration is missing. Re-import the wallet descriptor."
            )
        }

        var signedPsbt = psbtString
        var signedCount = 0
        val allAlternativePathsUsed = mutableListOf<String>()
        var anyUsedAddressLookupFallback = false
        val allAddressLookupInputIndices = sortedSetOf<Int>()
        val failedKeys = mutableListOf<String>()
        // Deduplicated across keys: each local key runs over the whole PSBT, so an input this
        // wallet refuses would otherwise be reported once per key.
        val allRefusals = LinkedHashMap<String, InputSigningRefusal>()

        for (keyId in keyIds) {
            AppLog.d(TAG) { "Processing local key" }
            val key = secureStorage.loadWalletKey(keyId, isDecoyMode)
            if (key == null) {
                AppLog.e(TAG) { "Failed to load WalletKey" }
                failedKeys.add(keyId)
                continue
            }

            // Try loaded wallet state first
            val matchingState = findMatchingWalletState(keyId, walletStates, isDecoyMode)

            val result = if (matchingState != null) {
                signWithLoadedState(signedPsbt, matchingState)
            } else {
                signWithDirectDerivation(signedPsbt, key, config, getSessionKeySeed(keyId))
            }

            fun record(refusal: InputSigningRefusal) {
                allRefusals["${refusal.inputIndex}:${refusal::class.simpleName}"] = refusal
            }
            when (result) {
                is Either.Right -> {
                    val signed = result.value
                    signedPsbt = signed.signedPsbt
                    allAlternativePathsUsed.addAll(signed.alternativePathsUsed)
                    if (signed.usedAddressLookupFallback) {
                        anyUsedAddressLookupFallback = true
                        allAddressLookupInputIndices.addAll(signed.addressLookupInputIndices)
                    }
                    signed.refusals.forEach(::record)
                    signedCount++
                    AppLog.d(TAG) { "Successfully signed portion of PSBT" }
                }
                // This key signed nothing. Keep its refusals: if no key signs anything, they are
                // the only way the failure message can say *why* rather than "not your inputs".
                is Either.Left -> result.value.forEach(::record)
            }
        }

        return if (signedCount > 0) {
            AppLog.d(TAG) { "Multisig signing complete: $signedCount/${keyIds.size} keys signed" }
            SigningResult.Success(
                signedPsbt = signedPsbt,
                alternativePathsUsed = allAlternativePathsUsed,
                usedAddressLookupFallback = anyUsedAddressLookupFallback,
                addressLookupInputIndices = allAddressLookupInputIndices.toList(),
                refusals = allRefusals.values.toList(),
            )
        } else {
            val refusals = allRefusals.values.toList()
            val errorMsg = when {
                failedKeys.isNotEmpty() ->
                    "Failed to load ${failedKeys.size} key(s). Ensure the wallet is properly loaded."
                refusals.isNotEmpty() -> describeSigningFailure(refusals)
                else -> "No inputs could be signed. The PSBT may not contain inputs for your keys."
            }
            SigningResult.Failure(SigningError.SIGNING_FAILED, errorMsg, refusals = refusals)
        }
    }

    /**
     * Find a loaded wallet state that uses the given key.
     */
    private fun findMatchingWalletState(
        keyId: String,
        walletStates: Map<String, WalletState>,
        isDecoyMode: Boolean
    ): WalletState? {
        return walletStates.entries.find { (id, _) ->
            val meta = secureStorage.loadWalletMetadata(id, isDecoyMode)
            meta?.keyIds?.contains(keyId) == true && !meta.isMultisig
        }?.value
    }

    /**
     * Intermediate per-key sign result used by the multisig aggregation loop.
     * Carries the same fields the inner [com.gorunjinian.metrovault.data.model.SigningResult]
     * does, so the outer multisig `SigningResult.Success` can be built without
     * losing fallback-tracking information.
     */
    private data class PerKeySignResult(
        val signedPsbt: String,
        val alternativePathsUsed: List<String>,
        val usedAddressLookupFallback: Boolean,
        val addressLookupInputIndices: List<Int>,
        val refusals: List<InputSigningRefusal> = emptyList(),
    )

    /**
     * Sign using an already-loaded wallet state.
     */
    private fun signWithLoadedState(
        psbt: String,
        state: WalletState
    ): Either<List<InputSigningRefusal>, PerKeySignResult> {
        val masterKey = state.getMasterPrivateKey() ?: return Either.Left(emptyList())
        val accountKey = state.getAccountPrivateKey() ?: return Either.Left(emptyList())
        val scriptType = DerivationPaths.getScriptType(state.derivationPath)
        val isTestnet = DerivationPaths.isTestnet(state.derivationPath)
        val accountPath = KeyPath(state.derivationPath)

        return bitcoinService.signPsbt(psbt, masterKey, accountKey, scriptType, isTestnet, accountPath)
            .map { it.toPerKeyResult() }
    }

    private fun com.gorunjinian.metrovault.data.model.SigningResult.toPerKeyResult() = PerKeySignResult(
        signedPsbt = signedPsbt,
        alternativePathsUsed = alternativePathsUsed,
        usedAddressLookupFallback = usedAddressLookupFallback,
        addressLookupInputIndices = addressLookupInputIndices,
        refusals = refusals,
    )

    /**
     * Sign by deriving keys directly from stored seed.
     * Uses session seed if available (for passphrase wallets), otherwise stored seed.
     */
    private fun signWithDirectDerivation(
        psbt: String,
        key: WalletKeys,
        config: MultisigConfig,
        sessionSeed: String?
    ): Either<List<InputSigningRefusal>, PerKeySignResult> {
        AppLog.d(TAG) { "Direct derivation signing for local key" }

        val cosigner = config.cosigners.find {
            it.fingerprint.equals(key.fingerprint, ignoreCase = true)
        }
        if (cosigner == null) {
            AppLog.e(TAG) { "No cosigner found matching local key fingerprint" }
            AppLog.d(TAG) { "Available cosigners: ${config.cosigners.size}" }
            return Either.Left(emptyList())
        }

        // Ensure m/ prefix on derivation path
        val rawPath = cosigner.derivationPath
        val derivationPath = if (rawPath.startsWith("m/")) rawPath else "m/$rawPath"
        AppLog.d(TAG) { "Resolved derivation path for signing" }

        // Use session seed if available, otherwise use stored seed
        val seedToUse = sessionSeed ?: key.bip39Seed

        val walletResult = bitcoinService.createWalletFromSeed(seedToUse, derivationPath)
        if (walletResult == null) {
            AppLog.e(TAG) { "Failed to derive wallet from local key" }
            return Either.Left(emptyList())
        }

        val scriptType = DerivationPaths.getScriptType(derivationPath)
        val isTestnet = DerivationPaths.isTestnet(derivationPath)
        val accountPath = KeyPath(derivationPath)

        return bitcoinService.signPsbt(
            psbt,
            walletResult.masterPrivateKey,
            walletResult.accountPrivateKey,
            scriptType,
            isTestnet,
            accountPath,
        ).map { it.toPerKeyResult() }
    }
}
