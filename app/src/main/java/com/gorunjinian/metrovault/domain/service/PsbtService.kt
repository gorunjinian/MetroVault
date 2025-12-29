package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.psbt.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.data.model.ScriptType
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.data.model.PsbtInput
import com.gorunjinian.metrovault.data.model.PsbtOutput
import com.gorunjinian.metrovault.data.model.SigningResult

/**
 * Service responsible for PSBT (Partially Signed Bitcoin Transaction) operations.
 * Handles PSBT parsing, signing, and details extraction.
 */
class PsbtService {

    companion object {
        private const val TAG = "PsbtService"
        
        // Standard derivation path purposes to try when fingerprint matches but pubkey doesn't
        // This handles cases where coordinator wallets (e.g., Sparrow) convert paths
        private val STANDARD_SINGLE_SIG_PURPOSES = listOf(84, 44, 49, 86) // P2WPKH, P2PKH, P2SH-P2WPKH, P2TR
        private val BIP48_SCRIPT_TYPES = listOf(2, 1)  // P2WSH (2'), P2SH-P2WSH (1')
    }

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
                    val strippedBytes = stripGlobalXpubs(psbtBytes) ?: return null
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
     * Checks if a PSBT is fully signed.
     */
    @Suppress("unused") // Public API for future use/testing
    fun isPsbtFullySigned(psbtBase64: String): Boolean {
        return try {
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> return false
            }

            psbt.inputs.all { input ->
                when (input) {
                    is Input.WitnessInput.PartiallySignedWitnessInput ->
                        input.partialSigs.isNotEmpty() || input.taprootKeySignature != null
                    is Input.NonWitnessInput.PartiallySignedNonWitnessInput ->
                        input.partialSigs.isNotEmpty()
                    is Input.WitnessInput.FinalizedWitnessInput -> true
                    is Input.NonWitnessInput.FinalizedNonWitnessInput -> true
                    is Input.FinalizedInputWithoutUtxo -> true
                    is Input.PartiallySignedInputWithoutUtxo -> false
                }
            }
        } catch (_: Exception) {
            Log.e(TAG, "Failed to check PSBT signatures")
            false
        }
    }

    /**
     * Extracts details from a PSBT for display purposes.
     */
    fun getPsbtDetails(psbtBase64: String, isTestnet: Boolean = false): PsbtDetails? {
        return try {
            Log.d(TAG, "getPsbtDetails called, base64 length: ${psbtBase64.length}")
            Log.d(TAG, "Base64 prefix: ${psbtBase64.take(50)}...")
            
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val psbtBytes = try {
                android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
            } catch (e: Exception) {
                Log.e(TAG, "Base64 decode failed: ${e.message}")
                return null
            }
            
            Log.d(TAG, "Decoded PSBT bytes length: ${psbtBytes.size}")
            Log.d(TAG, "First 20 bytes (hex): ${psbtBytes.take(20).joinToString("") { "%02x".format(it) }}")
            
            // Try standard parsing first
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> {
                    Log.d(TAG, "PSBT parsed successfully, inputs: ${psbtResult.value.inputs.size}")
                    psbtResult.value
                }
                is Either.Left -> {
                    Log.w(TAG, "Standard PSBT parse failed: ${psbtResult.value}")
                    // Try parsing with global xpubs stripped (workaround for malformed xpub metadata)
                    val strippedBytes = stripGlobalXpubs(psbtBytes)
                    if (strippedBytes != null) {
                        Log.d(TAG, "Retrying with stripped global xpubs")
                        when (val retryResult = Psbt.read(strippedBytes)) {
                            is Either.Right -> {
                                Log.d(TAG, "PSBT parsed successfully after stripping xpubs")
                                retryResult.value
                            }
                            is Either.Left -> {
                                Log.e(TAG, "PSBT parse still failed after stripping: ${retryResult.value}")
                                return null
                            }
                        }
                    } else {
                        Log.e(TAG, "Failed to strip global xpubs")
                        return null
                    }
                }
            }

            val tx = psbt.global.tx
            
            // Track multisig info across inputs
            var isMultisig = false
            var requiredSignatures = 1
            var totalSigners = 1
            var totalCurrentSignatures = 0
            var allInputsSigned = true

            val inputs = tx.txIn.mapIndexed { index, txIn ->
                val input = psbt.inputs.getOrNull(index)
                val inputValue = getInputValue(input)
                
                // Extract input address from scriptPubKey
                val inputAddress = input?.let { getInputScriptPubKey(it) }?.let { scriptPubKey ->
                    try {
                        val parsedScript = Script.parse(scriptPubKey)
                        when (val result = Bitcoin.addressFromPublicKeyScript(chainHash, parsedScript)) {
                            is Either.Right -> result.value
                            is Either.Left -> null
                        }
                    } catch (_: Exception) {
                        null
                    }
                } ?: "Unknown"
                
                // Analyze input for multisig and signature count
                val (inputIsMultisig, inputM, inputN, sigCount) = analyzeInputSignatures(input)
                
                if (inputIsMultisig) {
                    isMultisig = true
                    requiredSignatures = inputM
                    totalSigners = inputN
                }
                
                totalCurrentSignatures += sigCount
                
                // Check if this input has sufficient signatures
                val inputSigned = if (inputIsMultisig) {
                    sigCount >= inputM
                } else {
                    sigCount >= 1
                }
                if (!inputSigned) allInputsSigned = false

                PsbtInput(
                    address = inputAddress,
                    prevTxHash = txIn.outPoint.txid.toString(),
                    prevTxIndex = txIn.outPoint.index.toInt(),
                    value = inputValue,
                    signatureCount = sigCount,
                    isMultisig = inputIsMultisig
                )
            }

            val outputs = tx.txOut.map { txOut ->
                val address = extractAddressFromOutput(txOut, chainHash)
                PsbtOutput(address = address, value = txOut.amount.toLong())
            }

            val totalInput = inputs.sumOf { it.value }
            val totalOutput = outputs.sumOf { it.value }
            val fee = if (totalInput > 0) totalInput - totalOutput else null
            
            val virtualSize = calculateVirtualSize(psbt, tx)

            PsbtDetails(
                inputs = inputs, 
                outputs = outputs, 
                fee = fee, 
                virtualSize = virtualSize,
                isMultisig = isMultisig,
                requiredSignatures = requiredSignatures,
                totalSigners = totalSigners,
                currentSignatures = totalCurrentSignatures,
                isReadyToBroadcast = allInputsSigned
            )
        } catch (_: Exception) {
            null
        }
    }
    
    /**
     * Analyzes a PSBT input for multisig information and signature count.
     * 
     * @return Tuple of (isMultisig, m, n, currentSignatureCount)
     */
    private fun analyzeInputSignatures(input: Input?): InputSignatureInfo {
        if (input == null) return InputSignatureInfo(false, 1, 1, 0)
        
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> {
                val sigCount = input.partialSigs.size
                
                // Check witness script for multisig pattern
                val witnessScript = input.witnessScript
                if (witnessScript != null) {
                    val (isMs, m, n) = parseMultisigScript(witnessScript)
                    InputSignatureInfo(isMs, m, n, sigCount)
                } else {
                    // Single-sig P2WPKH
                    val hasSig = sigCount > 0 || input.taprootKeySignature != null
                    InputSignatureInfo(false, 1, 1, if (hasSig) 1 else 0)
                }
            }
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                val sigCount = input.partialSigs.size
                
                // Check redeem script for multisig pattern
                val redeemScript = input.redeemScript
                if (redeemScript != null) {
                    val (isMs, m, n) = parseMultisigScript(redeemScript)
                    InputSignatureInfo(isMs, m, n, sigCount)
                } else {
                    InputSignatureInfo(false, 1, 1, sigCount.coerceAtMost(1))
                }
            }
            is Input.WitnessInput.FinalizedWitnessInput -> {
                // Already finalized - count as fully signed
                InputSignatureInfo(false, 1, 1, 1)
            }
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> {
                InputSignatureInfo(false, 1, 1, 1)
            }
            is Input.FinalizedInputWithoutUtxo -> {
                InputSignatureInfo(false, 1, 1, 1)
            }
            is Input.PartiallySignedInputWithoutUtxo -> {
                InputSignatureInfo(false, 1, 1, 0)
            }
        }
    }
    
    /**
     * Parses a script to detect m-of-n multisig pattern.
     * Looks for: OP_m <pubkey1> <pubkey2> ... OP_n OP_CHECKMULTISIG
     * 
     * @return Triple of (isMultisig, m, n)
     */
    private fun parseMultisigScript(script: List<ScriptElt>): Triple<Boolean, Int, Int> {
        return try {
            // Check for OP_CHECKMULTISIG at end
            if (script.isEmpty() || script.last() != OP_CHECKMULTISIG) {
                return Triple(false, 1, 1)
            }
            
            // Find OP_m at start and OP_n before OP_CHECKMULTISIG
            val m = ScriptUtils.opNumToInt(script.firstOrNull())
            val n = ScriptUtils.opNumToInt(script.getOrNull(script.size - 2))
            
            if (m != null && n != null && m in 1..16 && n in 1..16 && m <= n) {
                Triple(true, m, n)
            } else {
                Triple(false, 1, 1)
            }
        } catch (_: Exception) {
            Triple(false, 1, 1)
        }
    }

    // ==================== Private Helpers ====================

    /**
     * Calculates the virtual size (vBytes) of the transaction.
     */
    private fun calculateVirtualSize(psbt: Psbt, tx: Transaction): Int {
        var hasSegwit = false
        var inputVBytes = 0.0
        
        psbt.inputs.forEach { input ->
            val scriptPubKey = getInputScriptPubKey(input)
            if (scriptPubKey != null) {
                try {
                    val parsedScript = Script.parse(scriptPubKey)
                    inputVBytes += when {
                        Script.isPay2pkh(parsedScript) -> 148.0     // P2PKH: 36 + 1 + 107 + 4
                        Script.isPay2sh(parsedScript) -> {
                            hasSegwit = true
                            91.0  // P2SH-P2WPKH: nested segwit estimate
                        }
                        Script.isPay2wpkh(parsedScript) -> {
                            hasSegwit = true
                            68.0  // P2WPKH: 36 + 1 + 4 + (1 + 107)/4
                        }
                        Script.isPay2wsh(parsedScript) -> {
                            hasSegwit = true
                            104.0  // P2WSH 2-of-3 estimate
                        }
                        Script.isPay2tr(parsedScript) -> {
                            hasSegwit = true
                            58.0   // P2TR: 36 + 1 + 4 + (1 + 65)/4
                        }
                        else -> 68.0  // Default to P2WPKH
                    }
                } catch (_: Exception) {
                    inputVBytes += 68.0
                }
            } else {
                inputVBytes += 68.0
            }
        }
        
        // Calculate output vBytes: nValue(8) + scriptPubKey length(1) + scriptPubKey
        var outputVBytes = 0.0
        tx.txOut.forEach { txOut ->
            try {
                val parsedScript = Script.parse(txOut.publicKeyScript)
                val scriptLen = when {
                    Script.isPay2pkh(parsedScript) -> 25   // P2PKH output
                    Script.isPay2sh(parsedScript) -> 23    // P2SH output
                    Script.isPay2wpkh(parsedScript) -> 22  // P2WPKH output
                    Script.isPay2wsh(parsedScript) -> 34   // P2WSH output
                    Script.isPay2tr(parsedScript) -> 34    // P2TR output
                    else -> 22  // Default to P2WPKH
                }
                outputVBytes += 8 + 1 + scriptLen
            } catch (_: Exception) {
                outputVBytes += 31.0
            }
        }
        
        // Overhead: version(4) + inputCount(1) + outputCount(1) + locktime(4) + segwit marker(0.5 if segwit)
        val overheadVBytes = if (hasSegwit) 10.5 else 10.0
        return (overheadVBytes + inputVBytes + outputVBytes).toInt()
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
                    Log.d(TAG, "  Derived pubkey: ${derivedPubKey.value.toHex().take(20)}...")
                    Log.d(TAG, "  PSBT pubkey:    ${publicKey.value.toHex().take(20)}...")

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
        // Extract account number and child path from original path
        // Typical format: m/purpose'/coin'/account'/change/index
        val pathList = originalPath.path
        if (pathList.size < 3) return null  // Need at least purpose/coin/account
        
        val coinType = if (isTestnet) DeterministicWallet.hardened(1) else DeterministicWallet.hardened(0)
        
        // Try to extract account number (typically at index 2, hardened)
        val originalAccount = pathList.getOrNull(2) ?: return null
        val accountNum = if (DeterministicWallet.isHardened(originalAccount)) {
            originalAccount - DeterministicWallet.hardenedKeyIndex
        } else {
            originalAccount
        }
        
        // Get the child path (change/index portion after account level)
        // For m/48'/0'/0'/2'/0/5, child path would be [0, 5]
        // For paths with script type like m/48'/0'/0'/2'/0/5, need to skip the script type too
        val childStartIndex = when {
            pathList.size >= 5 && DeterministicWallet.isHardened(pathList.getOrNull(3) ?: 0L) -> 4  // BIP48 with script type
            pathList.size >= 4 -> 3  // Standard paths
            else -> return null
        }
        val childPath = if (childStartIndex < pathList.size) pathList.subList(childStartIndex, pathList.size) else emptyList()
        
        Log.d(TAG, "  tryAlternativePaths: original=$originalPath, account=$accountNum, childPath=$childPath")
        
        // Try single-sig paths first (most common case: coordinator converted m/84 to m/48)
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
                    return Pair(derivedKey.privateKey, altPath.toString())
                }
            } catch (e: Exception) {
                Log.d(TAG, "  Alternative path m/$purpose' failed: ${e.message}")
            }
        }
        
        // Try BIP48 multisig paths
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
                    return Pair(derivedKey.privateKey, altPath.toString())
                }
            } catch (e: Exception) {
                Log.d(TAG, "  Alternative path m/48'/.../$ scriptType' failed: ${e.message}")
            }
        }
        
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
            val scriptPubKey = getInputScriptPubKey(input) ?: return null

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

    private fun getInputScriptPubKey(input: Input): ByteVector? {
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.txOut.publicKeyScript
            is Input.WitnessInput.FinalizedWitnessInput -> input.txOut.publicKeyScript
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                input.inputTx.txOut.getOrNull(input.outputIndex)?.publicKeyScript
            }
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> {
                input.inputTx.txOut.getOrNull(input.outputIndex)?.publicKeyScript
            }
            is Input.PartiallySignedInputWithoutUtxo -> null
            is Input.FinalizedInputWithoutUtxo -> null
        }
    }

    private fun getInputValue(input: Input?): Long {
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.amount.toLong()
            is Input.WitnessInput.FinalizedWitnessInput -> input.amount.toLong()
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> input.amount.toLong()
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> input.amount.toLong()
            is Input.PartiallySignedInputWithoutUtxo -> 0L
            is Input.FinalizedInputWithoutUtxo -> 0L
            null -> 0L
        }
    }

    private fun extractAddressFromOutput(txOut: TxOut, chainHash: BlockHash): String {
        return try {
            val scriptPubKey = Script.parse(txOut.publicKeyScript)
            when (val result = Bitcoin.addressFromPublicKeyScript(chainHash, scriptPubKey)) {
                is Either.Right -> result.value
                is Either.Left -> "Unknown"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }
    
    /**
     * Strips global xpub entries (keytype 0x01) from PSBT bytes.
     * This is a workaround for PSBTs with malformed xpub metadata that fail strict validation.
     * The global xpubs are not required for signing - we use derivation paths from inputs instead.
     */
    private fun stripGlobalXpubs(psbtBytes: ByteArray): ByteArray? {
        return try {
            // PSBT structure: magic (5) + global section + inputs + outputs
            if (psbtBytes.size < 5) return null
            
            // Verify magic header: "psbt\xff"
            if (psbtBytes[0] != 0x70.toByte() || 
                psbtBytes[1] != 0x73.toByte() || 
                psbtBytes[2] != 0x62.toByte() || 
                psbtBytes[3] != 0x74.toByte() ||
                psbtBytes[4] != 0xff.toByte()) {
                return null
            }
            
            val output = java.io.ByteArrayOutputStream()
            // Write magic header
            output.write(psbtBytes, 0, 5)
            
            var pos = 5
            
            // Parse global section, excluding xpub entries (keytype 0x01)
            while (pos < psbtBytes.size) {
                // Read key length
                val (keyLen, keyLenBytes) = readVarint(psbtBytes, pos)
                if (keyLen == 0L) {
                    // End of global section - write separator
                    output.write(0x00)
                    pos += keyLenBytes
                    break
                }
                
                pos += keyLenBytes
                if (pos + keyLen > psbtBytes.size) return null
                
                val keyStart = pos
                val keyFirstByte = psbtBytes[pos]
                pos += keyLen.toInt()
                
                // Read value length
                val (valueLen, valueLenBytes) = readVarint(psbtBytes, pos)
                pos += valueLenBytes
                if (pos + valueLen > psbtBytes.size) return null
                
                val valueStart = pos
                pos += valueLen.toInt()
                
                // Skip xpub entries (keytype 0x01), keep everything else
                if (keyFirstByte != 0x01.toByte()) {
                    writeVarint(keyLen, output)
                    output.write(psbtBytes, keyStart, keyLen.toInt())
                    writeVarint(valueLen, output)
                    output.write(psbtBytes, valueStart, valueLen.toInt())
                } else {
                    Log.d(TAG, "Stripping global xpub entry")
                }
            }
            
            // Copy the rest of the PSBT (inputs and outputs) unchanged
            if (pos < psbtBytes.size) {
                output.write(psbtBytes, pos, psbtBytes.size - pos)
            }
            
            output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error stripping global xpubs: ${e.message}")
            null
        }
    }
    
    /**
     * Reads a varint from byte array at given position.
     * @return Pair of (value, bytesRead)
     */
    private fun readVarint(bytes: ByteArray, pos: Int): Pair<Long, Int> {
        if (pos >= bytes.size) return Pair(0, 0)
        val first = bytes[pos].toInt() and 0xFF
        return when {
            first < 0xFD -> Pair(first.toLong(), 1)
            first == 0xFD -> {
                if (pos + 2 >= bytes.size) return Pair(0, 0)
                val v = ((bytes[pos + 1].toInt() and 0xFF) or 
                        ((bytes[pos + 2].toInt() and 0xFF) shl 8)).toLong()
                Pair(v, 3)
            }
            first == 0xFE -> {
                if (pos + 4 >= bytes.size) return Pair(0, 0)
                val v = ((bytes[pos + 1].toInt() and 0xFF) or 
                        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 3].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 4].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
                Pair(v, 5)
            }
            else -> {
                if (pos + 8 >= bytes.size) return Pair(0, 0)
                var v = 0L
                for (i in 0..7) {
                    v = v or ((bytes[pos + 1 + i].toLong() and 0xFF) shl (i * 8))
                }
                Pair(v, 9)
            }
        }
    }
    
    /**
     * Writes a varint to output stream.
     */
    private fun writeVarint(value: Long, output: java.io.ByteArrayOutputStream) {
        when {
            value < 0xFD -> output.write(value.toInt())
            value <= 0xFFFF -> {
                output.write(0xFD)
                output.write((value and 0xFF).toInt())
                output.write(((value shr 8) and 0xFF).toInt())
            }
            value <= 0xFFFFFFFFL -> {
                output.write(0xFE)
                output.write((value and 0xFF).toInt())
                output.write(((value shr 8) and 0xFF).toInt())
                output.write(((value shr 16) and 0xFF).toInt())
                output.write(((value shr 24) and 0xFF).toInt())
            }
            else -> {
                output.write(0xFF)
                for (i in 0..7) {
                    output.write(((value shr (i * 8)) and 0xFF).toInt())
                }
            }
        }
    }
}

/**
 * Internal data class for address key information used in PSBT signing.
 */
private data class AddressKeyInfo(
    val changeIndex: Long,
    val addressIndex: Long,
    val publicKey: PublicKey
)

/**
 * Internal data class for input signature analysis results.
 */
private data class InputSignatureInfo(
    val isMultisig: Boolean,
    val requiredSignatures: Int,
    val totalSigners: Int,
    val currentSignatures: Int
)

