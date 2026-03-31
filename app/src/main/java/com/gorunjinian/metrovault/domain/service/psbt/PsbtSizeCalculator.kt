package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.lib.bitcoin.*

/**
 * Handles transaction size calculations for PSBT fee estimation.
 * Computes exact sizes for finalized inputs and tight estimates for unsigned inputs.
 */
internal object PsbtSizeCalculator {

    /**
     * Result of input vBytes calculation.
     * @param vBytes The virtual size contribution of the input
     * @param isSegwit Whether this input uses SegWit (affects transaction overhead)
     */
    data class InputSizeResult(val vBytes: Double, val isSegwit: Boolean)

    /** Default estimated ECDSA signature size in bytes (DER average + sighash byte). */
    private const val ESTIMATED_ECDSA_SIG_SIZE = 72

    /** Default Schnorr signature size (64 bytes for SIGHASH_DEFAULT). */
    private const val ESTIMATED_SCHNORR_SIG_SIZE = 64

    /**
     * Calculates the virtual size (vBytes) of the final signed transaction.
     * Uses exact sizes for finalized inputs and per-script-type estimation for unsigned inputs.
     */
    fun calculateVirtualSize(psbt: Psbt, tx: Transaction): Int {
        var hasSegwit = false
        var totalInputVBytes = 0.0

        psbt.inputs.forEach { input ->
            val result = calculateInputVBytes(input)
            totalInputVBytes += result.vBytes
            if (result.isSegwit) hasSegwit = true
        }

        var totalOutputVBytes = 0.0
        tx.txOut.forEach { txOut ->
            totalOutputVBytes += calculateOutputVBytes(txOut)
        }

        // Overhead: version(4) + inputCount(varint) + outputCount(varint) + locktime(4)
        // SegWit marker+flag adds 0.5 vBytes (2 bytes at witness weight)
        val baseOverhead = 4 + varIntSize(psbt.inputs.size.toLong()) + varIntSize(tx.txOut.size.toLong()) + 4
        val overheadVBytes = if (hasSegwit) baseOverhead + 0.5 else baseOverhead.toDouble()

        return kotlin.math.ceil(overheadVBytes + totalInputVBytes + totalOutputVBytes).toInt()
    }

    /**
     * Calculates input vBytes by dispatching on the input type.
     * Finalized inputs yield exact sizes; partially signed inputs use estimation
     * with actual signature sizes where available.
     */
    fun calculateInputVBytes(input: Input): InputSizeResult {
        return when (input) {
            is Input.WitnessInput.FinalizedWitnessInput -> calculateFinalizedWitnessVBytes(input)
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> calculateFinalizedNonWitnessVBytes(input)
            is Input.FinalizedInputWithoutUtxo -> calculateFinalizedWithoutUtxoVBytes(input)
            is Input.WitnessInput.PartiallySignedWitnessInput -> calculatePartialWitnessVBytes(input)
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> calculatePartialNonWitnessVBytes(input)
            is Input.PartiallySignedInputWithoutUtxo -> InputSizeResult(68.0, true)
        }
    }

    // ---- Finalized inputs: exact sizes from serialized data ----

    /**
     * Computes exact vBytes for a finalized SegWit input from its scriptSig and witness data.
     */
    private fun calculateFinalizedWitnessVBytes(input: Input.WitnessInput.FinalizedWitnessInput): InputSizeResult {
        return try {
            val scriptSigSize = input.scriptSig?.let { Script.write(it).size } ?: 0
            val witnessSize = ScriptWitness.write(input.scriptWitness).size
            val baseSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
            val weight = 4 * baseSize + witnessSize
            InputSizeResult(weight / 4.0, true)
        } catch (_: Exception) {
            InputSizeResult(68.0, true)
        }
    }

    /**
     * Computes exact vBytes for a finalized legacy input from its scriptSig.
     */
    private fun calculateFinalizedNonWitnessVBytes(input: Input.NonWitnessInput.FinalizedNonWitnessInput): InputSizeResult {
        return try {
            val scriptSigSize = Script.write(input.scriptSig).size
            val totalSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
            InputSizeResult(totalSize.toDouble(), false)
        } catch (_: Exception) {
            InputSizeResult(148.0, false)
        }
    }

    /**
     * Computes exact vBytes for a finalized input without UTXO data.
     * Determines SegWit status from the presence of witness data.
     */
    private fun calculateFinalizedWithoutUtxoVBytes(input: Input.FinalizedInputWithoutUtxo): InputSizeResult {
        return try {
            val scriptSigSize = input.scriptSig?.let { Script.write(it).size } ?: 0
            val witness = input.scriptWitness

            if (witness != null && witness.isNotNull()) {
                val witnessSize = ScriptWitness.write(witness).size
                val baseSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
                val weight = 4 * baseSize + witnessSize
                InputSizeResult(weight / 4.0, true)
            } else {
                val totalSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
                InputSizeResult(totalSize.toDouble(), false)
            }
        } catch (_: Exception) {
            InputSizeResult(68.0, true)
        }
    }

    // ---- Partially signed inputs: estimate using actual sig sizes when available ----

    /**
     * Estimates vBytes for a partially signed SegWit input.
     * Uses actual signature sizes from partialSigs/taprootKeySignature when present.
     */
    private fun calculatePartialWitnessVBytes(input: Input.WitnessInput.PartiallySignedWitnessInput): InputSizeResult {
        return try {
            val parsedScript = Script.parse(input.txOut.publicKeyScript)

            when {
                // P2WPKH (Native SegWit single-sig)
                Script.isPay2wpkh(parsedScript) -> {
                    val sigSize = input.partialSigs.values.firstOrNull()?.size() ?: ESTIMATED_ECDSA_SIG_SIZE
                    // Witness: items_count(1) + compact_size(sig)(1) + sig + compact_size(33)(1) + pubkey(33)
                    val witnessSize = 1 + 1 + sigSize + 1 + 33
                    val weight = 41 * 4 + witnessSize
                    InputSizeResult(weight / 4.0, true)
                }

                // P2TR (Taproot key-path spend)
                Script.isPay2tr(parsedScript) -> {
                    val sigSize = input.taprootKeySignature?.size() ?: ESTIMATED_SCHNORR_SIG_SIZE
                    // Witness: items_count(1) + compact_size(sig)(1) + sig
                    val witnessSize = 1 + 1 + sigSize
                    val weight = 41 * 4 + witnessSize
                    InputSizeResult(weight / 4.0, true)
                }

                // P2WSH (Native SegWit multisig)
                Script.isPay2wsh(parsedScript) -> calculateP2wshVBytes(input.witnessScript, input.partialSigs)

                // P2SH (Nested SegWit: P2SH-P2WPKH or P2SH-P2WSH)
                Script.isPay2sh(parsedScript) -> calculateP2shWrappedVBytes(input)

                else -> InputSizeResult(68.0, true)
            }
        } catch (_: Exception) {
            InputSizeResult(68.0, true)
        }
    }

    /**
     * Estimates vBytes for a partially signed legacy input (P2PKH or P2SH bare multisig).
     * Uses actual signature sizes from partialSigs when present.
     */
    private fun calculatePartialNonWitnessVBytes(input: Input.NonWitnessInput.PartiallySignedNonWitnessInput): InputSizeResult {
        val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: return InputSizeResult(148.0, false)

        return try {
            val parsedScript = Script.parse(scriptPubKey)

            when {
                // P2PKH (Legacy single-sig)
                Script.isPay2pkh(parsedScript) -> {
                    val sigSize = input.partialSigs.values.firstOrNull()?.size() ?: ESTIMATED_ECDSA_SIG_SIZE
                    // scriptSig: push_prefix(1) + sig + push_prefix(1) + pubkey(33)
                    val scriptSigSize = 1 + sigSize + 1 + 33
                    val totalSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
                    InputSizeResult(totalSize.toDouble(), false)
                }

                // P2SH (Legacy bare multisig)
                Script.isPay2sh(parsedScript) -> calculateP2shBareMultisigVBytes(input.redeemScript, input.partialSigs)

                else -> InputSizeResult(148.0, false)
            }
        } catch (_: Exception) {
            InputSizeResult(148.0, false)
        }
    }

    // ---- P2WSH multisig ----

    /**
     * Calculates vBytes for P2WSH (Native SegWit multisig) inputs.
     * Uses exact witness script size and actual signature sizes when available.
     */
    private fun calculateP2wshVBytes(
        witnessScript: List<ScriptElt>?,
        partialSigs: Map<PublicKey, ByteVector>
    ): InputSizeResult {
        if (witnessScript != null) {
            val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(witnessScript)
            if (isMultisig) {
                val witnessScriptSize = Script.write(witnessScript).size
                val witnessSize = calculateMultisigWitnessSize(m, partialSigs, witnessScriptSize)
                val baseWeight = 41 * 4
                return InputSizeResult((baseWeight + witnessSize) / 4.0, true)
            }
        }
        // Fallback: 2-of-3 estimate
        return InputSizeResult(104.0, true)
    }

    // ---- P2SH variants ----

    /**
     * Calculates vBytes for P2SH-wrapped SegWit inputs (P2SH-P2WPKH or P2SH-P2WSH).
     */
    private fun calculateP2shWrappedVBytes(input: Input.WitnessInput.PartiallySignedWitnessInput): InputSizeResult {
        val redeemScript = input.redeemScript

        if (redeemScript != null) {
            // P2SH-P2WPKH: redeem script is a P2WPKH script (OP_0 <20-byte-hash> = 22 bytes)
            if (redeemScript.size == 2 && Script.isPay2wpkh(redeemScript)) {
                val sigSize = input.partialSigs.values.firstOrNull()?.size() ?: ESTIMATED_ECDSA_SIG_SIZE
                val witnessSize = 1 + 1 + sigSize + 1 + 33
                // scriptSig: push of 22-byte redeem script = 23 bytes
                val baseSize = 36 + 1 + 23 + 4  // 64 bytes
                val weight = 4 * baseSize + witnessSize
                return InputSizeResult(weight / 4.0, true)
            }

            // P2SH-P2WSH: redeem script is a P2WSH script (OP_0 <32-byte-hash> = 34 bytes)
            if (redeemScript.size == 2 && Script.isPay2wsh(redeemScript)) {
                val witnessScript = input.witnessScript
                if (witnessScript != null) {
                    val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(witnessScript)
                    if (isMultisig) {
                        val witnessScriptSize = Script.write(witnessScript).size
                        val witnessSize = calculateMultisigWitnessSize(m, input.partialSigs, witnessScriptSize)
                        // scriptSig: push of 34-byte P2WSH redeem script = 35 bytes
                        val baseWeight = (36 + 1 + 35 + 4) * 4  // 76 * 4 = 304 WU
                        return InputSizeResult((baseWeight + witnessSize) / 4.0, true)
                    }
                }
                return InputSizeResult(140.0, true)
            }
        }

        // Fallback: assume P2SH-P2WPKH
        return InputSizeResult(91.0, true)
    }

    /**
     * Calculates vBytes for P2SH bare multisig (legacy, no SegWit discount).
     * Uses exact redeem script size and actual signature sizes when available.
     */
    private fun calculateP2shBareMultisigVBytes(
        redeemScript: List<ScriptElt>?,
        partialSigs: Map<PublicKey, ByteVector>
    ): InputSizeResult {
        if (redeemScript != null) {
            val (isMultisig, m, _) = PsbtAnalyzer.parseMultisigScript(redeemScript)
            if (isMultisig) {
                val redeemScriptSize = Script.write(redeemScript).size
                val sigSizes = computeSignatureSizes(m, partialSigs)
                // scriptSig: OP_0(1) + m×(push_prefix(1) + sig) + push_prefix + redeemScript
                val scriptSigSize = 1 + sigSizes.sumOf { 1 + it } +
                    scriptPushPrefixSize(redeemScriptSize) + redeemScriptSize
                val totalSize = 36 + varIntSize(scriptSigSize.toLong()) + scriptSigSize + 4
                return InputSizeResult(totalSize.toDouble(), false)
            }
        }
        // Fallback: P2SH-P2WPKH
        return InputSizeResult(91.0, true)
    }

    // ---- Shared helpers ----

    /**
     * Calculates the serialized witness size for a multisig input.
     * Witness: items_count + OP_0(empty) + m×(compact_size + sig) + compact_size + witnessScript
     */
    private fun calculateMultisigWitnessSize(
        m: Int,
        partialSigs: Map<PublicKey, ByteVector>,
        witnessScriptSize: Int
    ): Int {
        val sigSizes = computeSignatureSizes(m, partialSigs)

        var size = 1  // items count varint (always 1 byte for < 253 items)
        size += 1     // OP_0 empty push (compact_size(0) = 1 byte)
        for (sigSize in sigSizes) {
            size += varIntSize(sigSize.toLong()) + sigSize
        }
        size += varIntSize(witnessScriptSize.toLong()) + witnessScriptSize
        return size
    }

    /**
     * Builds a list of m signature sizes: uses actual sizes from partialSigs first,
     * then fills remaining slots with estimated sizes.
     */
    private fun computeSignatureSizes(m: Int, partialSigs: Map<PublicKey, ByteVector>): List<Int> {
        val existingSizes = partialSigs.values.take(m).map { it.size() }
        val remaining = (m - existingSizes.size).coerceAtLeast(0)
        return existingSizes + List(remaining) { ESTIMATED_ECDSA_SIG_SIZE }
    }

    /**
     * Calculates output vBytes from the actual scriptPubKey size.
     * Output size = 8 (amount) + varint(scriptLen) + scriptPubKey
     */
    private fun calculateOutputVBytes(txOut: TxOut): Double {
        val scriptLen = txOut.publicKeyScript.size()
        return (8 + varIntSize(scriptLen.toLong()) + scriptLen).toDouble()
    }

    /**
     * Returns the size of a Bitcoin variable-length integer encoding.
     */
    private fun varIntSize(value: Long): Int {
        return when {
            value < 0xFD -> 1
            value <= 0xFFFF -> 3
            value <= 0xFFFFFFFFL -> 5
            else -> 9
        }
    }

    /**
     * Returns the push prefix size for a data push in Bitcoin script.
     * Different from varint: uses OP_PUSHDATA1/2/4 for data > 75 bytes.
     */
    private fun scriptPushPrefixSize(dataLen: Int): Int {
        return when {
            dataLen <= 75 -> 1      // Single opcode encodes both push and length
            dataLen <= 255 -> 2     // OP_PUSHDATA1 + 1-byte length
            dataLen <= 65535 -> 3   // OP_PUSHDATA2 + 2-byte length
            else -> 5               // OP_PUSHDATA4 + 4-byte length
        }
    }
}
