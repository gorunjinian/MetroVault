package com.gorunjinian.metrovault.domain.service.psbt

import android.util.Log
import com.gorunjinian.metrovault.data.model.PsbtDetails
import com.gorunjinian.metrovault.data.model.PsbtInput
import com.gorunjinian.metrovault.data.model.PsbtOutput
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.domain.service.util.BitcoinUtils

/**
 * Handles PSBT analysis, signature checking, and details extraction.
 */
internal object PsbtAnalyzer {
    private const val TAG = "PsbtAnalyzer"

    /**
     * Checks if a PSBT is fully signed.
     */
    fun isPsbtFullySigned(psbtBase64: String): Boolean {
        return try {
            val psbt = PsbtUtils.parsePsbt(psbtBase64) ?: return false

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
     * Checks if a PSBT can be finalized (has all required signatures).
     * A PSBT is finalizable if all inputs meet one of these criteria:
     * - Single-sig: exactly 1 partial signature (or taproot key sig)
     * - Multi-sig: at least m partial signatures for m-of-n multisig
     * - Already finalized
     *
     * @param psbtBase64 Base64 encoded PSBT
     * @return true if the PSBT has all required signatures and can be finalized
     */
    fun canFinalize(psbtBase64: String): Boolean {
        return try {
            val psbt = PsbtUtils.parsePsbt(psbtBase64) ?: return false

            psbt.inputs.all { input ->
                when (input) {
                    is Input.WitnessInput.PartiallySignedWitnessInput -> {
                        // Check for taproot first
                        if (input.taprootKeySignature != null) {
                            return@all true
                        }

                        // Check witness script for multisig pattern
                        val witnessScript = input.witnessScript
                        if (witnessScript != null) {
                            val (isMultisig, m, _) = parseMultisigScript(witnessScript)
                            if (isMultisig) {
                                // Multi-sig: need at least m signatures
                                input.partialSigs.size >= m
                            } else {
                                // Has witness script but not multisig - treat as single-sig
                                input.partialSigs.isNotEmpty()
                            }
                        } else {
                            // Single-sig P2WPKH: need exactly 1 signature
                            input.partialSigs.isNotEmpty()
                        }
                    }
                    is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                        // Check redeem script for multisig pattern
                        val redeemScript = input.redeemScript
                        if (redeemScript != null) {
                            val (isMultisig, m, _) = parseMultisigScript(redeemScript)
                            if (isMultisig) {
                                // Multi-sig: need at least m signatures
                                input.partialSigs.size >= m
                            } else {
                                // Has redeem script but not multisig - treat as single-sig
                                input.partialSigs.isNotEmpty()
                            }
                        } else {
                            // Single-sig P2PKH: need exactly 1 signature
                            input.partialSigs.isNotEmpty()
                        }
                    }
                    // Already finalized inputs are fine
                    is Input.WitnessInput.FinalizedWitnessInput -> true
                    is Input.NonWitnessInput.FinalizedNonWitnessInput -> true
                    is Input.FinalizedInputWithoutUtxo -> true
                    // Missing UTXO data - can't finalize
                    is Input.PartiallySignedInputWithoutUtxo -> false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to check if PSBT can be finalized: ${e.message}")
            false
        }
    }

    /**
     * Extracts details from a PSBT for display purposes.
     */
    fun getPsbtDetails(psbtBase64: String, isTestnet: Boolean = false): PsbtDetails? {
        return try {
            Log.d(TAG, "getPsbtDetails called, base64 length: ${psbtBase64.length}")

            val chainHash = BitcoinUtils.getChainHash(isTestnet)
            val psbt = PsbtUtils.parsePsbt(psbtBase64) ?: return null

            val tx = psbt.global.tx

            // Track multisig info across inputs
            var isMultisig = false
            var requiredSignatures = 1
            var totalSigners = 1
            // For multisig, we track the MINIMUM signature count across all multisig inputs
            // (since all inputs need signatures from the same signers)
            var minMultisigSignatures: Int? = null
            var allInputsSigned = true

            val inputs = tx.txIn.mapIndexed { index, txIn ->
                val input = psbt.inputs.getOrNull(index)
                val inputValue = PsbtUtils.getInputValue(input)

                // Extract input address from scriptPubKey
                val inputAddress = input?.let { PsbtUtils.getInputScriptPubKey(it) }?.let { scriptPubKey ->
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
                    // Track minimum signature count across all multisig inputs
                    minMultisigSignatures = minOf(minMultisigSignatures ?: sigCount, sigCount)
                }

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
                val address = PsbtUtils.extractAddressFromOutput(txOut, chainHash)
                PsbtOutput(address = address, value = txOut.amount.toLong())
            }

            val totalInput = inputs.sumOf { it.value }
            val totalOutput = outputs.sumOf { it.value }
            val fee = if (totalInput > 0) totalInput - totalOutput else null

            val virtualSize = PsbtSizeCalculator.calculateVirtualSize(psbt, tx)

            PsbtDetails(
                inputs = inputs,
                outputs = outputs,
                fee = fee,
                virtualSize = virtualSize,
                isMultisig = isMultisig,
                requiredSignatures = requiredSignatures,
                totalSigners = totalSigners,
                currentSignatures = minMultisigSignatures ?: 0,
                isReadyToBroadcast = allInputsSigned
            )
        } catch (_: Exception) {
            null
        }
    }

    /**
     * Analyzes a PSBT input for multisig information and signature count.
     *
     * @return InputSignatureInfo with (isMultisig, m, n, currentSignatureCount)
     */
    fun analyzeInputSignatures(input: Input?): InputSignatureInfo {
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
    fun parseMultisigScript(script: List<ScriptElt>): Triple<Boolean, Int, Int> {
        return try {
            // Check for OP_CHECKMULTISIG at end
            if (script.isEmpty() || script.last() != OP_CHECKMULTISIG) {
                return Triple(false, 1, 1)
            }

            // Find OP_m at start and OP_n before OP_CHECKMULTISIG
            val m = opNumToInt(script.firstOrNull())
            val n = opNumToInt(script.getOrNull(script.size - 2))

            if (m != null && n != null && m in 1..16 && n in 1..16 && m <= n) {
                Triple(true, m, n)
            } else {
                Triple(false, 1, 1)
            }
        } catch (_: Exception) {
            Triple(false, 1, 1)
        }
    }

    /**
     * Converts an OP_N script element to its integer value.
     * Used when parsing multisig scripts to extract m and n values.
     *
     * @param op The script element (OP_0 through OP_16)
     * @return The integer value (0-16), or null if not a valid OP_N
     */
    private fun opNumToInt(op: ScriptElt?): Int? {
        if (op == null) return null
        return if (Script.isSimpleValue(op)) {
            Script.simpleValue(op).toInt()
        } else {
            null
        }
    }
}
