package com.gorunjinian.metrovault.domain.service.bitcoin

import android.util.Log
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.WalletKeys
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath

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
        ) : SigningResult()

        /**
         * Signing failed with a specific error.
         * @property error The error type
         * @property message Human-readable error message
         */
        data class Failure(
            val error: SigningError,
            val message: String
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
        KEY_DERIVATION_FAILED
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
        val signingResult = bitcoinService.signPsbt(
            psbtString,
            masterPrivateKey,
            accountPrivateKey,
            scriptType,
            isTestnet,
            accountPath,
        )

        return if (signingResult != null) {
            SigningResult.Success(
                signedPsbt = signingResult.signedPsbt,
                alternativePathsUsed = signingResult.alternativePathsUsed,
                usedAddressLookupFallback = signingResult.usedAddressLookupFallback,
                addressLookupInputIndices = signingResult.addressLookupInputIndices,
            )
        } else {
            SigningResult.Failure(
                SigningError.SIGNING_FAILED,
                "Failed to sign PSBT. The transaction may not contain inputs for this wallet."
            )
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
        Log.d(TAG, "Multisig signing: keyIds=$keyIds")

        if (keyIds.isEmpty()) {
            Log.e(TAG, "No keyIds found for multisig wallet")
            return SigningResult.Failure(
                SigningError.NO_LOCAL_KEYS,
                "This multisig wallet has no local signing keys. Import a key that matches one of the cosigner fingerprints."
            )
        }

        val config = metadata.multisigConfig
        if (config == null) {
            Log.e(TAG, "No multisigConfig found")
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

        for (keyId in keyIds) {
            Log.d(TAG, "Processing keyId: $keyId")
            val key = secureStorage.loadWalletKey(keyId, isDecoyMode)
            if (key == null) {
                Log.e(TAG, "Failed to load WalletKey for keyId: $keyId")
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

            if (result != null) {
                signedPsbt = result.signedPsbt
                allAlternativePathsUsed.addAll(result.alternativePathsUsed)
                if (result.usedAddressLookupFallback) {
                    anyUsedAddressLookupFallback = true
                    allAddressLookupInputIndices.addAll(result.addressLookupInputIndices)
                }
                signedCount++
                Log.d(TAG, "Successfully signed portion of PSBT with key: $keyId")
            }
        }

        return if (signedCount > 0) {
            Log.d(TAG, "Multisig signing complete: $signedCount/${keyIds.size} keys signed")
            SigningResult.Success(
                signedPsbt = signedPsbt,
                alternativePathsUsed = allAlternativePathsUsed,
                usedAddressLookupFallback = anyUsedAddressLookupFallback,
                addressLookupInputIndices = allAddressLookupInputIndices.toList(),
            )
        } else {
            val errorMsg = if (failedKeys.isNotEmpty()) {
                "Failed to load ${failedKeys.size} key(s). Ensure the wallet is properly loaded."
            } else {
                "No inputs could be signed. The PSBT may not contain inputs for your keys."
            }
            SigningResult.Failure(SigningError.SIGNING_FAILED, errorMsg)
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
    )

    /**
     * Sign using an already-loaded wallet state.
     */
    private fun signWithLoadedState(
        psbt: String,
        state: WalletState
    ): PerKeySignResult? {
        val masterKey = state.getMasterPrivateKey() ?: return null
        val accountKey = state.getAccountPrivateKey() ?: return null
        val scriptType = DerivationPaths.getScriptType(state.derivationPath)
        val isTestnet = DerivationPaths.isTestnet(state.derivationPath)
        val accountPath = KeyPath(state.derivationPath)

        val result = bitcoinService.signPsbt(psbt, masterKey, accountKey, scriptType, isTestnet, accountPath)
        return result?.let {
            PerKeySignResult(
                signedPsbt = it.signedPsbt,
                alternativePathsUsed = it.alternativePathsUsed,
                usedAddressLookupFallback = it.usedAddressLookupFallback,
                addressLookupInputIndices = it.addressLookupInputIndices,
            )
        }
    }

    /**
     * Sign by deriving keys directly from stored seed.
     * Uses session seed if available (for passphrase wallets), otherwise stored seed.
     */
    private fun signWithDirectDerivation(
        psbt: String,
        key: WalletKeys,
        config: MultisigConfig,
        sessionSeed: String?
    ): PerKeySignResult? {
        Log.d(TAG, "Direct derivation signing for key: ${key.fingerprint}")

        val cosigner = config.cosigners.find {
            it.fingerprint.equals(key.fingerprint, ignoreCase = true)
        }
        if (cosigner == null) {
            Log.e(TAG, "No cosigner found matching fingerprint: ${key.fingerprint}")
            Log.d(TAG, "Available cosigners: ${config.cosigners.map { it.fingerprint }}")
            return null
        }

        // Ensure m/ prefix on derivation path
        val rawPath = cosigner.derivationPath
        val derivationPath = if (rawPath.startsWith("m/")) rawPath else "m/$rawPath"
        Log.d(TAG, "Using derivation path: $derivationPath (raw: $rawPath)")

        // Use session seed if available, otherwise use stored seed
        val seedToUse = sessionSeed ?: key.bip39Seed

        val walletResult = bitcoinService.createWalletFromSeed(seedToUse, derivationPath)
        if (walletResult == null) {
            Log.e(TAG, "Failed to derive wallet from key: ${key.fingerprint}")
            return null
        }

        val scriptType = DerivationPaths.getScriptType(derivationPath)
        val isTestnet = DerivationPaths.isTestnet(derivationPath)
        val accountPath = KeyPath(derivationPath)

        val result = bitcoinService.signPsbt(
            psbt,
            walletResult.masterPrivateKey,
            walletResult.accountPrivateKey,
            scriptType,
            isTestnet,
            accountPath,
        )
        return result?.let {
            PerKeySignResult(
                signedPsbt = it.signedPsbt,
                alternativePathsUsed = it.alternativePathsUsed,
                usedAddressLookupFallback = it.usedAddressLookupFallback,
                addressLookupInputIndices = it.addressLookupInputIndices,
            )
        }
    }
}