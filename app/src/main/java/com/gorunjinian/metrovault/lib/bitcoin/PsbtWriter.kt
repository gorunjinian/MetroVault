package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput


import com.gorunjinian.metrovault.lib.bitcoin.silentpayments.Bip374Fields

/**
 * Serializes a [Psbt] to bytes. Extracted from `Psbt`'s companion; [Psbt.write] delegates here.
 *
 * Dispatches on [Global.version]: v0 uses the BIP-174 layout (a global unsigned tx); v2 uses the
 * BIP-370 layout (no global tx — the transaction is carried across the global tx-version/locktime/
 * counts and the per-input `0x0e/0x0f/0x10` and per-output `0x03/0x04` fields, mirroring
 * [PsbtReader]). A silent-payment output whose synthesized script is still empty omits
 * `PSBT_OUT_SCRIPT` (matching how Sparrow emits an as-yet-underived SP recipient).
 */
internal object PsbtWriter {
    fun write(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        if (psbt.global.version >= 2) writeV2(psbt, out) else writeV0(psbt, out)
    }

    private fun writeMagic(out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        out.write(0x70)
        out.write(0x73)
        out.write(0x62)
        out.write(0x74)
        out.write(0xff)
    }

    /** Writes the `PSBT_GLOBAL_XPUB` (0x01) entries shared by v0 and v2. */
    private fun writeGlobalXpubs(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        psbt.global.extendedPublicKeys.forEach { xpub ->
            val key = ByteArrayOutput()
            key.write(0x01) // <keytype>
            Pack.writeInt32BE(xpub.prefix.toInt(), key)
            DeterministicWallet.write(xpub.extendedPublicKey, key)
            val value = ByteArrayOutput()
            Pack.writeInt32BE(xpub.masterKeyFingerprint.toInt(), value)
            xpub.extendedPublicKey.path.path.forEach { child -> Pack.writeInt32LE(child.toInt(), value) }
            writeDataEntry(DataEntry(ByteVector(key.toByteArray()), ByteVector(value.toByteArray())), out)
        }
    }

    /** Writes the per-input fields common to v0 and v2 (everything except the v2 outpoint fields, unknowns and the separator). */
    private fun writeInputCommonFields(input: Input, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        input.nonWitnessUtxo?.let { writeDataEntry(DataEntry(ByteVector("00"), ByteVector(Transaction.write(it))), out) }
        input.witnessUtxo?.let { writeDataEntry(DataEntry(ByteVector("01"), ByteVector(TxOut.write(it))), out) }
        sortPublicKeys(input.partialSigs).forEach { (publicKey, signature) -> writeDataEntry(DataEntry(ByteVector("02") + publicKey.value, signature), out) }
        input.sighashType?.let { writeDataEntry(DataEntry(ByteVector("03"), ByteVector(Pack.writeInt32LE(it))), out) }
        input.redeemScript?.let { writeDataEntry(DataEntry(ByteVector("04"), ByteVector(Script.write(it))), out) }
        input.witnessScript?.let { writeDataEntry(DataEntry(ByteVector("05"), ByteVector(Script.write(it))), out) }
        sortPublicKeys(input.derivationPaths).forEach { (publicKey, path) ->
            val key = ByteVector("06") + publicKey.value
            val value = ByteVector(Pack.writeInt32BE(path.masterKeyFingerprint.toInt())).concat(path.keyPath.path.map { ByteVector(Pack.writeInt32LE(it.toInt())) })
            writeDataEntry(DataEntry(key, value), out)
        }
        input.scriptSig?.let { writeDataEntry(DataEntry(ByteVector("07"), ByteVector(Script.write(it))), out) }
        input.scriptWitness?.let { writeDataEntry(DataEntry(ByteVector("08"), ByteVector(ScriptWitness.write(it))), out) }
        input.ripemd160.forEach { writeDataEntry(DataEntry(ByteVector("0a") + Crypto.ripemd160(it), it), out) }
        input.sha256.forEach { writeDataEntry(DataEntry(ByteVector("0b") + Crypto.sha256(it), it), out) }
        input.hash160.forEach { writeDataEntry(DataEntry(ByteVector("0c") + Crypto.hash160(it), it), out) }
        input.hash256.forEach { writeDataEntry(DataEntry(ByteVector("0d") + Crypto.hash256(it), it), out) }
        input.taprootKeySignature?.let { writeDataEntry(DataEntry(ByteVector("13"), it), out) }
        sortXonlyPublicKeys(input.taprootDerivationPaths).forEach { (publicKey, path) ->
            val key = ByteVector("16") + publicKey.value
            val value = path.write().byteVector()
            writeDataEntry(DataEntry(key, value), out)
        }
        input.taprootInternalKey?.let { writeDataEntry(DataEntry(ByteVector("17"), it.value), out) }
    }

    /** BIP-174 (v0) serialization: the transaction lives in the global `0x00` unsigned-tx entry. */
    private fun writeV0(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        writeMagic(out)

        /********** Global types **********/
        writeDataEntry(DataEntry(ByteVector("00"), ByteVector(Transaction.write(psbt.global.tx, Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS))), out)
        writeGlobalXpubs(psbt, out)
        if (psbt.global.version > 0) {
            writeDataEntry(DataEntry(ByteVector("fb"), ByteVector(Pack.writeInt32LE(psbt.global.version.toInt()))), out)
        }
        // BIP-375 forbids the global SP ECDH share/DLEQ proof fields in v0 — drop any that rode
        // in via `unknown` rather than emit a PSBT every BIP-375 parser must reject.
        psbt.global.unknown.filterNot(Bip374Fields::isGlobalSilentPaymentProofEntry).forEach { writeDataEntry(it, out) }
        out.write(0x00) // separator

        /********** Inputs **********/
        psbt.inputs.forEach { input ->
            writeInputCommonFields(input, out)
            input.unknown.forEach { writeDataEntry(it, out) }
            out.write(0x00) // separator
        }

        /********** Outputs **********/
        psbt.outputs.forEach { output ->
            output.redeemScript?.let { writeDataEntry(DataEntry(ByteVector("00"), ByteVector(Script.write(it))), out) }
            output.witnessScript?.let { writeDataEntry(DataEntry(ByteVector("01"), ByteVector(Script.write(it))), out) }
            sortPublicKeys(output.derivationPaths).forEach { (publicKey, path) ->
                val key = ByteVector("02") + publicKey.value
                val value = ByteVector(Pack.writeInt32BE(path.masterKeyFingerprint.toInt())).concat(path.keyPath.path.map { ByteVector(Pack.writeInt32LE(it.toInt())) })
                writeDataEntry(DataEntry(key, value), out)
            }
            output.taprootInternalKey?.let { writeDataEntry(DataEntry(ByteVector("05"), it.value), out) }
            sortXonlyPublicKeys(output.taprootDerivationPaths).forEach { (publicKey, path) ->
                val key = ByteVector("07") + publicKey.value
                val value = path.write().byteVector()
                writeDataEntry(DataEntry(key, value), out)
            }
            output.unknown.forEach { writeDataEntry(it, out) }
            out.write(0x00) // separator
        }
    }

    /** BIP-370 (v2) serialization: no global tx; the transaction is carried across global counts and per-input/output fields. */
    private fun writeV2(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        val tx = psbt.global.tx
        writeMagic(out)

        /********** Global types **********/
        writeGlobalXpubs(psbt, out)
        writeDataEntry(DataEntry(ByteVector("02"), ByteVector(Pack.writeInt32LE(tx.version.toInt()))), out) // PSBT_GLOBAL_TX_VERSION
        psbt.global.fallbackLocktime?.let {
            writeDataEntry(DataEntry(ByteVector("03"), ByteVector(Pack.writeInt32LE(it.toInt()))), out) // PSBT_GLOBAL_FALLBACK_LOCKTIME
        }
        writeDataEntry(DataEntry(ByteVector("04"), ByteVector(varint(psbt.inputs.size))), out)  // PSBT_GLOBAL_INPUT_COUNT
        writeDataEntry(DataEntry(ByteVector("05"), ByteVector(varint(psbt.outputs.size))), out)  // PSBT_GLOBAL_OUTPUT_COUNT
        psbt.global.txModifiable?.let {
            writeDataEntry(DataEntry(ByteVector("06"), ByteVector(byteArrayOf(it))), out) // PSBT_GLOBAL_TX_MODIFIABLE
        }
        writeDataEntry(DataEntry(ByteVector("fb"), ByteVector(Pack.writeInt32LE(psbt.global.version.toInt()))), out) // PSBT_GLOBAL_VERSION
        psbt.global.unknown.forEach { writeDataEntry(it, out) }
        out.write(0x00) // separator

        /********** Inputs **********/
        psbt.inputs.forEachIndexed { i, input ->
            val txIn = tx.txIn[i]
            writeInputCommonFields(input, out)
            // PSBT_IN_PREVIOUS_TXID — the txid in internal (little-endian) byte order, i.e. TxHash bytes.
            writeDataEntry(DataEntry(ByteVector("0e"), txIn.outPoint.hash.value), out)
            writeDataEntry(DataEntry(ByteVector("0f"), ByteVector(Pack.writeInt32LE(txIn.outPoint.index.toInt()))), out)
            writeDataEntry(DataEntry(ByteVector("10"), ByteVector(Pack.writeInt32LE(txIn.sequence.toInt()))), out)
            input.unknown.forEach { writeDataEntry(it, out) }
            out.write(0x00) // separator
        }

        /********** Outputs **********/
        psbt.outputs.forEachIndexed { i, output ->
            val txOut = tx.txOut[i]
            output.redeemScript?.let { writeDataEntry(DataEntry(ByteVector("00"), ByteVector(Script.write(it))), out) }
            output.witnessScript?.let { writeDataEntry(DataEntry(ByteVector("01"), ByteVector(Script.write(it))), out) }
            sortPublicKeys(output.derivationPaths).forEach { (publicKey, path) ->
                val key = ByteVector("02") + publicKey.value
                val value = ByteVector(Pack.writeInt32BE(path.masterKeyFingerprint.toInt())).concat(path.keyPath.path.map { ByteVector(Pack.writeInt32LE(it.toInt())) })
                writeDataEntry(DataEntry(key, value), out)
            }
            writeDataEntry(DataEntry(ByteVector("03"), ByteVector(Pack.writeInt64LE(txOut.amount.toLong()))), out) // PSBT_OUT_AMOUNT
            // PSBT_OUT_SCRIPT — omitted while empty (an as-yet-underived silent-payment output).
            if (txOut.publicKeyScript.size() > 0) {
                writeDataEntry(DataEntry(ByteVector("04"), txOut.publicKeyScript), out)
            }
            output.taprootInternalKey?.let { writeDataEntry(DataEntry(ByteVector("05"), it.value), out) }
            sortXonlyPublicKeys(output.taprootDerivationPaths).forEach { (publicKey, path) ->
                val key = ByteVector("07") + publicKey.value
                val value = path.write().byteVector()
                writeDataEntry(DataEntry(key, value), out)
            }
            output.unknown.forEach { writeDataEntry(it, out) }
            out.write(0x00) // separator
        }
    }

    private fun varint(n: Int): ByteArray {
        val o = ByteArrayOutput()
        BtcSerializer.writeVarint(n, o)
        return o.toByteArray()
    }

    /** We use lexicographic ordering on the public keys. */
    private fun <T> sortPublicKeys(publicKeys: Map<PublicKey, T>): List<Pair<PublicKey, T>> {
        return publicKeys.toList().sortedWith { a, b -> LexicographicalOrdering.compare(a.first, b.first) }
    }

    /** We use lexicographic ordering on the public keys. */
    private fun <T> sortXonlyPublicKeys(publicKeys: Map<XonlyPublicKey, T>): List<Pair<XonlyPublicKey, T>> {
        return publicKeys.toList().sortedWith { a, b -> LexicographicalOrdering.compare(a.first, b.first) }
    }

    private fun writeDataEntry(entry: DataEntry, output: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        BtcSerializer.writeVarint(entry.key.size(), output)
        output.write(entry.key.bytes)
        BtcSerializer.writeVarint(entry.value.size(), output)
        output.write(entry.value.bytes)
    }
}
