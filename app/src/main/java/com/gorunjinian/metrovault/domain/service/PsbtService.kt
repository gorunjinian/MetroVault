package com.gorunjinian.metrovault.domain.service

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.psbt.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.data.model.ScriptType

/**
 * Service responsible for PSBT (Partially Signed Bitcoin Transaction) operations.
 * Handles PSBT parsing, signing, and details extraction.
 */
class PsbtService {

    companion object {
        private const val TAG = "PsbtService"
        // Gap limit for address scanning fallback
        private const val ADDRESS_SCAN_GAP = 2000
    }

    private val addressService = AddressService()

    /**
     * Signs a PSBT using BIP-174 compliant approach:
     * 1. First tries to use derivation path metadata from the PSBT (fast, reliable)
     * 2. Falls back to address scanning if no metadata available
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @param masterPrivateKey Master private key for full path derivation (BIP-174)
     * @param accountPrivateKey Account-level private key for fallback address scanning
     * @param scriptType Script type for address generation in fallback
     * @param isTestnet Whether this is a testnet wallet
     */
    fun signPsbt(
        psbtBase64: String,
        masterPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        accountPrivateKey: DeterministicWallet.ExtendedPrivateKey,
        scriptType: ScriptType,
        isTestnet: Boolean = false
    ): String? {
        return try {
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)

            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> return null
            }
            
            // Compute wallet fingerprint for BIP-174 matching
            val walletFingerprint = BitcoinUtils.computeFingerprintLong(masterPrivateKey.publicKey)

            // Build address lookup for fallback (lazy - only used if BIP-174 fails)
            val addressToKeyInfo by lazy {
                buildAddressLookup(accountPrivateKey, scriptType)
            }

            var signedPsbt = psbt
            var signedCount = 0

            psbt.inputs.forEachIndexed { index, input ->
                // Try BIP-174 derivation path first
                var result = trySignWithDerivationPath(signedPsbt, index, input, masterPrivateKey, walletFingerprint)
                if (result != null) {
                    signedPsbt = result
                    signedCount++
                } else {
                    // Fallback to address scanning
                    result = signInputWithAddressLookup(signedPsbt, index, input, accountPrivateKey, addressToKeyInfo)
                    if (result != null) {
                        signedPsbt = result
                        signedCount++
                    }
                }
            }

            if (signedCount == 0) {
                return null
            }

            val signedBytes = Psbt.write(signedPsbt).toByteArray()
            android.util.Base64.encodeToString(signedBytes, android.util.Base64.NO_WRAP)
        } catch (_: Exception) {
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
            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val psbtBytes = android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
            val psbt = when (val psbtResult = Psbt.read(psbtBytes)) {
                is Either.Right -> psbtResult.value
                is Either.Left -> {
                    Log.e(TAG, "Failed to parse PSBT")
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
                        when (val result = Bitcoin.addressFromPublicKeyScript(chainHash, parsedScript)) {
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
                val address = extractAddressFromOutput(txOut, chainHash)
                PsbtOutput(address = address, value = txOut.amount.toLong())
            }

            val totalInput = inputs.sumOf { it.value }
            val totalOutput = outputs.sumOf { it.value }
            val fee = if (totalInput > 0) totalInput - totalOutput else null
            
            val virtualSize = calculateVirtualSize(psbt, tx)

            PsbtDetails(inputs = inputs, outputs = outputs, fee = fee, virtualSize = virtualSize)
        } catch (_: Exception) {
            null
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
                    
                    // Derive key using the full path from master
                    val signingPrivateKey = masterPrivateKey
                        .derivePrivateKey(keyPathWithMaster.keyPath)
                        .privateKey
                    
                    // Verify derived public key matches
                    if (signingPrivateKey.publicKey() != publicKey) {
                        continue
                    }
                    
                    return signInput(psbt, inputIndex, input, signingPrivateKey, publicKey)
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
                    if (derivedXOnly != xOnlyPublicKey) {
                        continue
                    }
                    
                    return signInput(psbt, inputIndex, input, signingPrivateKey, signingPrivateKey.publicKey())
                }
            }

            null
        } catch (_: Exception) {
            null
        }
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

            for (i in 0 until ADDRESS_SCAN_GAP) {
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
 * Details extracted from a PSBT for display purposes.
 */
data class PsbtDetails(
    val inputs: List<PsbtInput>,
    val outputs: List<PsbtOutput>,
    val fee: Long?,
    val virtualSize: Int  // Transaction size in virtual bytes (vBytes)
)

/**
 * PSBT input details.
 */
data class PsbtInput(
    val address: String,
    val prevTxHash: String,
    val prevTxIndex: Int,
    val value: Long
)

/**
 * PSBT output details.
 */
data class PsbtOutput(
    val address: String,
    val value: Long
)
