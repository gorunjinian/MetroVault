package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import java.util.Base64

/**
 * BIP-322 generic signed messages — the "simple" variant, single-sig P2WPKH and P2TR (key path).
 *
 * Taproot addresses cannot use the legacy ECDSA message-signing schemes ([MessageSigning]'s
 * Electrum/BIP-137): there is no pubkey-recovery convention that binds to a taproot output key.
 * Wallets (Sparrow, the BIP-322 reference) instead sign a virtual transaction: `to_spend` pays the
 * address an output committing to the tagged message hash, and `to_sign` spends it; the "simple"
 * signature is the base64 of `to_sign`'s input witness.
 *
 * This matters for silent payments: every SP receive address is P2TR, and Sparrow signs/verifies
 * messages for them via BIP-322 (the one-off key is `d = b_spend + t_k`). The transaction
 * construction here is pinned to drongo's `Bip322.java` and validated against the official
 * BIP-322 / drongo test vectors, so signatures interoperate with Sparrow in both directions.
 */
object Bip322 {
    /** Prefix Sparrow puts on BIP-322 simple signatures; the bare base64 form is also accepted. */
    const val SIMPLE_PREFIX = "smp"

    private const val MESSAGE_TAG = "BIP0322-signed-message"

    /** `taggedHash("BIP0322-signed-message", message)` — committed to in [toSpend]'s scriptSig. */
    fun messageHash(message: String): ByteVector32 = Crypto.taggedHash(message.encodeToByteArray(), MESSAGE_TAG)

    /**
     * The virtual funding transaction: version 0, locktime 0, one input spending the null outpoint
     * with `scriptSig = OP_0 <messageHash>`, one zero-value output paying [outputScript].
     */
    fun toSpend(outputScript: ByteVector, message: String): Transaction {
        val scriptSig = Script.write(listOf(OP_0, OP_PUSHDATA(messageHash(message).toByteArray()))).byteVector()
        return Transaction(
            version = 0,
            txIn = listOf(TxIn(OutPoint(TxId(ByteVector32.Zeroes), 0xffffffffL), scriptSig, 0L)),
            txOut = listOf(TxOut(Satoshi(0), outputScript)),
            lockTime = 0
        )
    }

    /**
     * The virtual transaction that is actually signed: version 0, locktime 0, one input spending
     * [toSpend]'s output 0 (sequence 0), one zero-value `OP_RETURN` output.
     */
    fun toSign(toSpend: Transaction): Transaction = Transaction(
        version = 0,
        txIn = listOf(TxIn(OutPoint(toSpend.txid, 0L), ByteVector.empty, 0L)),
        txOut = listOf(TxOut(Satoshi(0), Script.write(listOf(OP_RETURN)).byteVector())),
        lockTime = 0
    )

    /** Encode a `to_sign` input witness as a `smp`-prefixed BIP-322 simple signature string. */
    fun encodeSimpleSignature(witness: ScriptWitness): String =
        SIMPLE_PREFIX + Base64.getEncoder().encodeToString(ScriptWitness.write(witness))

    /** `PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE` (0x09): the message a BIP-322 PSBT commits to. */
    const val PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE: Byte = 0x09

    /** A validated BIP-322 message-signing request. */
    data class MessagePsbtRequest(val address: String, val message: String)

    /**
     * Recognize and validate a BIP-322 message-signing PSBT (drongo `getBip322Psbt[Sp]`).
     *
     * Returns null when [psbt] carries no `PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE` — i.e. it's a
     * regular transaction, not a message request. When the field is present, the PSBT must be a
     * faithful BIP-322 virtual pair: one input spending output 0 of the `to_spend` transaction
     * *recomputed here from the address and message*, and a single zero-value `OP_RETURN` output.
     * That recomputation is the security check — it proves the input cannot spend anything real
     * and that the signature will commit to exactly the declared address and message.
     *
     * @throws IllegalArgumentException if the field is present but the PSBT is not a valid request.
     */
    fun parseMessagePsbt(psbt: Psbt, chainHash: BlockHash): MessagePsbtRequest? {
        val messageEntry = psbt.global.unknown.firstOrNull {
            it.key.size() == 1 && it.key[0] == PSBT_GLOBAL_GENERIC_SIGNED_MESSAGE
        } ?: return null
        val message = String(messageEntry.value.toByteArray(), Charsets.UTF_8)

        val tx = psbt.global.tx
        require(tx.txIn.size == 1 && tx.txOut.size == 1) { "A BIP-322 message PSBT must have exactly one input and one output" }
        require(tx.txOut[0].amount == Satoshi(0) && tx.txOut[0].publicKeyScript == Script.write(listOf(OP_RETURN)).byteVector()) {
            "A BIP-322 message PSBT must pay only a zero-value OP_RETURN output"
        }
        val witnessUtxo = psbt.inputs[0].witnessUtxo
            ?: throw IllegalArgumentException("A BIP-322 message PSBT must carry the to_spend witness utxo")
        require(witnessUtxo.amount == Satoshi(0)) { "The BIP-322 to_spend output must be zero-value" }

        val address = (Bitcoin.addressFromPublicKeyScript(chainHash, witnessUtxo.publicKeyScript.toByteArray()) as? Either.Right)?.value
            ?: throw IllegalArgumentException("The BIP-322 message PSBT pays an unrecognized address")

        // The input must spend exactly the virtual to_spend for this (address, message) pair.
        val expectedToSpend = toSpend(witnessUtxo.publicKeyScript, message)
        require(tx.txIn[0].outPoint == OutPoint(expectedToSpend.txid, 0L)) {
            "The BIP-322 message PSBT input does not commit to the declared address and message"
        }

        return MessagePsbtRequest(address, message)
    }

    /**
     * Extract the BIP-322 simple signature from a *signed* message PSBT: the taproot key-path
     * signature (P2TR, including silent-payment addresses) or the partial signature + pubkey
     * (P2WPKH), encoded as the `to_sign` input witness. Null if the input carries no signature.
     */
    fun signatureFromSignedMessagePsbt(psbt: Psbt): String? {
        val input = psbt.inputs.firstOrNull() ?: return null
        input.taprootKeySignature?.let { return encodeSimpleSignature(ScriptWitness(listOf(it))) }
        val (publicKey, signature) = input.partialSigs.entries.firstOrNull() ?: return null
        return encodeSimpleSignature(ScriptWitness(listOf(signature, publicKey.value)))
    }

    /**
     * Sign [message] for [address] with [privateKey], producing a BIP-322 simple signature.
     *
     * Supports single-sig P2WPKH and P2TR (BIP-86 key path — [privateKey] is the *internal* key,
     * tweaked here exactly as drongo's `getOutputKey` does). The address must be the one derived
     * from [privateKey]; a mismatch throws rather than producing a signature nobody can verify.
     *
     * @throws IllegalArgumentException for unsupported address types or a key/address mismatch.
     */
    fun signSimple(address: String, message: String, privateKey: PrivateKey, chainHash: BlockHash): String {
        val script = (Bitcoin.addressToPublicKeyScript(chainHash, address) as? Either.Right)?.value
            ?: throw IllegalArgumentException("Invalid address")
        val scriptBytes = Script.write(script).byteVector()
        val toSpendTx = toSpend(scriptBytes, message)
        val toSignTx = toSign(toSpendTx)

        val witness = when {
            Script.isPay2tr(script) -> {
                val outputKey = Script.pay2trOutputKey(scriptBytes)
                val (derivedKey, _) = privateKey.xOnlyPublicKey().outputKey(Crypto.TaprootTweak.NoScriptTweak)
                require(outputKey == derivedKey) { "Address does not match the BIP-86 key for this private key" }
                val sighash = toSignTx.hashForSigningTaprootKeyPath(0, listOf(toSpendTx.txOut[0]), SigHash.SIGHASH_ALL)
                val sig = Crypto.signSchnorr(sighash, privateKey, Crypto.TaprootTweak.NoScriptTweak)
                ScriptWitness(listOf(sig.concat(SigHash.SIGHASH_ALL.toByte())))
            }
            Script.isPay2wpkh(script) -> {
                val publicKey = privateKey.publicKey()
                val program = (script[1] as OP_PUSHDATA).data
                require(program == ByteVector(publicKey.hash160())) { "Address does not match this private key" }
                // Segwit v0 sighash uses the P2PKH script of the key as the scriptCode (BIP-143).
                val scriptCode = Script.pay2pkh(publicKey)
                val sighash = toSignTx.hashForSigning(0, scriptCode, SigHash.SIGHASH_ALL, Satoshi(0), SigVersion.SIGVERSION_WITNESS_V0)
                val derSig = Crypto.compact2der(Crypto.sign(sighash, privateKey)).concat(SigHash.SIGHASH_ALL.toByte())
                ScriptWitness(listOf(derSig, publicKey.value))
            }
            else -> throw IllegalArgumentException("BIP-322 signing supports Native SegWit and Taproot addresses only")
        }

        val encoded = encodeSimpleSignature(witness)
        check(verifySimple(address, message, encoded, chainHash)) { "Generated BIP-322 signature failed self-verification" }
        return encoded
    }

    /**
     * Verify a BIP-322 simple signature for a single-sig P2WPKH or P2TR address (key-path spend —
     * the forms Sparrow produces, including for silent-payment receive addresses). Returns false
     * for any malformed input rather than throwing.
     */
    fun verifySimple(address: String, message: String, signatureBase64: String, chainHash: BlockHash): Boolean {
        val trimmed = signatureBase64.trim().removePrefix(SIMPLE_PREFIX)
        val witness = try {
            ScriptWitness.read(Base64.getDecoder().decode(trimmed))
        } catch (_: Exception) {
            return false
        }

        val script = (Bitcoin.addressToPublicKeyScript(chainHash, address) as? Either.Right)?.value ?: return false
        val scriptBytes = Script.write(script).byteVector()
        val toSpendTx = toSpend(scriptBytes, message)
        val toSignTx = toSign(toSpendTx)

        return when {
            Script.isPay2tr(script) -> verifyP2trWitness(witness, scriptBytes, toSpendTx, toSignTx)
            Script.isPay2wpkh(script) -> verifyP2wpkhWitness(witness, script, toSpendTx, toSignTx)
            else -> false
        }
    }

    private fun verifyP2trWitness(witness: ScriptWitness, scriptBytes: ByteVector, toSpendTx: Transaction, toSignTx: Transaction): Boolean {
        // Key-path spend: the witness is exactly one Schnorr signature (64B, or 65B with sighash).
        val sig = witness.stack.singleOrNull() ?: return false
        val sighashType = when (sig.size()) {
            64 -> SigHash.SIGHASH_DEFAULT
            // A 65-byte signature with sighash byte 0x00 is invalid per BIP-341.
            65 -> (sig[64].toInt() and 0xff).takeIf { it != 0 } ?: return false
            else -> return false
        }
        val outputKey = Script.pay2trOutputKey(scriptBytes) ?: return false
        val sighash = try {
            toSignTx.hashForSigningTaprootKeyPath(0, listOf(toSpendTx.txOut[0]), sighashType)
        } catch (_: Exception) {
            return false
        }
        return Crypto.verifySignatureSchnorr(sighash, sig.take(64), outputKey)
    }

    private fun verifyP2wpkhWitness(witness: ScriptWitness, script: List<ScriptElt>, toSpendTx: Transaction, toSignTx: Transaction): Boolean {
        // P2WPKH spend: witness is [DER signature ‖ sighash byte, compressed pubkey].
        if (witness.stack.size != 2) return false
        val (sigWithType, pubKeyBytes) = witness.stack
        if (sigWithType.size() < 9 || pubKeyBytes.size() != 33) return false
        val publicKey = PublicKey(pubKeyBytes.toByteArray())
        if (!publicKey.isValid()) return false
        val program = (script.getOrNull(1) as? OP_PUSHDATA)?.data ?: return false
        if (program != ByteVector(publicKey.hash160())) return false

        val sighashType = sigWithType[sigWithType.size() - 1].toInt() and 0xff
        if (sighashType == 0) return false
        val scriptCode = Script.pay2pkh(publicKey)
        return try {
            val sighash = toSignTx.hashForSigning(0, scriptCode, sighashType, Satoshi(0), SigVersion.SIGVERSION_WITNESS_V0)
            val compactSig = Crypto.der2compact(sigWithType.take(sigWithType.size() - 1).toByteArray())
            Crypto.verifySignature(sighash, compactSig, publicKey)
        } catch (_: Exception) {
            false
        }
    }
}
