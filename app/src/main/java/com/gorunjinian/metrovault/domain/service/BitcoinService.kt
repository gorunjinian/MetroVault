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
        private const val ADDRESS_SCAN_GAP = 100
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

    fun signPsbt(
        psbtBase64: String,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType
    ): String? {
        return try {
            Log.d(TAG, "signPsbt called with scriptType: $scriptType")
            Log.d(TAG, "PSBT Base64 length: ${psbtBase64.length}, starts with: ${psbtBase64.take(20)}...")
            
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

            val addressToKeyInfo = buildAddressLookup(accountPrivateKey, scriptType)
            Log.d(TAG, "Address lookup built with ${addressToKeyInfo.size} entries for scriptType: $scriptType")

            var signedPsbt = psbt
            var signedCount = 0

            psbt.inputs.forEachIndexed { index, input ->
                val result = signInputIfOurs(signedPsbt, index, input, accountPrivateKey, scriptType, addressToKeyInfo)
                if (result != null) {
                    signedPsbt = result
                    signedCount++
                }
            }

            if (signedCount == 0) {
                Log.w(TAG, "No inputs were signed - none matched wallet addresses")
                Log.w(TAG, "Check if PSBT scriptPubKeys match wallet's address derivation path and script type")
                return null
            }

            Log.d(TAG, "Signed $signedCount inputs successfully")

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

    private fun signInputIfOurs(
        psbt: Psbt,
        inputIndex: Int,
        input: Input,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        @Suppress("UNUSED_PARAMETER") scriptType: ScriptType, // Kept for API consistency
        addressLookup: Map<ByteVector, AddressKeyInfo>
    ): Psbt? {
        return try {
            // Debug: Log input type
            val inputType = when (input) {
                is Input.WitnessInput.PartiallySignedWitnessInput -> "PartiallySignedWitnessInput"
                is Input.WitnessInput.FinalizedWitnessInput -> "FinalizedWitnessInput"
                is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> "PartiallySignedNonWitnessInput"
                is Input.NonWitnessInput.FinalizedNonWitnessInput -> "FinalizedNonWitnessInput"
                is Input.PartiallySignedInputWithoutUtxo -> "PartiallySignedInputWithoutUtxo"
                is Input.FinalizedInputWithoutUtxo -> "FinalizedInputWithoutUtxo"
            }
            Log.d(TAG, "Input $inputIndex type: $inputType")
            
            val scriptPubKey = getInputScriptPubKey(input)
            if (scriptPubKey == null) {
                Log.d(TAG, "Input $inputIndex has no scriptPubKey available (input type: $inputType)")
                return null
            }
            
            // Debug: Log the scriptPubKey we're looking for
            Log.d(TAG, "Input $inputIndex scriptPubKey: ${scriptPubKey.toHex()}")
            
            val keyInfo = addressLookup[scriptPubKey]
            if (keyInfo == null) {
                Log.d(TAG, "Input $inputIndex scriptPubKey not found in wallet (${addressLookup.size} addresses checked)")
                // Debug: Log first few wallet scriptPubKeys for comparison
                addressLookup.entries.take(3).forEachIndexed { idx, entry ->
                    Log.d(TAG, "  Wallet scriptPubKey $idx: ${entry.key.toHex()}")
                }
                return null
            }

            val signingPrivateKey = accountPrivateKey
                .derivePrivateKey(keyInfo.changeIndex)
                .derivePrivateKey(keyInfo.addressIndex)
                .privateKey
            
            val signingPublicKey = keyInfo.publicKey

            // For segwit inputs, we need to add the witnessScript if not present
            // The library requires this even for P2WPKH (it uses pay2pkh as the "witness script")
            var psbtToSign = psbt
            if (input is Input.WitnessInput.PartiallySignedWitnessInput && input.witnessScript == null) {
                // For P2WPKH, the witness script is the pay2pkh script using the public key
                val witnessScript = Script.pay2pkh(signingPublicKey)
                Log.d(TAG, "Adding witnessScript for P2WPKH input $inputIndex")
                
                // Update the input with the witness script
                val updatedInput = input.copy(witnessScript = witnessScript)
                val updatedInputs = psbt.inputs.toMutableList()
                updatedInputs[inputIndex] = updatedInput
                psbtToSign = psbt.copy(inputs = updatedInputs)
            }

            when (val signResult = psbtToSign.sign(signingPrivateKey, inputIndex)) {
                is Either.Right -> {
                    Log.d(TAG, "Successfully signed input $inputIndex")
                    signResult.value.psbt
                }
                is Either.Left -> {
                    Log.w(TAG, "Failed to sign input $inputIndex: ${signResult.value}")
                    null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Error signing input $inputIndex: ${e.message}", e)
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