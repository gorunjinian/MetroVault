package com.gorunjinian.metrovault.domain.service.psbt

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import fr.acinq.secp256k1.Hex

/**
 * Handles PSBT finalization (constructing final transactions from signed PSBTs).
 */
@Suppress("KDocUnresolvedReference")
internal object PsbtFinalizer {
    private const val TAG = "PsbtFinalizer"

    /**
     * Result of PSBT finalization.
     */
    sealed class FinalizePsbtResult {
        data class Success(val txHex: String) : FinalizePsbtResult()
        data class Failure(val message: String) : FinalizePsbtResult()
    }

    /**
     * Finalizes a PSBT and extracts the raw transaction.
     * This assumes canFinalize() has returned true.
     *
     * For each input:
     * - P2WPKH: Creates witness with [signature, pubkey]
     * - P2TR: Creates witness with [schnorr_signature]
     * - P2PKH: Creates scriptSig with [signature] [pubkey]
     * - P2SH-P2WPKH: Creates witness + scriptSig with redeem script
     * - P2WSH (multisig): Creates witness with [empty, sig1, sig2, ..., sigM, witnessScript]
     * - P2SH-P2WSH (multisig): Creates witness with [empty, sig1, sig2, ..., sigM, witnessScript]
     * - P2SH (bare multisig): Creates scriptSig with [OP_0, sig1, sig2, ..., sigM, redeemScript]
     *
     * @param psbtBase64 Base64 encoded signed PSBT
     * @return FinalizePsbtResult with raw transaction hex on success
     */
    fun finalizePsbt(psbtBase64: String): FinalizePsbtResult {
        return try {
            var psbt = PsbtUtils.parsePsbt(psbtBase64)
                ?: return FinalizePsbtResult.Failure("Failed to parse PSBT")

            // Finalize each input
            for (inputIndex in psbt.inputs.indices) {
                when (val input = psbt.inputs[inputIndex]) {
                    is Input.WitnessInput.PartiallySignedWitnessInput -> {
                        val scriptPubKey = runCatching { Script.parse(input.txOut.publicKeyScript) }.getOrNull()
                            ?: return FinalizePsbtResult.Failure("Failed to parse scriptPubKey for input $inputIndex")

                        val witness: ScriptWitness = when {
                            // P2TR (Taproot)
                            Script.isPay2tr(scriptPubKey) -> {
                                val sig = input.taprootKeySignature
                                    ?: return FinalizePsbtResult.Failure("Missing taproot signature for input $inputIndex")
                                ScriptWitness(listOf(sig))
                            }
                            // P2WSH (Native SegWit multisig) - has witnessScript
                            Script.isPay2wsh(scriptPubKey) && input.witnessScript != null -> {
                                val witnessScript = input.witnessScript
                                val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(witnessScript)

                                if (isMultisig) {
                                    // Multi-sig witness: [OP_0 (empty for CHECKMULTISIG bug), sig1, sig2, ..., sigM, witnessScript]
                                    if (input.partialSigs.size < m) {
                                        return FinalizePsbtResult.Failure("Input $inputIndex: need $m signatures, have ${input.partialSigs.size}")
                                    }

                                    // Order signatures according to public key order in witnessScript
                                    val orderedSigs = orderSignaturesByScript(input.partialSigs, witnessScript)
                                    if (orderedSigs.size < m) {
                                        return FinalizePsbtResult.Failure("Input $inputIndex: could not order signatures properly")
                                    }

                                    // Take exactly m signatures
                                    val sigs = orderedSigs.take(m)
                                    val witnessScriptBytes = Script.write(witnessScript).byteVector()

                                    // Build witness: empty (for CHECKMULTISIG bug), then signatures, then script
                                    val witnessStack = mutableListOf<ByteVector>()
                                    witnessStack.add(ByteVector.empty) // Empty element for CHECKMULTISIG off-by-one bug
                                    witnessStack.addAll(sigs)
                                    witnessStack.add(witnessScriptBytes)

                                    ScriptWitness(witnessStack)
                                } else {
                                    // Non-multisig P2WSH - treat as single-sig with witness script
                                    val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                        ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                                    val witnessScriptBytes = Script.write(witnessScript).byteVector()
                                    ScriptWitness(listOf(sig, pubKey.value, witnessScriptBytes))
                                }
                            }
                            // P2SH-P2WSH (Nested SegWit multisig) - has redeemScript pointing to P2WSH and witnessScript
                            Script.isPay2sh(scriptPubKey) && input.witnessScript != null -> {
                                val witnessScript = input.witnessScript
                                val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(witnessScript)

                                if (isMultisig) {
                                    // Same witness as P2WSH
                                    if (input.partialSigs.size < m) {
                                        return FinalizePsbtResult.Failure("Input $inputIndex: need $m signatures, have ${input.partialSigs.size}")
                                    }

                                    val orderedSigs = orderSignaturesByScript(input.partialSigs, witnessScript)
                                    if (orderedSigs.size < m) {
                                        return FinalizePsbtResult.Failure("Input $inputIndex: could not order signatures properly")
                                    }

                                    val sigs = orderedSigs.take(m)
                                    val witnessScriptBytes = Script.write(witnessScript).byteVector()

                                    val witnessStack = mutableListOf<ByteVector>()
                                    witnessStack.add(ByteVector.empty)
                                    witnessStack.addAll(sigs)
                                    witnessStack.add(witnessScriptBytes)

                                    ScriptWitness(witnessStack)
                                } else {
                                    val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                        ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                                    val witnessScriptBytes = Script.write(witnessScript).byteVector()
                                    ScriptWitness(listOf(sig, pubKey.value, witnessScriptBytes))
                                }
                            }
                            // P2WPKH (Native SegWit single-sig)
                            Script.isPay2wpkh(scriptPubKey) -> {
                                val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                    ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                                ScriptWitness(listOf(sig, pubKey.value))
                            }
                            // P2SH-P2WPKH (Nested SegWit single-sig) - redeemScript points to P2WPKH
                            Script.isPay2sh(scriptPubKey) && input.redeemScript != null -> {
                                val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                    ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                                ScriptWitness(listOf(sig, pubKey.value))
                            }
                            else -> return FinalizePsbtResult.Failure("Unsupported script type for input $inputIndex")
                        }

                        when (val result = psbt.finalizeWitnessInput(inputIndex, witness)) {
                            is Either.Right -> psbt = result.value
                            is Either.Left -> return FinalizePsbtResult.Failure("Failed to finalize input $inputIndex: ${result.value}")
                        }
                    }
                    is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                        val redeemScript = input.redeemScript

                        val scriptSig: List<ScriptElt> = if (redeemScript != null) {
                            val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(redeemScript)

                            if (isMultisig) {
                                // P2SH bare multi-sig: scriptSig = [OP_0, sig1, sig2, ..., sigM, redeemScript]
                                if (input.partialSigs.size < m) {
                                    return FinalizePsbtResult.Failure("Input $inputIndex: need $m signatures, have ${input.partialSigs.size}")
                                }

                                val orderedSigs = orderSignaturesByScript(input.partialSigs, redeemScript)
                                if (orderedSigs.size < m) {
                                    return FinalizePsbtResult.Failure("Input $inputIndex: could not order signatures properly")
                                }

                                val sigs = orderedSigs.take(m)
                                val redeemScriptBytes = Script.write(redeemScript)

                                // Build scriptSig: OP_0 (for CHECKMULTISIG bug), then sigs, then redeemScript
                                val scriptSigElements = mutableListOf<ScriptElt>()
                                scriptSigElements.add(OP_0)  // Empty element for CHECKMULTISIG off-by-one bug
                                sigs.forEach { sig -> scriptSigElements.add(OP_PUSHDATA(sig)) }
                                scriptSigElements.add(OP_PUSHDATA(redeemScriptBytes))

                                scriptSigElements
                            } else {
                                // P2SH-wrapped single-sig (rare, but handle it)
                                val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                    ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                                listOf(OP_PUSHDATA(sig), OP_PUSHDATA(pubKey.value))
                            }
                        } else {
                            // P2PKH (Legacy single-sig)
                            val (pubKey, sig) = input.partialSigs.entries.firstOrNull()
                                ?: return FinalizePsbtResult.Failure("Missing signature for input $inputIndex")
                            listOf(OP_PUSHDATA(sig), OP_PUSHDATA(pubKey.value))
                        }

                        when (val result = psbt.finalizeNonWitnessInput(inputIndex, scriptSig)) {
                            is Either.Right -> psbt = result.value
                            is Either.Left -> return FinalizePsbtResult.Failure("Failed to finalize input $inputIndex: ${result.value}")
                        }
                    }
                    // Already finalized - skip
                    is Input.WitnessInput.FinalizedWitnessInput,
                    is Input.NonWitnessInput.FinalizedNonWitnessInput,
                    is Input.FinalizedInputWithoutUtxo -> { /* Already finalized */ }

                    is Input.PartiallySignedInputWithoutUtxo ->
                        return FinalizePsbtResult.Failure("Input $inputIndex missing UTXO data")
                }
            }

            // Extract the final transaction
            when (val extractResult = psbt.extract()) {
                is Either.Right -> {
                    val tx = extractResult.value
                    val txBytes = Transaction.write(tx)
                    val txHex = Hex.encode(txBytes)
                    Log.d(TAG, "Successfully finalized PSBT, tx size: ${txBytes.size} bytes")
                    FinalizePsbtResult.Success(txHex)
                }
                is Either.Left -> FinalizePsbtResult.Failure("Failed to extract transaction: ${extractResult.value}")
            }
        } catch (e: Exception) {
            Log.e(TAG, "Exception during PSBT finalization: ${e.message}", e)
            FinalizePsbtResult.Failure("Finalization error: ${e.message}")
        }
    }

    /**
     * Orders signatures according to the order of public keys in the multisig script.
     * Bitcoin's CHECKMULTISIG requires signatures to be in the same order as their
     * corresponding public keys appear in the script.
     *
     * @param partialSigs Map of public keys to their signatures
     * @param witnessScript The multisig witness script containing ordered public keys
     * @return List of signatures ordered according to public key order in script
     */
    fun orderSignaturesByScript(
        partialSigs: Map<PublicKey, ByteVector>,
        witnessScript: List<ScriptElt>
    ): List<ByteVector> {
        // Extract public keys from the witness script (they appear between OP_m and OP_n)
        val scriptPubKeys = witnessScript
            .filterIsInstance<OP_PUSHDATA>()
            .filter { it.data.size() == 33 || it.data.size() == 65 } // Compressed or uncompressed pubkeys
            .map { it.data.toByteArray().toList() }

        Log.d(TAG, "orderSignaturesByScript: Script has ${scriptPubKeys.size} pubkeys, partialSigs has ${partialSigs.size}")

        // Order signatures by matching public key order
        val orderedSigs = mutableListOf<ByteVector>()
        for (scriptPubKey in scriptPubKeys) {
            // Find matching signature
            val matchingSig = partialSigs.entries.find { (pubKey, _) ->
                pubKey.value.toByteArray().toList() == scriptPubKey
            }
            if (matchingSig != null) {
                orderedSigs.add(matchingSig.value)
                Log.d(TAG, "orderSignaturesByScript: Found sig for pubkey at position ${orderedSigs.size}")
            }
        }

        return orderedSigs
    }
}
