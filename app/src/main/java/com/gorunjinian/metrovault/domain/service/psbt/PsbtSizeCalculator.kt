package com.gorunjinian.metrovault.domain.service.psbt

import com.gorunjinian.metrovault.lib.bitcoin.*

/**
 * Handles transaction size calculations for PSBT fee estimation.
 */
internal object PsbtSizeCalculator {

    /**
     * Result of input vBytes calculation.
     * @param vBytes The virtual size contribution of the input
     * @param isSegwit Whether this input uses SegWit (affects transaction overhead)
     */
    data class InputSizeResult(val vBytes: Double, val isSegwit: Boolean)

    /**
     * Calculates the virtual size (vBytes) of the transaction.
     * Uses accurate per-input calculation based on script type and multisig configuration.
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

        // Overhead: version(4) + inputCount(1) + outputCount(1) + locktime(4)
        // SegWit marker+flag adds 0.5 vBytes (2 bytes at witness weight)
        val overheadVBytes = if (hasSegwit) 10.5 else 10.0

        return kotlin.math.ceil(overheadVBytes + totalInputVBytes + totalOutputVBytes).toInt()
    }

    /**
     * Calculates accurate input vBytes based on script type and multisig configuration.
     *
     * Formula: vBytes = ceil((base_weight + witness_weight) / 4)
     * - base_weight = non-witness bytes × 4
     * - witness_weight = witness bytes × 1 (SegWit discount)
     *
     * Supports: P2PKH, P2WPKH, P2SH-P2WPKH, P2WSH, P2SH-P2WSH, P2SH bare multisig, P2TR
     */
    fun calculateInputVBytes(input: Input): InputSizeResult {
        val scriptPubKey = PsbtUtils.getInputScriptPubKey(input) ?: return InputSizeResult(68.0, true)

        return try {
            val parsedScript = Script.parse(scriptPubKey)

            when {
                // P2PKH (Legacy single-sig): All data in scriptSig, no witness discount
                // Size: outpoint(36) + scriptSig(1 + 107) + sequence(4) = 148 bytes
                Script.isPay2pkh(parsedScript) -> InputSizeResult(148.0, false)

                // P2WPKH (Native SegWit single-sig): Empty scriptSig, data in witness
                // Base: 41 bytes, Witness: ~108 bytes → vBytes ≈ 68
                Script.isPay2wpkh(parsedScript) -> InputSizeResult(68.0, true)

                // P2TR (Taproot key-path spend): Empty scriptSig, Schnorr sig in witness
                // Base: 41 bytes, Witness: ~66 bytes → vBytes ≈ 58
                Script.isPay2tr(parsedScript) -> InputSizeResult(58.0, true)

                // P2WSH (Native SegWit script): Calculate based on witness script
                Script.isPay2wsh(parsedScript) -> calculateP2wshVBytes(input)

                // P2SH: Could be nested SegWit (P2SH-P2WPKH, P2SH-P2WSH) or bare multisig
                Script.isPay2sh(parsedScript) -> calculateP2shVBytes(input)

                // Default fallback to P2WPKH
                else -> InputSizeResult(68.0, true)
            }
        } catch (_: Exception) {
            InputSizeResult(68.0, true)
        }
    }

    /**
     * Calculates vBytes for P2WSH (Native SegWit multisig) inputs.
     *
     * Formula:
     * - Base: 41 bytes (outpoint + empty scriptSig + sequence) × 4 weight
     * - Witness: 1 (items count) + 1 (OP_0) + m×73 (signatures) + 1 (script len) + witnessScriptLen
     */
    private fun calculateP2wshVBytes(input: Input): InputSizeResult {
        val witnessScript = when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.witnessScript
            else -> null
        }

        if (witnessScript != null) {
            val (isMultisig, m, n) = PsbtAnalyzer.parseMultisigScript(witnessScript)
            if (isMultisig) {
                val baseWeight = 41 * 4  // 164 WU
                val witnessScriptSize = (n * 34) + 3  // n×(33 pubkey + 1 push) + OP_m + OP_n + OP_CHECKMULTISIG
                val witnessWeight = 1 + 1 + (m * 73) + 1 + witnessScriptSize
                return InputSizeResult((baseWeight + witnessWeight) / 4.0, true)
            }
        }

        // Fallback: 2-of-3 estimate ≈ 104 vBytes
        return InputSizeResult(104.0, true)
    }

    /**
     * Calculates vBytes for P2SH inputs.
     * Detects whether it's nested SegWit (P2SH-P2WPKH, P2SH-P2WSH) or bare P2SH multisig.
     *
     * - P2SH-P2WPKH: scriptSig has 23-byte redeem script, witness has sig+pubkey
     * - P2SH-P2WSH: scriptSig has 35-byte redeem script, witness has multisig data
     * - P2SH bare multisig: All data in scriptSig, no witness discount
     */
    private fun calculateP2shVBytes(input: Input): InputSizeResult {
        val redeemScript = when (input) {
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> input.redeemScript
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.redeemScript
            else -> null
        }

        if (redeemScript != null) {
            // Check if redeem script is P2WPKH (nested SegWit single-sig)
            if (redeemScript.size == 2 && Script.isPay2wpkh(redeemScript)) {
                // P2SH-P2WPKH: scriptSig(23) + base(41) = 64 bytes base, witness ~108 bytes
                // vBytes = (64×4 + 108) / 4 = 91
                return InputSizeResult(91.0, true)
            }

            // Check if redeem script is P2WSH (nested SegWit multisig)
            if (redeemScript.size == 2 && Script.isPay2wsh(redeemScript)) {
                // Need to get the actual witness script to determine m-of-n
                val witnessScript = when (input) {
                    is Input.WitnessInput.PartiallySignedWitnessInput -> input.witnessScript
                    else -> null
                }

                if (witnessScript != null) {
                    val (isMultisig, m, n) = PsbtAnalyzer.parseMultisigScript(witnessScript)
                    if (isMultisig) {
                        // P2SH-P2WSH: scriptSig has 35-byte P2WSH redeem script
                        // Base: 41 + 35 = 76 bytes × 4 = 304 WU
                        val baseWeight = 76 * 4
                        val witnessScriptSize = (n * 34) + 3
                        val witnessWeight = 1 + 1 + (m * 73) + 1 + witnessScriptSize
                        return InputSizeResult((baseWeight + witnessWeight) / 4.0, true)
                    }
                }
                // Fallback P2SH-P2WSH 2-of-3: ~140 vBytes
                return InputSizeResult(140.0, true)
            }

            // Check if redeem script is bare multisig (legacy, no SegWit discount)
            val (isMultisig, m, n) = PsbtAnalyzer.parseMultisigScript(redeemScript)
            if (isMultisig) {
                // P2SH bare multisig: Everything in scriptSig, counted at full weight
                // scriptSig: 1 (OP_0) + m×73 (signatures) + 1 (redeemScript len) + redeemScriptLen
                val redeemScriptSize = (n * 34) + 3
                val scriptSigSize = 1 + (m * 73) + 1 + redeemScriptSize
                // Total: outpoint(36) + scriptSigLen(1-3) + scriptSig + sequence(4)
                val totalBytes = 36 + varIntSize(scriptSigSize) + scriptSigSize + 4
                return InputSizeResult(totalBytes.toDouble(), false)
            }
        }

        // Fallback: assume P2SH-P2WPKH (most common P2SH type now)
        return InputSizeResult(91.0, true)
    }

    /**
     * Calculates output vBytes based on script type.
     * Output size = 8 (value) + 1 (scriptPubKey length) + scriptPubKey
     */
    private fun calculateOutputVBytes(txOut: TxOut): Double {
        return try {
            val parsedScript = Script.parse(txOut.publicKeyScript)
            val scriptLen = when {
                Script.isPay2pkh(parsedScript) -> 25   // OP_DUP OP_HASH160 <20> OP_EQUALVERIFY OP_CHECKSIG
                Script.isPay2sh(parsedScript) -> 23    // OP_HASH160 <20> OP_EQUAL
                Script.isPay2wpkh(parsedScript) -> 22  // OP_0 <20>
                Script.isPay2wsh(parsedScript) -> 34   // OP_0 <32>
                Script.isPay2tr(parsedScript) -> 34    // OP_1 <32>
                else -> 22  // Default to P2WPKH
            }
            (8 + 1 + scriptLen).toDouble()
        } catch (_: Exception) {
            31.0  // Default P2WPKH output size
        }
    }

    /**
     * Returns the size of a variable-length integer encoding.
     */
    private fun varIntSize(value: Int): Int {
        return when {
            value < 0xFD -> 1
            value <= 0xFFFF -> 3
            else -> 9
        }
    }
}
