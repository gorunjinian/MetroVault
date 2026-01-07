package com.gorunjinian.metrovault.domain.service.psbt

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.SigningResult
import com.gorunjinian.metrovault.domain.service.bitcoin.AddressService
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils
import com.gorunjinian.metrovault.domain.service.util.WalletConstants

/**
 * Handles PSBT signing operations.
 */
internal object PsbtSigner {
    private const val TAG = "PsbtSigner"

    // Standard derivation path purposes to try when fingerprint matches but pubkey doesn't
    // This handles cases where coordinator wallets (e.g., Sparrow) convert paths
    private val STANDARD_SINGLE_SIG_PURPOSES = listOf(84, 44, 49, 86) // P2WPKH, P2PKH, P2SH-P2WPKH, P2TR
    private val BIP48_SCRIPT_TYPES = listOf(2, 1)  // P2WSH (2'), P2SH-P2WSH (1')

    private val addressService = AddressService()

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
     * @return SigningResult with signed PSBT and info about alternative paths used, or null on failure
     */
    @Suppress("UNUSED_PARAMETER")
    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): SigningResult? {
        return try {
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)

            // Try parsing, with fallback to stripped xpubs
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> {
                    // Retry with stripped global xpubs
                    val strippedBytes = PsbtUtils.stripGlobalXpubs(psbtBytes) ?: return null
                    when (val retryResult = Psbt.read(strippedBytes)) {
                        is Either.Right -> retryResult.value
                        is Either.Left -> return null
                    }
                }
            }

            // Compute wallet fingerprint for BIP-174 matching
            val walletFingerprint = BitcoinUtils.computeFingerprintLong(masterPrivateKey.publicKey)

            // Build address lookup for fallback (lazy - only used if BIP-174 fails)
            val addressToKeyInfo by lazy {
                buildAddressLookup(accountPrivateKey, scriptType)
            }

            var signedPsbt = psbt
            var signedCount = 0
            val alternativePathsUsed = mutableListOf<String>()  // Track when alternative paths are used

            Log.d(TAG, "Signing PSBT with ${psbt.inputs.size} inputs, wallet fingerprint: ${walletFingerprint.toString(16)}")

            psbt.inputs.forEachIndexed { index, input ->
                Log.d(TAG, "Processing input $index, derivationPaths: ${input.derivationPaths.size}, taprootPaths: ${input.taprootDerivationPaths.size}")
                // Try BIP-174 derivation path first (with path-agnostic fallback)
                val signResult = trySignWithDerivationPath(
                    signedPsbt, index, input, masterPrivateKey, walletFingerprint, isTestnet
                )
                if (signResult != null) {
                    signedPsbt = signResult.first
                    signedCount++
                    signResult.second?.let { altPath ->
                        alternativePathsUsed.add(altPath)
                        Log.d(TAG, "Input $index signed via alternative path: $altPath")
                    } ?: Log.d(TAG, "Input $index signed via BIP-174")
                } else {
                    Log.d(TAG, "Input $index BIP-174 failed, trying address lookup")
                    // Fallback to address scanning
                    val result = signInputWithAddressLookup(signedPsbt, index, input, accountPrivateKey, addressToKeyInfo)
                    if (result != null) {
                        signedPsbt = result
                        signedCount++
                        Log.d(TAG, "Input $index signed via address lookup")
                    } else {
                        Log.d(TAG, "Input $index: no signature possible")
                    }
                }
            }

            Log.d(TAG, "Signed $signedCount of ${psbt.inputs.size} inputs")
            if (signedCount == 0) {
                return null
            }

            val signedBytes = Psbt.write(signedPsbt).toByteArray()
            val signedBase64 = android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)

            SigningResult(
                signedPsbt = signedBase64,
                usedAlternativePath = alternativePathsUsed.isNotEmpty(),
                alternativePathsUsed = alternativePathsUsed
            )
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PSBT signing: ${e.message}", e)
            null
        }
    }

    /**
     * Attempts to sign an input using BIP-174 derivation path metadata.
     * If fingerprint matches but derived pubkey doesn't match, tries alternative standard paths.
     *
     * @return Pair of (signed PSBT, alternative path used) or null if signing failed.
     *         The path string is non-null only when an alternative path was used for signing.
     */
    private fun trySignWithDerivationPath(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        walletFingerprint: Long,
        isTestnet: Boolean
    ): Pair<Psbt, String?>? {
        return try {
            // Check regular derivation paths (for non-taproot)
            Log.d(TAG, "trySignWithDerivationPath: input $inputIndex has ${input.derivationPaths.size} derivation paths")
            for ((publicKey, keyPathWithMaster) in input.derivationPaths) {
                Log.d(TAG, "  Path fingerprint: ${keyPathWithMaster.masterKeyFingerprint.toString(16)}, wallet: ${walletFingerprint.toString(16)}, keyPath: ${keyPathWithMaster.keyPath}")
                if (keyPathWithMaster.masterKeyFingerprint == walletFingerprint) {
                    Log.d(TAG, "  Fingerprint match! Deriving key...")
                    Log.d(TAG, "  KeyPath type: ${keyPathWithMaster.keyPath.javaClass.simpleName}")
                    Log.d(TAG, "  KeyPath path list: ${keyPathWithMaster.keyPath.path}")

                    // Derive key using the full path from master
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(keyPathWithMaster.keyPath)
                        .privateKey

                    val derivedPubKey = signingPrivateKey.publicKey()

                    // Verify derived public key matches
                    if (derivedPubKey == publicKey) {
                        Log.d(TAG, "  Public key verified, signing...")
                        val signed = signInput(psbt, inputIndex, input, signingPrivateKey, publicKey)
                        return signed?.let { Pair(it, null) }  // null = no alternative path
                    }

                    // Fingerprint matches but pubkey doesn't - try alternative paths
                    Log.w(TAG, "  Derived public key doesn't match PSBT key, trying alternative paths...")
                    val altResult = tryAlternativePaths(
                        masterPrivateKey, publicKey, keyPathWithMaster.keyPath, isTestnet
                    )
                    if (altResult != null) {
                        val (altSigningKey, altPath) = altResult
                        Log.d(TAG, "  Found matching key at alternative path: $altPath")
                        val signed = signInput(psbt, inputIndex, input, altSigningKey, publicKey)
                        return signed?.let { Pair(it, altPath) }
                    }
                    Log.w(TAG, "  No alternative path matched, skipping")
                }
            }

            // Check taproot derivation paths
            for ((xOnlyPublicKey, taprootPath) in input.taprootDerivationPaths) {
                if (taprootPath.masterKeyFingerprint == walletFingerprint) {

                    // Derive key using the full path from master
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(taprootPath.keyPath)
                        .privateKey

                    // Verify derived x-only public key matches
                    val derivedXOnly = XonlyPublicKey(signingPrivateKey.publicKey())
                    if (derivedXOnly == xOnlyPublicKey) {
                        val signed = signInput(psbt, inputIndex, input, signingPrivateKey, signingPrivateKey.publicKey())
                        return signed?.let { Pair(it, null) }
                    }

                    // For Taproot, try alternative paths as well
                    Log.w(TAG, "  Taproot pubkey mismatch, trying alternative paths...")
                    val altResult = tryAlternativePaths(
                        masterPrivateKey, signingPrivateKey.publicKey(), taprootPath.keyPath, isTestnet
                    )
                    if (altResult != null) {
                        val (altSigningKey, altPath) = altResult
                        val altXOnly = XonlyPublicKey(altSigningKey.publicKey())
                        if (altXOnly == xOnlyPublicKey) {
                            Log.d(TAG, "  Found matching Taproot key at alternative path: $altPath")
                            val signed = signInput(psbt, inputIndex, input, altSigningKey, altSigningKey.publicKey())
                            return signed?.let { Pair(it, altPath) }
                        }
                    }
                }
            }

            null
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Tries alternative standard derivation paths to find a matching public key.
     * This handles cases where coordinator wallets convert paths (e.g., m/84' -> m/48' for multisig).
     * 
     * Also tries multiple account numbers since coordinators may normalize account to 0
     * even when the original key was from a different account.
     *
     * @param masterPrivateKey The master private key to derive from
     * @param expectedPublicKey The public key we're looking for
     * @param originalPath The original path from the PSBT that didn't match
     * @param isTestnet Whether this is a testnet wallet
     * @return Pair of (signing private key, path string) if found, null otherwise
     */
    private fun tryAlternativePaths(
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        expectedPublicKey: PublicKey,
        originalPath: KeyPath,
        isTestnet: Boolean
    ): Pair<PrivateKey, String>? {
        // Extract child path from original path
        // Typical format: m/purpose'/coin'/account'/change/index
        // or for BIP48: m/48'/coin'/account'/scriptType'/change/index
        val pathList = originalPath.path
        if (pathList.size < 3) return null  // Need at least purpose/coin/account

        val coinType = if (isTestnet) DeterministicWallet.hardened(1) else DeterministicWallet.hardened(0)

        // Get the child path (change/index portion after account level)
        // For m/48'/0'/0'/2'/0/5, child path would be [0, 5]
        val childStartIndex = when {
            pathList.size >= 5 && DeterministicWallet.isHardened(pathList.getOrNull(3) ?: 0L) -> 4  // BIP48 with script type
            pathList.size >= 4 -> 3  // Standard paths
            else -> return null
        }
        val childPath = if (childStartIndex < pathList.size) pathList.subList(childStartIndex, pathList.size) else emptyList()

        // Extract account from PSBT path as a starting point
        val originalAccount = pathList.getOrNull(2) ?: return null
        val psbtAccountNum = if (DeterministicWallet.isHardened(originalAccount)) {
            originalAccount - DeterministicWallet.hardenedKeyIndex
        } else {
            originalAccount
        }

        Log.d(TAG, "  tryAlternativePaths: original=$originalPath, psbtAccount=$psbtAccountNum, childPath=$childPath")

        // Account numbers to try - start with PSBT account, then try common accounts 0-9
        val accountsToTry = (listOf(psbtAccountNum) + (0L..9L)).distinct()

        // Try single-sig paths first (most common case: coordinator converted m/84 to m/48)
        for (accountNum in accountsToTry) {
            for (purpose in STANDARD_SINGLE_SIG_PURPOSES) {
                try {
                    // Build path: m/purpose'/coin'/account'/child...
                    val altPath = KeyPath(listOf(
                        DeterministicWallet.hardened(purpose.toLong()),
                        coinType,
                        DeterministicWallet.hardened(accountNum)
                    ) + childPath)

                    val derivedKey = masterPrivateKey.derivePrivateKey(altPath)
                    if (derivedKey.publicKey == expectedPublicKey) {
                        Log.d(TAG, "  Found match at alternative path: $altPath")
                        return Pair(derivedKey.privateKey, altPath.toString())
                    }
                } catch (_: Exception) {
                    // Silent - just try next
                }
            }
        }

        // Try BIP48 multisig paths
        for (accountNum in accountsToTry) {
            for (scriptType in BIP48_SCRIPT_TYPES) {
                try {
                    // Build path: m/48'/coin'/account'/scriptType'/child...
                    val altPath = KeyPath(listOf(
                        DeterministicWallet.hardened(48),
                        coinType,
                        DeterministicWallet.hardened(accountNum),
                        DeterministicWallet.hardened(scriptType.toLong())
                    ) + childPath)

                    val derivedKey = masterPrivateKey.derivePrivateKey(altPath)
                    if (derivedKey.publicKey == expectedPublicKey) {
                        Log.d(TAG, "  Found match at alternative path: $altPath")
                        return Pair(derivedKey.privateKey, altPath.toString())
                    }
                } catch (_: Exception) {
                    // Silent - just try next
                }
            }
        }

        Log.w(TAG, "  No alternative path matched after trying ${accountsToTry.size} accounts")
        return null
    }

    /**
     * Signs an input using address lookup fallback.
     */
    private fun signInputWithAddressLookup(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        addressLookup: Map<ByteVector, AddressKeyInfo>
    ): Psbt? {
        return try {
            val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: return null

            val keyInfo = addressLookup[scriptPubKey] ?: return null

            val signingPrivateKey = accountPrivateKey
                .derivePrivateKey(keyInfo.changeIndex)
                .derivePrivateKey(keyInfo.addressIndex)
                .privateKey

            signInput(psbt, inputIndex, input, signingPrivateKey, keyInfo.publicKey)
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Core signing logic shared between BIP-174 and address lookup methods.
     */
    private fun signInput(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        signingPrivateKey: PrivateKey,
        signingPublicKey: PublicKey
    ): Psbt? {
        // For P2WPKH inputs, we need to temporarily add the witnessScript for signing
        // but must remove it afterwards per BIP-174 (P2WPKH doesn't use witnessScript field)
        var psbtToSign = psbt
        var addedWitnessScriptForP2wpkh = false

        if (input is Input.WitnessInput.PartiallySignedWitnessInput && input.witnessScript == null) {
            // Check if this is actually P2WPKH
            val pubkeyScript = runCatching {
                Script.parse(input.txOut.publicKeyScript)
            }.getOrNull()

            if (pubkeyScript != null && Script.isPay2wpkh(pubkeyScript)) {
                val witnessScript = Script.pay2pkh(signingPublicKey)

                val updatedInput = input.copy(witnessScript = witnessScript)
                val updatedInputs = psbt.inputs.toMutableList()
                updatedInputs[inputIndex] = updatedInput
                psbtToSign = psbt.copy(inputs = updatedInputs)
                addedWitnessScriptForP2wpkh = true
            }
        }

        return when (val signResult = psbtToSign.sign(signingPrivateKey, inputIndex)) {
            is Either.Right -> {
                var signedPsbt = signResult.value.psbt

                // Remove the temporary witnessScript for P2WPKH - it should NOT be in the final PSBT
                // as it causes some finalizers (like BlueWallet) to incorrectly add it to the witness
                if (addedWitnessScriptForP2wpkh) {
                    signedPsbt = removeWitnessScriptFromInput(signedPsbt, inputIndex)
                }

                signedPsbt
            }
            is Either.Left -> null
        }
    }

    /**
     * Removes the witnessScript field from a P2WPKH input after signing.
     */
    private fun removeWitnessScriptFromInput(psbt: Psbt, inputIndex: Int): Psbt {
        val input = psbt.inputs[inputIndex]
        if (input is Input.WitnessInput.PartiallySignedWitnessInput && input.witnessScript != null) {
            val cleanedInput = input.copy(witnessScript = null)
            val updatedInputs = psbt.inputs.toMutableList()
            updatedInputs[inputIndex] = cleanedInput
            return psbt.copy(inputs = updatedInputs)
        }
        return psbt
    }

    /**
     * Builds an address lookup map for fallback signing.
     */
    private fun buildAddressLookup(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType
    ): Map<ByteVector, AddressKeyInfo> {
        val lookup = mutableMapOf<ByteVector, AddressKeyInfo>()
        val accountPublicKey = accountPrivateKey.extendedPublicKey

        for (isChange in listOf(false, true)) {
            val changeIndex = if (isChange) 1L else 0L

            for (i in 0 until WalletConstants.SINGLE_SIG_ADDRESS_GAP) {
                try {
                    val addressKey = accountPublicKey
                        .derivePublicKey(changeIndex)
                        .derivePublicKey(i.toLong())

                    val publicKey = addressKey.publicKey
                    val scriptPubKey = addressService.createScriptPubKey(publicKey, scriptType)

                    if (scriptPubKey != null) {
                        lookup[scriptPubKey] = AddressKeyInfo(changeIndex, i.toLong(), publicKey)
                    }
                } catch (_: Exception) {
                    // Skip addresses that fail to derive
                }
            }
        }

        return lookup
    }
}
