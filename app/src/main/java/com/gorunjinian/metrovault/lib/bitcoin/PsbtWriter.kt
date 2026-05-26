package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput

/**
 * Serializes a [Psbt] to the BIP-174 byte format. Extracted from `Psbt`'s companion; [Psbt.write]
 * delegates here. (PSBT v2 / BIP-370 write support is added separately.)
 */
internal object PsbtWriter {
    fun write(psbt: Psbt, out: com.gorunjinian.metrovault.lib.bitcoin.io.Output) {
        /********** Magic header **********/
        out.write(0x70)
        out.write(0x73)
        out.write(0x62)
        out.write(0x74)
        out.write(0xff)

        /********** Global types **********/
        writeDataEntry(DataEntry(ByteVector("00"), ByteVector(Transaction.write(psbt.global.tx, Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS))), out)
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
        if (psbt.global.version > 0) {
            writeDataEntry(DataEntry(ByteVector("fb"), ByteVector(Pack.writeInt32LE(psbt.global.version.toInt()))), out)
        }
        psbt.global.unknown.forEach { writeDataEntry(it, out) }
        out.write(0x00) // separator

        /********** Inputs **********/
        psbt.inputs.forEach { input ->
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
