package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.InputSigningRefusal
import com.gorunjinian.metrovault.data.model.SigningResult
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils

/**
 * Handles PSBT signing operations.
 */
internal object PsbtSigner {
    private const val TAG = "PsbtSigner"

    /**
     * Signs a PSBT using BIP-174 compliant approach with path-agnostic fallback:
     * 1. First tries to use derivation path metadata from the PSBT (fast, reliable)
     * 2. If fingerprint matches but pubkey doesn't, tries standard alternative paths
     * 3. Falls back to address scanning if no metadata available
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @param masterPrivateKey Master private key for full path derivation (BIP-174)
     * @param accountPrivateKey Account-level private key for fallback address scanning
     * @param scriptType Script type for address generation in fallback
     * @param isTestnet Whether this is a testnet wallet
     * @return the signed PSBT and diagnostics, or the per-input refusals that prevented signing
     *   (empty when the PSBT could not be parsed or nothing in it was ours)
     */
    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false,
        accountPath: KeyPath,
    ): Either<List<InputSigningRefusal>, SigningResult> {
        return try {
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)

            // Try parsing, with fallback to stripped xpubs
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> {
                    // Retry with stripped global xpubs
                    val strippedBytes = PsbtUtils.stripGlobalXpubs(psbtBytes)
                        ?: return Either.Left(emptyList())
                    when (val retryResult = Psbt.read(strippedBytes)) {
                        is Either.Right -> retryResult.value
                        is Either.Left -> return Either.Left(emptyList())
                    }
                }
            }

            signParsedPsbt(psbt, masterPrivateKey, accountPrivateKey, scriptType, isTestnet, accountPath).map { signed ->
                val signedBytes = Psbt.write(signed.psbt).toByteArray()
                SigningResult(
                    signedPsbt = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP),
                    usedAlternativePath = signed.alternativePathsUsed.isNotEmpty(),
                    alternativePathsUsed = signed.alternativePathsUsed,
                    usedAddressLookupFallback = signed.addressLookupInputIndices.isNotEmpty(),
                    addressLookupInputIndices = signed.addressLookupInputIndices,
                    // Carried on the success path too: a partially-signed PSBT otherwise looks
                    // like a clean success while silently omitting an input.
                    refusals = signed.refusals,
                )
            }
        } catch (e: Exception) {
            // Keep internal exception text out of the UI; the detail is in the debug log.
            AppLog.e(TAG, e) { "Exception during PSBT signing: ${e.message}" }
            Either.Left(emptyList())
        }
    }

    /** What [signParsedPsbt] produces: the signed psbt plus the diagnostics [SigningResult] surfaces. */
    internal data class ParsedSigningResult(
        val psbt: Psbt,
        val alternativePathsUsed: List<String>,
        val addressLookupInputIndices: List<Int>,
        val refusals: List<InputSigningRefusal>,
    )

    /**
     * The per-input signing loop over an already-parsed PSBT. Kept separate from [signPsbt] so
     * it can be exercised directly: `android.util.Base64` is stubbed out under unit tests.
     *
     * @return the signed psbt and diagnostics, or — when no input was signed — the refusals
     *   recorded for inputs that matched this wallet.
     */
    internal fun signParsedPsbt(
        psbt: Psbt,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean,
        accountPath: KeyPath,
    ): Either<List<InputSigningRefusal>, ParsedSigningResult> {
        // Compute wallet fingerprint for BIP-174 matching
        val walletFingerprint = BitcoinUtils.computeFingerprintLong(masterPrivateKey.publicKey)

        // Build address lookup for fallback (lazy - only used if BIP-174 fails)
        val addressToKeyInfo by lazy {
            PsbtKeyResolver.buildAddressLookup(accountPrivateKey, scriptType)
        }

        var signedPsbt = psbt
        var signedCount = 0
        val alternativePathsUsed = mutableListOf<String>()  // Track when alternative paths are used (Stage 2)
        val addressLookupInputIndices = mutableListOf<Int>()  // Track when Stage 3 fallback was used
        // Inputs we matched to this wallet but then declined to sign. Recorded only when key
        // resolution succeeded, so a non-empty list means "yours, but refused" — never "not yours".
        val refusals = mutableListOf<InputSigningRefusal>()

        AppLog.d(TAG) { "Signing PSBT with ${psbt.inputs.size} inputs" }

        psbt.inputs.forEachIndexed { index, input ->
            // An already-finalized input carries its final scriptSig/witness and needs nothing from
            // us. Without this skip, Stage 3 would still match one of ours by scriptPubKey and
            // `Psbt.sign` would decline it as "already finalized" — which the UI would then report
            // as a partial signature on a transaction that is in fact complete.
            if (input.isFinalized) {
                AppLog.d(TAG) { "Input $index already finalized, skipping" }
                return@forEachIndexed
            }
            AppLog.d(TAG) { "Processing input $index, derivationPaths: ${input.derivationPaths.size}, taprootPaths: ${input.taprootDerivationPaths.size}" }
            // Try BIP-174 derivation path first (with path-agnostic fallback)
            val signResult = trySignWithDerivationPath(
                signedPsbt, index, input, masterPrivateKey, walletFingerprint, isTestnet
            )
            if (signResult is SignAttempt.Signed) {
                signedPsbt = signResult.psbt
                signedCount++
                if (signResult.alternativePath != null) {
                    AppLog.d(TAG) { "Input $index signed via alternative path" }
                    alternativePathsUsed.add(signResult.alternativePath)
                } else {
                    AppLog.d(TAG) { "Input $index signed via BIP-174" }
                }
            } else {
                // Stage 1 resolves by the PSBT's declared derivation metadata; Stage 3 resolves
                // by scriptPubKey against our own addresses. Those can land on *different* keys,
                // so a Stage 1 refusal must still fall through — a PSBT that declares the wrong
                // path under our fingerprint is exactly what Stage 3 exists to rescue.
                AppLog.d(TAG) { "Input $index not signed via BIP-174, trying address lookup" }
                val fallback = signInputWithAddressLookup(
                    signedPsbt, index, input, accountPrivateKey, addressToKeyInfo,
                    accountPath, walletFingerprint,
                )
                when {
                    fallback is SignAttempt.Signed -> {
                        signedPsbt = fallback.psbt
                        signedCount++
                        addressLookupInputIndices.add(index)
                        AppLog.w(TAG) {
                            "Input $index signed via address lookup (Stage 3 fallback) — " +
                                "PSBT did not declare this input via PSBT_IN_BIP32_DERIVATION with a matching fingerprint"
                        }
                    }
                    // Neither stage produced a signature. Report a reason only if some stage
                    // actually resolved a key and then declined — prefer Stage 3's, which was
                    // reached via the scriptPubKey and so describes the real output being spent.
                    else -> {
                        val failure = (fallback as? SignAttempt.Refused)?.failure
                            ?: (signResult as? SignAttempt.Refused)?.failure
                        if (failure != null) {
                            AppLog.w(TAG) { "Input $index refused: $failure" }
                            refusals.add(InputSigningRefusal.from(index, failure))
                        } else {
                            AppLog.d(TAG) { "Input $index: no signature possible" }
                        }
                    }
                }
            }
        }

        AppLog.d(TAG) { "Signed $signedCount of ${psbt.inputs.size} inputs" }
        if (signedCount == 0) {
            return Either.Left(refusals)
        }

        return Either.Right(
            ParsedSigningResult(
                psbt = signedPsbt,
                alternativePathsUsed = alternativePathsUsed,
                addressLookupInputIndices = addressLookupInputIndices,
                refusals = refusals,
            )
        )
    }

    /** True for the three finalized [Input] subtypes: nothing further can or should be signed. */
    private val Input.isFinalized: Boolean
        get() = this is Input.FinalizedInputWithoutUtxo ||
            this is Input.WitnessInput.FinalizedWitnessInput ||
            this is Input.NonWitnessInput.FinalizedNonWitnessInput

    /**
     * Attempts to sign an input using BIP-174 derivation path metadata.
     * If fingerprint matches but derived pubkey doesn't match, tries alternative standard paths.
     *
     * @return [SignAttempt.NotOurs] when no key could be resolved, [SignAttempt.Refused] when a key
     *         was resolved but signing was declined, otherwise the signed psbt. The alternative path
     *         is non-null only when one was used.
     */
    private fun trySignWithDerivationPath(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        walletFingerprint: Long,
        isTestnet: Boolean
    ): SignAttempt {
        val resolved = PsbtKeyResolver.resolveFromDerivationPaths(
            input, masterPrivateKey, walletFingerprint, isTestnet
        ) ?: return SignAttempt.NotOurs
        if (resolved.alternativePath != null) {
            AppLog.d(TAG) { "  Input $inputIndex resolved via alternative path" }
        }
        return when (val signed = psbt.sign(resolved.privateKey, inputIndex)) {
            is Either.Right -> SignAttempt.Signed(signed.value.psbt, resolved.alternativePath)
            is Either.Left -> SignAttempt.Refused(signed.value)
        }
    }

    /** Outcome of trying to sign one input. */
    private sealed class SignAttempt {
        /** No key for this input could be resolved — the input is not ours. */
        data object NotOurs : SignAttempt()

        /** A key was resolved, but signing was declined. This is what the user needs told. */
        data class Refused(val failure: UpdateFailure) : SignAttempt()

        data class Signed(val psbt: Psbt, val alternativePath: String? = null) : SignAttempt()
    }

    /**
     * Signs an input using address lookup fallback (Stage 3).
     *
     * When the incoming PSBT does not declare an input's key via
     * PSBT_IN_BIP32_DERIVATION (or the fingerprint doesn't match), this
     * method falls back to deriving addresses from the wallet's own seed
     * and matching by scriptPubKey. Before signing, it writes the full
     * (fingerprint, derivation path) entry back into the input's
     * derivationPaths map so the resulting signed PSBT carries correct
     * provenance for downstream tools (BIP-174 §4.1.1). Without this,
     * coordinators and multisig aggregators cannot verify which key
     * produced the signature.
     *
     * @param accountPath The wallet's account-level derivation path
     *   (e.g. m/84'/0'/0'), used to build the full per-input path.
     * @param walletFingerprint The wallet's computed master fingerprint,
     *   used in the written-back KeyPathWithMaster entry.
     */
    private fun signInputWithAddressLookup(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        addressLookup: Map<ByteVector, AddressKeyInfo>,
        accountPath: KeyPath,
        walletFingerprint: Long,
    ): SignAttempt {
        return try {
            val (resolved, keyInfo) = PsbtKeyResolver.resolveFromAddressLookup(
                input, accountPrivateKey, addressLookup
            ) ?: return SignAttempt.NotOurs
            val signingPrivateKey = resolved.privateKey

            // Build the full derivation path for the signed key so we can
            // write it back into PSBT_IN_BIP32_DERIVATION. The account path
            // is m/84'/0'/0' (or equivalent for the script type); we append
            // the change/address indices from the address-lookup match.
            val fullPath = KeyPath(
                accountPath.path + listOf(keyInfo.changeIndex, keyInfo.addressIndex)
            )

            // Splice the derivation entry into the input BEFORE calling
            // Psbt.sign() so that (a) downstream tools can verify signing
            // provenance and (b) Psbt.sign itself sees a well-formed input.
            val inputWithDerivation = addDerivationToInput(input, keyInfo.publicKey, walletFingerprint, fullPath)
            val psbtWithDerivation = if (inputWithDerivation !== input) {
                val updatedInputs = psbt.inputs.toMutableList()
                updatedInputs[inputIndex] = inputWithDerivation
                psbt.copy(inputs = updatedInputs)
            } else {
                psbt
            }

            when (val signed = psbtWithDerivation.sign(signingPrivateKey, inputIndex)) {
                is Either.Right -> SignAttempt.Signed(signed.value.psbt)
                is Either.Left -> SignAttempt.Refused(signed.value)
            }
        } catch (e: Exception) {
            AppLog.w(TAG, e) { "signInputWithAddressLookup: exception" }
            SignAttempt.NotOurs
        }
    }

    /**
     * Splices a derivation entry into an input so the signed PSBT carries provenance.
     *
     * P2TR inputs get a BIP-371 `PSBT_IN_TAP_BIP32_DERIVATION` entry keyed by the x-only
     * internal key; everything else gets the BIP-174 `PSBT_IN_BIP32_DERIVATION` entry keyed by
     * the compressed pubkey. Writing the plain BIP32 entry on a taproot input would be misleading
     * — coordinators key taproot provenance off the x-only field.
     *
     * Because [Input] is a sealed hierarchy with three un-finalized subtypes
     * (each of which stores its own derivation maps), we need a `when` over
     * the subclasses to call the correct `.copy()`. Finalized inputs cannot
     * accept new derivations and are returned unchanged — [signParsedPsbt]
     * skips them before Stage 3 runs, so the finalized branches are
     * unreachable but kept for totality.
     */
    private fun addDerivationToInput(
        input: Input,
        publicKey: PublicKey,
        walletFingerprint: Long,
        fullPath: KeyPath,
    ): Input {
        val isTaproot = PsbtUtils.getInputScriptPubKey(input)?.let { Script.isPay2tr(it) } == true

        if (isTaproot) {
            val taprootDerivation = TaprootBip32DerivationPath(
                leaves = emptyList(),  // key-path spend: no tapleaf commits to this key
                masterKeyFingerprint = walletFingerprint,
                keyPath = fullPath,
            )
            val internalKey = XonlyPublicKey(publicKey)
            val mergedTaprootPaths =
                input.taprootDerivationPaths + (internalKey to taprootDerivation)
            // Taproot is segwit v1, so a P2TR scriptPubKey always arrives as a witness input; the
            // other un-finalized subtypes don't even carry a taprootDerivationPaths field.
            return when (input) {
                is Input.WitnessInput.PartiallySignedWitnessInput ->
                    input.copy(
                        taprootDerivationPaths = mergedTaprootPaths,
                        // We no longer *require* PSBT_IN_TAP_INTERNAL_KEY to sign, but the signed
                        // PSBT should still carry it so downstream tools have full provenance.
                        taprootInternalKey = input.taprootInternalKey ?: internalKey,
                    )
                else -> {
                    AppLog.w(TAG) { "addDerivationToInput: cannot add taproot derivation to ${input::class.simpleName}" }
                    input
                }
            }
        }

        val mergedPaths = input.derivationPaths + (publicKey to KeyPathWithMaster(walletFingerprint, fullPath))
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput ->
                input.copy(derivationPaths = mergedPaths)
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput ->
                input.copy(derivationPaths = mergedPaths)
            is Input.PartiallySignedInputWithoutUtxo ->
                input.copy(derivationPaths = mergedPaths)
            else -> {
                AppLog.w(TAG) { "addDerivationToInput: cannot add derivation to finalized input ${input::class.simpleName}" }
                input
            }
        }
    }

}
