package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import kotlin.jvm.JvmField
import kotlin.jvm.JvmStatic

/**
 * A partially signed bitcoin transaction: see https://github.com/bitcoin/bips/blob/master/bip-0174.mediawiki.
 *
 * @param global global psbt data containing the transaction to be signed.
 * @param inputs signing data for each input of the transaction to be signed (order matches the unsigned tx).
 * @param outputs signing data for each output of the transaction to be signed (order matches the unsigned tx).
 */
@Suppress("unused")
data class Psbt(@JvmField val global: Global, @JvmField val inputs: List<Input>, @JvmField val outputs: List<Output>) {

    init {
        require(global.tx.txIn.size == inputs.size) { "there must be one partially signed input per input of the unsigned tx" }
        require(global.tx.txOut.size == outputs.size) { "there must be one partially signed output per output of the unsigned tx" }
    }

    /** Return the input at the given index, or `null` if the index is out of bounds. */
    fun getInput(inputIndex: Int): Input? =
        if (inputIndex in inputs.indices) inputs[inputIndex] else null

    /** Return the input that spends the given outpoint, or `null` if no such input exists. */
    fun getInput(outPoint: OutPoint): Input? {
        val idx = global.tx.txIn.indexOfFirst { it.outPoint == outPoint }
        return if (idx >= 0) inputs[idx] else null
    }

    /**
     * Implements the PSBT signer role: sign a given input.
     * The caller needs to carefully verify that it wants to spend that input, and that the unsigned transaction matches
     * what it expects.
     *
     * @param priv private key used to sign the input.
     * @param outPoint input that should be signed.
     * @return the psbt with a partial signature added (other inputs will not be modified).
     */
    fun sign(priv: PrivateKey, outPoint: OutPoint): Either<UpdateFailure, SignPsbtResult> {
        val inputIndex = global.tx.txIn.indexOfFirst { it.outPoint == outPoint }
        if (inputIndex < 0) return Either.Left(UpdateFailure.InvalidInput("psbt transaction does not spend the provided outpoint"))
        return sign(priv, inputIndex)
    }

    /**
     * Implements the PSBT signer role: sign a given input.
     * The caller needs to carefully verify that it wants to spend that input, and that the unsigned transaction matches
     * what it expects.
     *
     * @param priv private key used to sign the input.
     * @param inputIndex index of the input that should be signed.
     * @return the psbt with a partial signature added (other inputs will not be modified).
     */
    fun sign(priv: PrivateKey, inputIndex: Int): Either<UpdateFailure, SignPsbtResult> {
        if (inputIndex >= inputs.size) return Either.Left(UpdateFailure.InvalidInput("input index must exist in the input tx"))
        val input = inputs[inputIndex]
        return sign(priv, inputIndex, input, global).map { SignPsbtResult(this.copy(inputs = inputs.updated(inputIndex, it.first)), it.second) }
    }

    private fun sign(priv: PrivateKey, inputIndex: Int, input: Input, global: Global): Either<UpdateFailure, Pair<Input, ByteVector>> {
        val txIn = global.tx.txIn[inputIndex]
        return when (input) {
            is Input.PartiallySignedInputWithoutUtxo -> Either.Left(UpdateFailure.CannotSignInput(inputIndex, "cannot sign: input hasn't been updated with utxo data"))
            is Input.WitnessInput.PartiallySignedWitnessInput -> {
                if (input.nonWitnessUtxo != null && input.nonWitnessUtxo.txid != txIn.outPoint.txid) {
                    Either.Left(UpdateFailure.InvalidNonWitnessUtxo("non-witness utxo does not match unsigned tx input"))
                } else if (input.nonWitnessUtxo != null && input.nonWitnessUtxo.txOut.size <= txIn.outPoint.index) {
                    Either.Left(UpdateFailure.InvalidNonWitnessUtxo("non-witness utxo index out of bounds"))
                } else if (!Script.isNativeWitnessScript(input.txOut.publicKeyScript) && !Script.isPayToScript(input.txOut.publicKeyScript.toByteArray())) {
                    Either.Left(UpdateFailure.InvalidWitnessUtxo("witness utxo must use native segwit or P2SH embedded segwit"))
                } else {
                    signWitness(priv, inputIndex, input, global)
                }
            }
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                if (input.inputTx.txid != txIn.outPoint.txid) {
                    Either.Left(UpdateFailure.InvalidNonWitnessUtxo("non-witness utxo does not match unsigned tx input"))
                } else if (input.inputTx.txOut.size <= txIn.outPoint.index) {
                    Either.Left(UpdateFailure.InvalidNonWitnessUtxo("non-witness utxo index out of bounds"))
                } else {
                    signNonWitness(priv, inputIndex, input, global)
                }
            }
            is Input.FinalizedInputWithoutUtxo -> Either.Left(UpdateFailure.CannotSignInput(inputIndex, "cannot sign: input has already been finalized"))
            is Input.WitnessInput.FinalizedWitnessInput -> Either.Left(UpdateFailure.CannotSignInput(inputIndex, "cannot sign: input has already been finalized"))
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> Either.Left(UpdateFailure.CannotSignInput(inputIndex, "cannot sign: input has already been finalized"))

        }
    }

    private fun signNonWitness(priv: PrivateKey, inputIndex: Int, input: Input.NonWitnessInput.PartiallySignedNonWitnessInput, global: Global): Either<UpdateFailure, Pair<Input.NonWitnessInput.PartiallySignedNonWitnessInput, ByteVector>> {
        val txIn = global.tx.txIn[inputIndex]
        val redeemScript = when (input.redeemScript) {
            null -> runCatching {
                Script.parse(input.inputTx.txOut[txIn.outPoint.index.toInt()].publicKeyScript)
            }.getOrElse {
                return Either.Left(UpdateFailure.InvalidNonWitnessUtxo("failed to parse redeem script"))
            }
            else -> {
                // If a redeem script is provided in the partially signed input, the utxo must be a p2sh for that script.
                val p2sh = Script.write(Script.pay2sh(input.redeemScript))
                if (!input.inputTx.txOut[txIn.outPoint.index.toInt()].publicKeyScript.contentEquals(p2sh)) {
                    return Either.Left(UpdateFailure.InvalidNonWitnessUtxo("redeem script does not match non-witness utxo scriptPubKey"))
                } else {
                    input.redeemScript
                }
            }
        }
        val sig = ByteVector(Transaction.signInput(global.tx, inputIndex, redeemScript, input.sighashType ?: SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_BASE, priv))
        return Either.Right(Pair(input.copy(partialSigs = input.partialSigs + (priv.publicKey() to sig)), sig))
    }

    private fun signWitness(priv: PrivateKey, inputIndex: Int, input: Input.WitnessInput.PartiallySignedWitnessInput, global: Global): Either<UpdateFailure, Pair<Input.WitnessInput.PartiallySignedWitnessInput, ByteVector>> {
        val pubkeyScript = runCatching {
            Script.parse(input.txOut.publicKeyScript)
        }.getOrElse {
            return Either.Left(UpdateFailure.InvalidWitnessUtxo("failed to parse pubkeyScript"))
        }
        // BIP-143 P2WPKH script code is `OP_DUP OP_HASH160 <pubKeyHash> OP_EQUALVERIFY OP_CHECKSIG`
        // (i.e. pay2pkh of the pubkey hash from the witness program). PSBT does NOT carry a
        // witnessScript for P2WPKH, so we derive the signing script inline rather than relying on
        // the caller to splice one in temporarily.
        fun signP2wpkh(program: List<ScriptElt>): Either<UpdateFailure, Pair<Input.WitnessInput.PartiallySignedWitnessInput, ByteVector>> {
            val pubkeyHash = (program[1] as OP_PUSHDATA).data.toByteArray()
            val signingScript = Script.pay2pkh(pubkeyHash)
            val sig = ByteVector(Transaction.signInput(global.tx, inputIndex, signingScript, input.sighashType ?: SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_WITNESS_V0, priv))
            return Either.Right(Pair(input.copy(partialSigs = input.partialSigs + (priv.publicKey() to sig)), sig))
        }

        return when {
            Script.isPay2wpkh(pubkeyScript) -> signP2wpkh(pubkeyScript)
            Script.isPay2wsh(pubkeyScript) -> when {
                input.witnessScript == null -> Either.Left(UpdateFailure.InvalidWitnessUtxo("missing witness script"))
                pubkeyScript != Script.pay2wsh(input.witnessScript) -> Either.Left(UpdateFailure.InvalidWitnessUtxo("witness script does not match redeemScript or scriptPubKey"))
                else -> {
                    val sig = ByteVector(Transaction.signInput(global.tx, inputIndex, input.witnessScript, input.sighashType ?: SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_WITNESS_V0, priv))
                    Either.Right(Pair(input.copy(partialSigs = input.partialSigs + (priv.publicKey() to sig)), sig))
                }
            }
            Script.isPay2tr(pubkeyScript) -> when (input.taprootInternalKey) {
                null -> Either.Left(UpdateFailure.InvalidWitnessUtxo("missing taproot internal key"))
                else -> {
                    // When spending taproot inputs, we include *all* of the transaction's inputs in the signed hash.
                    val spentOutputs = this.inputs.mapIndexedNotNull { idx, txIn -> txIn.witnessUtxo ?: txIn.nonWitnessUtxo?.txOut?.get(this.global.tx.txIn[idx].outPoint.index.toInt()) }
                    if (spentOutputs.size != this.inputs.size) {
                        Either.Left(UpdateFailure.InvalidInput("missing txOut for one of our inputs"))
                    } else {
                        val sig = Transaction.signInputTaprootKeyPath(priv, global.tx, inputIndex, spentOutputs, input.sighashType ?: SigHash.SIGHASH_DEFAULT, null)
                        val sigAndSighashType = input.sighashType?.let { sig.concat(it.toByte()) } ?: sig
                        Either.Right(Pair(input.copy(taprootKeySignature = sigAndSighashType), sigAndSighashType))
                    }
                }
            }
            Script.isPay2sh(pubkeyScript) -> when {
                input.redeemScript == null -> Either.Left(UpdateFailure.InvalidWitnessUtxo("missing redeem script"))
                pubkeyScript != Script.pay2sh(input.redeemScript) -> Either.Left(UpdateFailure.InvalidWitnessUtxo("redeem script does not match witness utxo scriptPubKey"))
                Script.isPay2wpkh(input.redeemScript) -> signP2wpkh(input.redeemScript)
                Script.isPay2wsh(input.redeemScript) -> when {
                    input.witnessScript == null -> Either.Left(UpdateFailure.InvalidWitnessUtxo("missing witness script"))
                    input.redeemScript != Script.pay2wsh(input.witnessScript) -> Either.Left(UpdateFailure.InvalidWitnessUtxo("witness script does not match redeemScript or scriptPubKey"))
                    else -> {
                        val sig = ByteVector(Transaction.signInput(global.tx, inputIndex, input.witnessScript, input.sighashType ?: SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_WITNESS_V0, priv))
                        Either.Right(Pair(input.copy(partialSigs = input.partialSigs + (priv.publicKey() to sig)), sig))
                    }
                }
                else -> Either.Left(UpdateFailure.InvalidWitnessUtxo("redeem script is not a supported segwit witness program"))
            }
            else -> {
                val script = input.witnessScript ?: input.redeemScript ?: pubkeyScript
                val sig = ByteVector(Transaction.signInput(global.tx, inputIndex, script, input.sighashType ?: SigHash.SIGHASH_ALL, input.amount, SigVersion.SIGVERSION_WITNESS_V0, priv))
                Either.Right(Pair(input.copy(partialSigs = input.partialSigs + (priv.publicKey() to sig)), sig))
            }
        }
    }

    /**
     * Implements the PSBT finalizer role: finalizes a given segwit input.
     * This will clear all fields from the input except the utxo, scriptSig, scriptWitness and unknown entries.
     *
     * @param outPoint input that should be finalized.
     * @param scriptWitness witness script.
     * @return a psbt with the given input finalized.
     */
    fun finalizeWitnessInput(outPoint: OutPoint, scriptWitness: ScriptWitness): Either<UpdateFailure, Psbt> {
        val inputIndex = global.tx.txIn.indexOfFirst { it.outPoint == outPoint }
        if (inputIndex < 0) return Either.Left(UpdateFailure.InvalidInput("psbt transaction does not spend the provided outpoint"))
        return finalizeWitnessInput(inputIndex, scriptWitness)
    }

    /**
     * Implements the PSBT finalizer role: finalizes a given segwit input.
     * This will clear all fields from the input except the utxo, scriptSig, scriptWitness and unknown entries.
     *
     * @param inputIndex index of the input that should be finalized.
     * @param scriptWitness witness script.
     * @return a psbt with the given input finalized.
     */
    fun finalizeWitnessInput(inputIndex: Int, scriptWitness: ScriptWitness): Either<UpdateFailure, Psbt> {
        if (inputIndex >= inputs.size) return Either.Left(UpdateFailure.InvalidInput("input index must exist in the input tx"))
        return when (val input = inputs[inputIndex]) {
            is Input.PartiallySignedInputWithoutUtxo -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, "cannot finalize: input is missing utxo details"))
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, "cannot finalize: input is a non-segwit input"))
            is Input.WitnessInput.PartiallySignedWitnessInput -> {
                val scriptSig = input.redeemScript?.let { script -> listOf(OP_PUSHDATA(Script.write(script))) } // p2sh-embedded segwit
                val finalizedInput = Input.WitnessInput.FinalizedWitnessInput(input.txOut, input.nonWitnessUtxo, scriptWitness, scriptSig, input.ripemd160, input.sha256, input.hash160, input.hash256, input.unknown)
                Either.Right(this.copy(inputs = this.inputs.updated(inputIndex, finalizedInput)))
            }
            else -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, ("cannot finalize: input has already been finalized")))
        }
    }

    /**
     * Implements the PSBT finalizer role: finalizes a given non-segwit input.
     * This will clear all fields from the input except the utxo, scriptSig and unknown entries.
     *
     * @param outPoint input that should be finalized.
     * @param scriptSig signature script.
     * @return a psbt with the given input finalized.
     */
    fun finalizeNonWitnessInput(outPoint: OutPoint, scriptSig: List<ScriptElt>): Either<UpdateFailure, Psbt> {
        val inputIndex = global.tx.txIn.indexOfFirst { it.outPoint == outPoint }
        if (inputIndex < 0) return Either.Left(UpdateFailure.InvalidInput("psbt transaction does not spend the provided outpoint"))
        return finalizeNonWitnessInput(inputIndex, scriptSig)
    }

    /**
     * Implements the PSBT finalizer role: finalizes a given non-segwit input.
     * This will clear all fields from the input except the utxo, scriptSig and unknown entries.
     *
     * @param inputIndex index of the input that should be finalized.
     * @param scriptSig signature script.
     * @return a psbt with the given input finalized.
     */
    fun finalizeNonWitnessInput(inputIndex: Int, scriptSig: List<ScriptElt>): Either<UpdateFailure, Psbt> {
        if (inputIndex >= inputs.size) return Either.Left(UpdateFailure.InvalidInput("input index must exist in the input tx"))
        return when (val input = inputs[inputIndex]) {
            is Input.PartiallySignedInputWithoutUtxo -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, "cannot finalize: input is missing utxo details"))
            is Input.WitnessInput.PartiallySignedWitnessInput -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, "cannot finalize: input is a segwit input"))
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                val finalizedInput = Input.NonWitnessInput.FinalizedNonWitnessInput(input.inputTx, input.outputIndex, scriptSig, input.ripemd160, input.sha256, input.hash160, input.hash256, input.unknown)
                Either.Right(this.copy(inputs = this.inputs.updated(inputIndex, finalizedInput)))
            }
            else -> Either.Left(UpdateFailure.CannotFinalizeInput(inputIndex, ("cannot finalize: input has already been finalized")))
        }
    }

    /**
     * Implements the PSBT extractor role: extracts a valid transaction from the psbt data.
     *
     * @return a fully signed, ready-to-broadcast transaction.
     */
    fun extract(): Either<UpdateFailure, Transaction> {
        val (finalTxsIn, utxos) = global.tx.txIn.zip(inputs).map { (txIn, input) ->
            val finalTxIn = txIn.copy(
                witness = input.scriptWitness ?: ScriptWitness.empty,
                signatureScript = input.scriptSig?.let { ByteVector(Script.write(it)) } ?: ByteVector.empty
            )
            val utxo = when (input) {
                is Input.NonWitnessInput.FinalizedNonWitnessInput -> {
                    if (input.inputTx.txid != txIn.outPoint.txid) return Either.Left(UpdateFailure.CannotExtractTx("non-witness utxo does not match unsigned tx input"))
                    if (input.inputTx.txOut.size <= txIn.outPoint.index) return Either.Left(UpdateFailure.CannotExtractTx("non-witness utxo index out of bounds"))
                    input.inputTx.txOut[txIn.outPoint.index.toInt()]
                }
                is Input.WitnessInput.FinalizedWitnessInput -> input.txOut
                else -> return Either.Left(UpdateFailure.CannotExtractTx("some utxos are missing"))
            }
            Pair(finalTxIn, txIn.outPoint to utxo)
        }.unzip()
        val finalTx = global.tx.copy(txIn = finalTxsIn)
        return try {
            Transaction.correctlySpends(finalTx, utxos.toMap(), ScriptFlags.STANDARD_SCRIPT_VERIFY_FLAGS)
            Either.Right(finalTx)
        } catch (_: Exception) {
            Either.Left(UpdateFailure.CannotExtractTx("extracted transaction doesn't pass standard script validation"))
        }
    }

    /**
     * Compute the fees paid by the PSBT.
     * Note that if some inputs have not been updated yet, the fee cannot be computed.
     */
    fun computeFees(): Satoshi? {
        val inputAmounts = inputs.map { input ->
            when (input) {
                is Input.WitnessInput -> input.amount
                is Input.NonWitnessInput -> input.amount
                else -> null
            }
        }
        return when {
            inputAmounts.any { it == null } -> null
            else -> {
                val amountOut = global.tx.txOut.sumOf { it.amount.sat }.toSatoshi()
                val amountIn = inputAmounts.filterNotNull().sumOf { it.sat }.toSatoshi()
                amountIn - amountOut
            }
        }
    }

    @Suppress("ConstPropertyName")
    companion object {

        /** Only version 0 is supported for now. */
        const val Version: Long = 0

        /**
         * Implements the PSBT combiner role: combines multiple psbts for the same unsigned transaction.
         *
         * @param psbts partially signed bitcoin transactions to combine.
         * @return a psbt that contains data from all the input psbts.
         */
        @JvmStatic
        fun combine(vararg psbts: Psbt): Either<UpdateFailure, Psbt> {
            return when {
                psbts.map { it.global.tx.txid }.toSet().size != 1 -> Either.Left(UpdateFailure.CannotCombine("cannot combine psbts for distinct transactions"))
                psbts.map { it.inputs.size }.toSet() != setOf(psbts[0].global.tx.txIn.size) -> Either.Left(UpdateFailure.CannotCombine("some psbts have an invalid number of inputs"))
                psbts.map { it.outputs.size }.toSet() != setOf(psbts[0].global.tx.txOut.size) -> Either.Left(UpdateFailure.CannotCombine("some psbts have an invalid number of outputs"))
                else -> {
                    val global = psbts[0].global.copy(
                        unknown = combineUnknown(psbts.map { it.global.unknown }),
                        extendedPublicKeys = combineExtendedPublicKeys(psbts.map { it.global.extendedPublicKeys })
                    )
                    val combined = Psbt(
                        global,
                        global.tx.txIn.indices.map { i -> combineInput(global.tx.txIn[i], psbts.map { it.inputs[i] }) },
                        global.tx.txOut.indices.map { i -> combineOutput(psbts.map { it.outputs[i] }) }
                    )
                    Either.Right(combined)
                }
            }
        }

        private fun combineUnknown(unknowns: List<List<DataEntry>>): List<DataEntry> = unknowns.flatten().associateBy { it.key }.values.toList()

        private fun combineExtendedPublicKeys(keys: List<List<ExtendedPublicKeyWithMaster>>): List<ExtendedPublicKeyWithMaster> = keys.flatten().associateBy { it.extendedPublicKey }.values.toList()

        private fun combineInput(txIn: TxIn, inputs: List<Input>): Input = createInput(
            txIn,
            inputs.firstNotNullOfOrNull { it.nonWitnessUtxo },
            inputs.firstNotNullOfOrNull { it.witnessUtxo },
            inputs.firstNotNullOfOrNull { it.sighashType },
            inputs.flatMap { it.partialSigs.toList() }.toMap(),
            inputs.flatMap { it.derivationPaths.toList() }.toMap(),
            inputs.firstNotNullOfOrNull { it.redeemScript },
            inputs.firstNotNullOfOrNull { it.witnessScript },
            inputs.firstNotNullOfOrNull { it.scriptSig },
            inputs.firstNotNullOfOrNull { it.scriptWitness },
            inputs.flatMap { it.ripemd160 }.toSet(),
            inputs.flatMap { it.sha256 }.toSet(),
            inputs.flatMap { it.hash160 }.toSet(),
            inputs.flatMap { it.hash256 }.toSet(),
            inputs.firstNotNullOfOrNull { it.taprootKeySignature },
            inputs.flatMap { it.taprootDerivationPaths.toList() }.toMap(),
            inputs.firstNotNullOfOrNull { it.taprootInternalKey },
            combineUnknown(inputs.map { it.unknown })
        )

        private fun combineOutput(outputs: List<Output>): Output = createOutput(
            outputs.firstNotNullOfOrNull { it.redeemScript },
            outputs.firstNotNullOfOrNull { it.witnessScript },
            outputs.flatMap { it.derivationPaths.toList() }.toMap(),
            outputs.firstNotNullOfOrNull { it.taprootInternalKey },
            outputs.flatMap { it.taprootDerivationPaths.toList() }.toMap(),
            combineUnknown(outputs.map { it.unknown })
        )

        /**
         * Joins multiple distinct PSBTs with different inputs and outputs into one PSBT with inputs and outputs from all of
         * the PSBTs. No input in any of the PSBTs can be in more than one of the PSBTs.
         *
         * @param psbts partially signed bitcoin transactions to join.
         * @return a psbt that contains data from all the input psbts.
         */
        @JvmStatic
        fun join(vararg psbts: Psbt): Either<UpdateFailure, Psbt> {
            return when {
                psbts.isEmpty() -> Either.Left(UpdateFailure.CannotJoin("no psbt provided"))
                psbts.map { it.global.version }.toSet().size != 1 -> Either.Left(UpdateFailure.CannotJoin("cannot join psbts with different versions"))
                psbts.map { it.global.tx.version }.toSet().size != 1 -> Either.Left(UpdateFailure.CannotJoin("cannot join psbts with different tx versions"))
                psbts.map { it.global.tx.lockTime }.toSet().size != 1 -> Either.Left(UpdateFailure.CannotJoin("cannot join psbts with different tx lockTime"))
                psbts.any { it.global.tx.txIn.size != it.inputs.size || it.global.tx.txOut.size != it.outputs.size } -> Either.Left(UpdateFailure.CannotJoin("some psbts have an invalid number of inputs/outputs"))
                psbts.flatMap { it.global.tx.txIn.map { txIn -> txIn.outPoint } }.toSet().size != psbts.sumOf { it.global.tx.txIn.size } -> Either.Left(UpdateFailure.CannotJoin("cannot join psbts that spend the same input"))
                else -> {
                    val global = psbts[0].global.copy(
                        tx = psbts[0].global.tx.copy(
                            txIn = psbts.flatMap { it.global.tx.txIn },
                            txOut = psbts.flatMap { it.global.tx.txOut }
                        ),
                        extendedPublicKeys = psbts.flatMap { it.global.extendedPublicKeys }.distinct(),
                        unknown = psbts.flatMap { it.global.unknown }.distinct()
                    )
                    Either.Right(psbts[0].copy(
                        global = global,
                        inputs = psbts.flatMap { it.inputs },
                        outputs = psbts.flatMap { it.outputs }
                    ))
                }
            }
        }

        @JvmStatic
        fun write(psbt: Psbt): ByteVector {
            val output = ByteArrayOutput()
            PsbtWriter.write(psbt, output)
            return ByteVector(output.toByteArray())
        }

        @JvmStatic
        fun write(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output): Unit =
            PsbtWriter.write(psbt, out)

        @JvmStatic
        fun read(input: ByteVector): Either<ParseFailure, Psbt> = read(ByteArrayInput(input.toByteArray()))

        @JvmStatic
        fun read(input: ByteArray): Either<ParseFailure, Psbt> = read(ByteArrayInput(input))

        @JvmStatic
        fun read(input: com.gorunjinian.metrovault.lib.bitcoin.io.Input): Either<ParseFailure, Psbt> =
            PsbtReader.read(input)
    }

}

