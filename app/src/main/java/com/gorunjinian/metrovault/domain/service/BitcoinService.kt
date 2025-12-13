package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.psbt.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.data.model.BitcoinAddress
import com.gorunjinian.metrovault.data.model.ScriptType
import java.security.MessageDigest
import java.security.SecureRandom

/**
 * Service responsible for all Bitcoin-related operations using the bitcoin library.
 * This isolates the backend Bitcoin logic from the rest of the app.
 */
class BitcoinService {

    companion object {
        private const val TAG = "BitcoinService"
        private val CHAIN_HASH = Block.LivenetGenesisBlock.hash // Mainnet
        // Increased from 100 to 500 per chain (1000 total) for better coverage
        private const val ADDRESS_SCAN_GAP = 500
    }

    data class WalletCreationResult(
        val masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        val accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        val accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        val fingerprint: String
    )

    @Suppress("unused") // Public API for future use/testing
    fun generateMnemonic(wordCount: Int = 24): List<String> {
        return generateMnemonicWithUserEntropy(wordCount, null)
    }

    /**
     * Generates a BIP39 mnemonic, optionally mixing user-provided entropy with system entropy.
     *
     * @param wordCount Number of words (12, 15, 18, 21, or 24)
     * @param userEntropy Optional user-provided entropy (from coin flips, dice rolls, etc.)
     * @return List of mnemonic words
     *
     * Security: User entropy supplements but never replaces system entropy.
     * Even if userEntropy is predictable, the result is cryptographically secure
     * because system entropy from SecureRandom is always mixed in.
     */
    fun generateMnemonicWithUserEntropy(wordCount: Int = 24, userEntropy: ByteArray?): List<String> {
        val entropySize = when (wordCount) {
            12 -> 16  // 128 bits
            15 -> 20  // 160 bits
            18 -> 24  // 192 bits
            21 -> 28  // 224 bits
            24 -> 32  // 256 bits
            else -> 32
        }

        val systemEntropy = ByteArray(entropySize)
        var combinedEntropy: ByteArray? = null
        var finalEntropy: ByteArray? = null

        return try {
            // Always generate full system entropy
            SecureRandom().nextBytes(systemEntropy)

            finalEntropy = if (userEntropy != null && userEntropy.isNotEmpty()) {
                // Mix user entropy with system entropy using SHA-256
                combinedEntropy = userEntropy + systemEntropy
                val hash = MessageDigest.getInstance("SHA-256").digest(combinedEntropy)
                // Take the required number of bytes
                hash.copyOfRange(0, entropySize)
            } else {
                // No user entropy, use system entropy directly
                systemEntropy.copyOf()
            }

            MnemonicCode.toMnemonics(finalEntropy)
        } finally {
            // Securely wipe all entropy from memory
            systemEntropy.fill(0)
            combinedEntropy?.fill(0)
            finalEntropy?.fill(0)
        }
    }

    fun validateMnemonic(words: List<String>): Boolean {
        return try {
            MnemonicCode.validate(words)
            true
        } catch (_: Exception) {
            // Don't log exception details - might contain partial mnemonic info
            Log.w(TAG, "Mnemonic validation failed")
            false
        }
    }

    /**
     * Calculates the master fingerprint from mnemonic and passphrase.
     * Used for real-time fingerprint preview in passphrase dialogs.
     * 
     * @return 8-character hex fingerprint or null on error
     */
    fun calculateFingerprint(mnemonicWords: List<String>, passphrase: String): String? {
        return try {
            val seed = MnemonicCode.toSeed(mnemonicWords, passphrase)
            val masterPrivateKey = DeterministicWallet.generate(seed.byteVector())
            val masterPubKey = masterPrivateKey.publicKey
            val hash160 = Crypto.hash160(masterPubKey.value.toByteArray())
            hash160.take(4).joinToString("") { "%02x".format(it) }
        } catch (_: Exception) {
            Log.w(TAG, "Failed to calculate fingerprint")
            null
        }
    }

    fun createWalletFromMnemonic(
        mnemonicWords: List<String>,
        passphrase: String = "",
        derivationPath: String
    ): WalletCreationResult? {
        return try {
            val seed = MnemonicCode.toSeed(mnemonicWords, passphrase)
            val masterPrivateKey = DeterministicWallet.generate(seed.byteVector())
            val path = parseDerivationPath(derivationPath)
            val accountPrivateKey = masterPrivateKey.derivePrivateKey(path)
            val accountPublicKey = accountPrivateKey.extendedPublicKey

            val masterPubKey = masterPrivateKey.publicKey
            val hash160 = Crypto.hash160(masterPubKey.value.toByteArray())
            val fingerprint = hash160.take(4).joinToString("") { "%02x".format(it) }

            WalletCreationResult(
                masterPrivateKey = masterPrivateKey,
                accountPrivateKey = accountPrivateKey,
                accountPublicKey = accountPublicKey,
                fingerprint = fingerprint
            )
        } catch (_: Exception) {
            // Don't log exception details - might contain sensitive wallet info
            Log.e(TAG, "Failed to create wallet from mnemonic")
            null
        }
    }

    fun generateAddress(
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        index: Int,
        isChange: Boolean,
        scriptType: ScriptType
    ): BitcoinAddress? {
        return try {
            val changeIndex = if (isChange) 1L else 0L
            val addressKey = accountPublicKey
                .derivePublicKey(changeIndex)
                .derivePublicKey(index.toLong())

            val publicKey = addressKey.publicKey
            val address = when (scriptType) {
                ScriptType.P2PKH -> Bitcoin.computeP2PkhAddress(publicKey, CHAIN_HASH)
                ScriptType.P2SH_P2WPKH -> Bitcoin.computeP2ShOfP2WpkhAddress(publicKey, CHAIN_HASH)
                ScriptType.P2WPKH -> Bitcoin.computeP2WpkhAddress(publicKey, CHAIN_HASH)
                ScriptType.P2TR -> {
                    val xOnlyPubKey = XonlyPublicKey(publicKey)
                    Bitcoin.computeBIP86Address(xOnlyPubKey, CHAIN_HASH)
                }
            }

            val fullPath = "${accountPublicKey.path}/$changeIndex/$index"

            BitcoinAddress(
                address = address,
                derivationPath = fullPath,
                index = index,
                isChange = isChange,
                publicKey = publicKey.toHex(),
                scriptType = scriptType
            )
        } catch (_: Exception) {
            Log.e(TAG, "Failed to generate address at index $index")
            null
        }
    }

    /**
     * Data class for address key pair.
     */
    data class AddressKeyPair(
        val publicKey: String,      // Compressed public key (33 bytes hex, starts with 02 or 03)
        val privateKeyWIF: String   // Private key in WIF format (starts with K or L for mainnet compressed)
    )

    /**
     * Gets the public and private key for a specific address.
     * @param accountPrivateKey The wallet's account private key
     * @param index Address index
     * @param isChange Whether this is a change address
     * @return AddressKeyPair with public key (hex) and private key (WIF), or null on error
     */
    fun getAddressKeys(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        index: Int,
        isChange: Boolean
    ): AddressKeyPair? {
        return try {
            val changeIndex = if (isChange) 1L else 0L
            val addressPrivateKey = accountPrivateKey
                .derivePrivateKey(changeIndex)
                .derivePrivateKey(index.toLong())

            val privateKey = addressPrivateKey.privateKey
            val publicKey = privateKey.publicKey()

            AddressKeyPair(
                publicKey = publicKey.toHex(),
                privateKeyWIF = privateKey.toBase58(Base58.Prefix.SecretKey)
            )
        } catch (e: Exception) {
            Log.e(TAG, "Failed to get address keys at index $index: ${e.message}")
            null
        }
    }

    /**
     * Gets the wallet descriptors using the Descriptor library.
     * FIX for Bug #1: Now properly utilizes Descriptor.kt for BIP84 descriptor generation.
     *
     * @return Pair of (receive descriptor, change descriptor) with checksums, or null on failure
     */
    fun getWalletDescriptors(masterPrivateKey: DeterministicWallet.ExtendedPrivateKey): Pair<String, String>? {
        return try {
            Descriptor.BIP84Descriptors(CHAIN_HASH, masterPrivateKey)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to generate wallet descriptors")
            null
        }
    }

    /**
     * Signs a PSBT using BIP-174 compliant approach:
     * 1. First tries to use derivation path metadata from the PSBT (fast, reliable)
     * 2. Falls back to address scanning if no metadata available
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @param masterPrivateKey Master private key for full path derivation (BIP-174)
     * @param accountPrivateKey Account-level private key for fallback address scanning
     * @param scriptType Script type for address generation in fallback
     */
    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType
    ): String? {
        return try {
            Log.d(TAG, "signPsbt called with scriptType: $scriptType")
            
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
            Log.d(TAG, "PSBT bytes decoded: ${psbtBytes.size} bytes")

            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> {
                    Log.e(TAG, "Failed to parse PSBT: ${psbtResult.value}")
                    return null
                }
            }
            
            Log.d(TAG, "PSBT parsed successfully. Inputs: ${psbt.inputs.size}, Outputs: ${psbt.global.tx.txOut.size}")

            // Compute wallet fingerprint for BIP-174 matching
            val walletFingerprint = computeWalletFingerprint(masterPrivateKey)
            Log.d(TAG, "Wallet fingerprint: ${walletFingerprint.toString(16).padStart(8, '0')}")

            // Build address lookup for fallback (lazy - only used if BIP-174 fails)
            val addressToKeyInfo by lazy {
                Log.d(TAG, "Building address lookup for fallback (scanning $ADDRESS_SCAN_GAP addresses per chain)")
                buildAddressLookup(accountPrivateKey, scriptType)
            }

            var signedPsbt = psbt
            var signedCount = 0
            var bip174Count = 0
            var fallbackCount = 0

            psbt.inputs.forEachIndexed { index, input ->
                // Try BIP-174 derivation path first
                var result = trySignWithDerivationPath(signedPsbt, index, input, masterPrivateKey, walletFingerprint)
                if (result != null) {
                    signedPsbt = result
                    signedCount++
                    bip174Count++
                } else {
                    // Fallback to address scanning
                    result = signInputWithAddressLookup(signedPsbt, index, input, accountPrivateKey, addressToKeyInfo)
                    if (result != null) {
                        signedPsbt = result
                        signedCount++
                        fallbackCount++
                    }
                }
            }

            if (signedCount == 0) {
                Log.w(TAG, "No inputs were signed - none matched wallet")
                Log.w(TAG, "Checked: BIP-174 derivation paths and ${ADDRESS_SCAN_GAP * 2} addresses")
                return null
            }

            Log.d(TAG, "Signed $signedCount inputs: $bip174Count via BIP-174, $fallbackCount via address lookup")

            val signedBytes = Psbt.write(signedPsbt).toByteArray()
            android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to sign PSBT: ${e.message}", e)
            null
        }
    }

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

    fun getPsbtDetails(psbtBase64: String): PsbtDetails? {
        return try {
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> {
                    Log.e(TAG, "Failed to parse PSBT: ${psbtResult.value}")
                    return null
                }
            }

            val tx = psbt.global.tx

            val inputs = tx.txIn.mapIndexed { index, txIn ->
                val input = psbt.inputs.getOrNull(index)
                val inputValue = getInputValue(input)
                
                // Extract input address from scriptPubKey
                val inputAddress = input?.let { getInputScriptPubKey(it) }?.let { scriptPubKey ->
                    try {
                        val parsedScript = Script.parse(scriptPubKey)
                        when (val result = Bitcoin.addressFromPublicKeyScript(Block.LivenetGenesisBlock.hash, parsedScript)) {
                            is Either.Right -> result.value
                            is Either.Left -> null
                        }
                    } catch (_: Exception) {
                        null
                    }
                } ?: "Unknown"

                PsbtInput(
                    address = inputAddress,
                    prevTxHash = txIn.outPoint.txid.toString(),
                    prevTxIndex = txIn.outPoint.index.toInt(),
                    value = inputValue
                )
            }

            val outputs = tx.txOut.map { txOut ->
                val address = extractAddressFromOutput(txOut)
                PsbtOutput(address = address, value = txOut.amount.toLong())
            }

            val totalInput = inputs.sumOf { it.value }
            val totalOutput = outputs.sumOf { it.value }
            val fee = if (totalInput > 0) totalInput - totalOutput else null

            PsbtDetails(inputs = inputs, outputs = outputs, fee = fee)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to parse PSBT details")
            null
        }
    }

    fun getAccountXpub(accountPublicKey: DeterministicWallet.ExtendedPublicKey, scriptType: ScriptType): String {
        val prefix = when (scriptType) {
            ScriptType.P2PKH -> DeterministicWallet.xpub
            ScriptType.P2SH_P2WPKH -> DeterministicWallet.ypub
            ScriptType.P2WPKH -> DeterministicWallet.zpub
            ScriptType.P2TR -> DeterministicWallet.xpub
        }
        return accountPublicKey.encode(prefix)
    }

    fun checkAddressBelongsToWallet(
        address: String,
        accountPublicKey: DeterministicWallet.ExtendedPublicKey,
        scriptType: ScriptType,
        scanRange: Int = ADDRESS_SCAN_GAP
    ): AddressCheckResult? {
        return try {
            for (i in 0 until scanRange) {
                val addr = generateAddress(accountPublicKey, i, isChange = false, scriptType)
                if (addr?.address == address) {
                    return AddressCheckResult(true, addr.derivationPath, i, false)
                }
            }

            for (i in 0 until scanRange) {
                val addr = generateAddress(accountPublicKey, i, isChange = true, scriptType)
                if (addr?.address == address) {
                    return AddressCheckResult(true, addr.derivationPath, i, true)
                }
            }

            AddressCheckResult(false, null, null, null)
        } catch (_: Exception) {
            Log.e(TAG, "Failed to check address")
            null
        }
    }

    /**
     * Parses a BIP32 derivation path string into a list of path indices.
     * Validates path format before parsing.
     *
     * @param pathString Path in format "m/44'/0'/0'" or "m/44h/0h/0h"
     * @return List of path indices (with hardened flag applied)
     * @throws IllegalArgumentException if path format is invalid
     */
    private fun parseDerivationPath(pathString: String): List<Long> {
        // Validate path format with regex
        val pathRegex = Regex("""^m(/\d+['h]?)+$""")
        require(pathString.matches(pathRegex)) {
            "Invalid derivation path format: $pathString. Expected format: m/44'/0'/0'"
        }

        val path = pathString.removePrefix("m/").split("/")
        return path.map { segment ->
            val isHardened = segment.endsWith("'") || segment.endsWith("h")
            val indexStr = segment.removeSuffix("'").removeSuffix("h")

            // Validate index is a valid number
            val index = indexStr.toLongOrNull()
                ?: throw IllegalArgumentException("Invalid path index: $segment")

            // Validate index range (BIP32 allows 0 to 2^31-1)
            require(index in 0..2147483647L) {
                "Path index out of range: $index"
            }

            if (isHardened) DeterministicWallet.hardened(index) else index
        }
    }

    /**
     * Computes the wallet's master key fingerprint as used in BIP-174 PSBTs.
     * This is the first 4 bytes of hash160(master public key) as an unsigned Long.
     */
    private fun computeWalletFingerprint(masterPrivateKey: DeterministicWallet.ExtendedPrivateKey): Long {
        val masterPubKey = masterPrivateKey.publicKey
        val hash160 = Crypto.hash160(masterPubKey.value.toByteArray())
        // Convert first 4 bytes to unsigned Long (big-endian as per BIP-174)
        return ((hash160[0].toLong() and 0xFF) shl 24) or
               ((hash160[1].toLong() and 0xFF) shl 16) or
               ((hash160[2].toLong() and 0xFF) shl 8) or
               (hash160[3].toLong() and 0xFF)
    }

    /**
     * Attempts to sign an input using BIP-174 derivation path metadata.
     * This is the preferred method as it's fast and reliable.
     *
     * @return signed PSBT if derivation path matched our wallet, null otherwise
     */
    private fun trySignWithDerivationPath(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        walletFingerprint: Long
    ): Psbt? {
        return try {
            // Check regular derivation paths (for non-taproot)
            for ((publicKey, keyPathWithMaster) in input.derivationPaths) {
                if (keyPathWithMaster.masterKeyFingerprint == walletFingerprint) {
                    Log.d(TAG, "Input $inputIndex: Found matching BIP-174 derivation path: ${keyPathWithMaster.keyPath}")
                    
                    // Derive key using the full path from master
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(keyPathWithMaster.keyPath)
                        .privateKey
                    
                    // Verify derived public key matches
                    if (signingPrivateKey.publicKey() != publicKey) {
                        Log.w(TAG, "Input $inputIndex: Derived public key doesn't match PSBT public key")
                        continue
                    }
                    
                    return signInput(psbt, inputIndex, input, signingPrivateKey, publicKey)
                }
            }

            // Check taproot derivation paths
            for ((xOnlyPublicKey, taprootPath) in input.taprootDerivationPaths) {
                if (taprootPath.masterKeyFingerprint == walletFingerprint) {
                    Log.d(TAG, "Input $inputIndex: Found matching Taproot BIP-174 derivation path: ${taprootPath.keyPath}")
                    
                    // Derive key using the full path from master
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(taprootPath.keyPath)
                        .privateKey
                    
                    // Verify derived x-only public key matches
                    val derivedXOnly = XonlyPublicKey(signingPrivateKey.publicKey())
                    if (derivedXOnly != xOnlyPublicKey) {
                        Log.w(TAG, "Input $inputIndex: Derived x-only public key doesn't match PSBT")
                        continue
                    }
                    
                    return signInput(psbt, inputIndex, input, signingPrivateKey, signingPrivateKey.publicKey())
                }
            }

            // No matching derivation path found
            if (input.derivationPaths.isEmpty() && input.taprootDerivationPaths.isEmpty()) {
                Log.d(TAG, "Input $inputIndex: No BIP-174 derivation paths in PSBT")
            } else {
                Log.d(TAG, "Input $inputIndex: BIP-174 paths present but fingerprint doesn't match wallet")
            }
            null
        } catch (e: Exception) {
            Log.w(TAG, "Input $inputIndex: BIP-174 signing failed: ${e.message}")
            null
        }
    }

    /**
     * Signs an input using address lookup fallback.
     * Used when PSBT doesn't contain BIP-174 derivation metadata.
     */
    private fun signInputWithAddressLookup(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        addressLookup: Map<ByteVector, AddressKeyInfo>
    ): Psbt? {
        return try {
            val scriptPubKey = getInputScriptPubKey(input)
            if (scriptPubKey == null) {
                Log.d(TAG, "Input $inputIndex: No scriptPubKey available for fallback")
                return null
            }

            val keyInfo = addressLookup[scriptPubKey]
            if (keyInfo == null) {
                Log.d(TAG, "Input $inputIndex: Address not found in wallet (scanned ${addressLookup.size} addresses)")
                return null
            }

            Log.d(TAG, "Input $inputIndex: Found via address lookup at change=${keyInfo.changeIndex}, index=${keyInfo.addressIndex}")

            val signingPrivateKey = accountPrivateKey
                .derivePrivateKey(keyInfo.changeIndex)
                .derivePrivateKey(keyInfo.addressIndex)
                .privateKey

            signInput(psbt, inputIndex, input, signingPrivateKey, keyInfo.publicKey)
        } catch (e: Exception) {
            Log.e(TAG, "Input $inputIndex: Address lookup signing failed: ${e.message}")
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
                Log.d(TAG, "Input $inputIndex: Adding temporary witnessScript for P2WPKH signing")
                
                val updatedInput = input.copy(witnessScript = witnessScript)
                val updatedInputs = psbt.inputs.toMutableList()
                updatedInputs[inputIndex] = updatedInput
                psbtToSign = psbt.copy(inputs = updatedInputs)
                addedWitnessScriptForP2wpkh = true
            }
        }

        return when (val signResult = psbtToSign.sign(signingPrivateKey, inputIndex)) {
            is Either.Right -> {
                Log.d(TAG, "Input $inputIndex: Signed successfully")
                var signedPsbt = signResult.value.psbt
                
                // Remove the temporary witnessScript for P2WPKH - it should NOT be in the final PSBT
                // as it causes some finalizers (like BlueWallet) to incorrectly add it to the witness
                if (addedWitnessScriptForP2wpkh) {
                    signedPsbt = removeWitnessScriptFromInput(signedPsbt, inputIndex)
                    Log.d(TAG, "Input $inputIndex: Removed temporary witnessScript from P2WPKH input")
                }
                
                signedPsbt
            }
            is Either.Left -> {
                Log.w(TAG, "Input $inputIndex: Sign failed: ${signResult.value}")
                null
            }
        }
    }

    /**
     * Removes the witnessScript field from a P2WPKH input after signing.
     * Per BIP-174, P2WPKH inputs should NOT have the witnessScript field set.
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


    private fun buildAddressLookup(
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType
    ): Map<ByteVector, AddressKeyInfo> {
        val lookup = mutableMapOf<ByteVector, AddressKeyInfo>()
        val accountPublicKey = accountPrivateKey.extendedPublicKey

        for (isChange in listOf(false, true)) {
            val changeIndex = if (isChange) 1L else 0L

            for (i in 0 until ADDRESS_SCAN_GAP) {
                try {
                    val addressKey = accountPublicKey
                        .derivePublicKey(changeIndex)
                        .derivePublicKey(i.toLong())

                    val publicKey = addressKey.publicKey
                    val scriptPubKey = createScriptPubKey(publicKey, scriptType)

                    if (scriptPubKey != null) {
                        lookup[scriptPubKey] = AddressKeyInfo(changeIndex, i.toLong(), publicKey)
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to derive address at index $i: ${e.message}")
                }
            }
        }

        Log.d(TAG, "Built address lookup with ${lookup.size} entries")
        return lookup
    }

    private fun createScriptPubKey(publicKey: PublicKey, scriptType: ScriptType): ByteVector? {
        return try {
            when (scriptType) {
                ScriptType.P2PKH -> Script.write(Script.pay2pkh(publicKey)).byteVector()
                ScriptType.P2SH_P2WPKH -> {
                    val p2wpkh = Script.pay2wpkh(publicKey)
                    Script.write(Script.pay2sh(p2wpkh)).byteVector()
                }
                ScriptType.P2WPKH -> Script.write(Script.pay2wpkh(publicKey)).byteVector()
                ScriptType.P2TR -> {
                    val xOnlyKey = XonlyPublicKey(publicKey)
                    Script.write(Script.pay2tr(xOnlyKey)).byteVector()
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to create scriptPubKey: ${e.message}")
            null
        }
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

    private fun extractAddressFromOutput(txOut: TxOut): String {
        return try {
            val scriptPubKey = Script.parse(txOut.publicKeyScript)
            when (val result = Bitcoin.addressFromPublicKeyScript(CHAIN_HASH, scriptPubKey)) {
                is Either.Right -> result.value
                is Either.Left -> "Unknown"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }
}

private data class AddressKeyInfo(
    val changeIndex: Long,
    val addressIndex: Long,
    val publicKey: PublicKey
)

data class AddressCheckResult(
    val belongs: Boolean,
    val derivationPath: String?,
    val index: Int?,
    val isChange: Boolean?
)

data class PsbtDetails(
    val inputs: List<PsbtInput>,
    val outputs: List<PsbtOutput>,
    val fee: Long?
)

data class PsbtInput(
    val address: String,
    val prevTxHash: String,
    val prevTxIndex: Int,
    val value: Long
)

data class PsbtOutput(
    val address: String,
    val value: Long
)