package com.gorunjinian.metrovault.domain.manager

import android.util.Log
import com.gorunjinian.metrovault.core.storage.SecureStorage
import com.gorunjinian.metrovault.data.model.DerivationPaths
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.WalletKey
import com.gorunjinian.metrovault.data.model.WalletMetadata
import com.gorunjinian.metrovault.data.model.WalletState
import com.gorunjinian.metrovault.domain.service.bitcoin.BitcoinService

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
         */
        data class Success(
            val signedPsbt: String,
            val alternativePathsUsed: List<String> = emptyList()
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
        val signingResult = bitcoinService.signPsbt(
            psbtString,
            masterPrivateKey,
            accountPrivateKey,
            scriptType,
            isTestnet
        )

        return if (signingResult != null) {
            SigningResult.Success(signingResult.signedPsbt, signingResult.alternativePathsUsed)
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
        val failedKeys = mutableListOf<String>()

        for (keyId in keyIds) {
            Log.d(TAG, "Processing keyId: $keyId")
            val key = secureStorage.loadWalletKey(keyId, isDecoyMode)
            if (key == null) {
                Log.e(TAG, "Failed to load WalletKey for keyId: $keyId")
                failedKeys.add(keyId)
                continue
            }
            Log.d(TAG, "Loaded key: fingerprint=${key.fingerprint}, seedLen=${key.bip39Seed.length}")

            // Try loaded wallet state first
            val matchingState = findMatchingWalletState(keyId, walletStates, isDecoyMode)

            val result = if (matchingState != null) {
                signWithLoadedState(signedPsbt, matchingState)
            } else {
                signWithDirectDerivation(signedPsbt, key, config, getSessionKeySeed(keyId))
            }

            if (result != null) {
                signedPsbt = result.first
                allAlternativePathsUsed.addAll(result.second)
                signedCount++
                Log.d(TAG, "Successfully signed portion of PSBT with key: $keyId")
            }
        }

        return if (signedCount > 0) {
            Log.d(TAG, "Multisig signing complete: $signedCount/${keyIds.size} keys signed")
            SigningResult.Success(signedPsbt, allAlternativePathsUsed)
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
     * Sign using an already-loaded wallet state.
     */
    private fun signWithLoadedState(
        psbt: String,
        state: WalletState
    ): Pair<String, List<String>>? {
        val masterKey = state.getMasterPrivateKey() ?: return null
        val accountKey = state.getAccountPrivateKey() ?: return null
        val scriptType = DerivationPaths.getScriptType(state.derivationPath)
        val isTestnet = DerivationPaths.isTestnet(state.derivationPath)

        val result = bitcoinService.signPsbt(psbt, masterKey, accountKey, scriptType, isTestnet)
        return result?.let { it.signedPsbt to it.alternativePathsUsed }
    }

    /**
     * Sign by deriving keys directly from stored seed.
     * Uses session seed if available (for passphrase wallets), otherwise stored seed.
     */
    private fun signWithDirectDerivation(
        psbt: String,
        key: WalletKey,
        config: MultisigConfig,
        sessionSeed: String?
    ): Pair<String, List<String>>? {
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

        val result = bitcoinService.signPsbt(
            psbt,
            walletResult.masterPrivateKey,
            walletResult.accountPrivateKey,
            scriptType,
            isTestnet
        )
        return result?.let { it.signedPsbt to it.alternativePathsUsed }
    }
}
