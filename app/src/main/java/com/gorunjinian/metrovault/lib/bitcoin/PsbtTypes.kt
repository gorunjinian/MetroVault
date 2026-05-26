package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayOutput
import kotlin.jvm.JvmField

/**
 * Data types for [Psbt] (BIP-174 / BIP-370). Extracted from `Psbt.kt` so the class file holds the
 * live operations while the serialized/parsed shapes live here. All types stay in the
 * `lib.bitcoin` package, so this split is invisible to consumers.
 */

/**
 * @param prefix extended public key version bytes.
 * @param masterKeyFingerprint fingerprint of the master key.
 * @param extendedPublicKey BIP32 extended public key.
 */
data class ExtendedPublicKeyWithMaster(@JvmField val prefix: Long, @JvmField val masterKeyFingerprint: Long, @JvmField val extendedPublicKey: DeterministicWallet.ExtendedPublicKey)

/**
 * @param masterKeyFingerprint fingerprint of the master key.
 * @param keyPath bip 32 derivation path.
 */
data class KeyPathWithMaster(@JvmField val masterKeyFingerprint: Long, @JvmField val keyPath: KeyPath)

/**
 * @param masterKeyFingerprint fingerprint of the master key.
 * @param keyPath bip 32 derivation path.
 */
data class TaprootBip32DerivationPath(@JvmField val leaves: List<ByteVector32>, @JvmField val masterKeyFingerprint: Long, @JvmField val keyPath: KeyPath) {
    fun write(): ByteArray {
        val out = ByteArrayOutput()
        BtcSerializer.writeVarint(leaves.size, out)
        leaves.forEach { BtcSerializer.writeBytes(it, out) }
        BtcSerializer.writeBytes(ByteVector(Pack.writeInt32BE(masterKeyFingerprint.toInt())).concat(keyPath.path.map { ByteVector(Pack.writeInt32LE(it.toInt())) }), out)
        return out.toByteArray()
    }

    companion object {
        fun read(bin: ByteArray): TaprootBip32DerivationPath {
            val input = ByteArrayInput(bin)
            val numLeaves = BtcSerializer.varint(input).toInt()
            val leaves = (0 until numLeaves).map { BtcSerializer.bytes(input, 32).byteVector32() }
            val masterKeyFingerprint = Pack.int32BE(input).toLong()
            val childCount = (input.availableBytes / 4)
            val keyPath = KeyPath((0 until childCount).map { _ -> Pack.int32LE(input).toUInt().toLong() })
            return TaprootBip32DerivationPath(leaves, masterKeyFingerprint, keyPath)
        }
    }
}

data class DataEntry(@JvmField val key: ByteVector, @JvmField val value: ByteVector)

/**
 * Global data for the PSBT.
 *
 * @param version psbt version.
 * @param tx partially signed transaction. NB: the transaction must be serialized with the "old" format (without witnesses).
 * @param extendedPublicKeys (optional) extended public keys used when signing inputs and producing outputs.
 * @param unknown (optional) unknown global entries.
 */
data class Global(
    @JvmField val version: Long,
    @JvmField val tx: Transaction,
    @JvmField val extendedPublicKeys: List<ExtendedPublicKeyWithMaster>,
    @JvmField val unknown: List<DataEntry>,
    /** PSBT v2 (BIP-370) fallback locktime (`PSBT_GLOBAL_FALLBACK_LOCKTIME`). Null for v0. */
    @JvmField val fallbackLocktime: Long? = null,
    /** PSBT v2 (BIP-370) tx-modifiable flags (`PSBT_GLOBAL_TX_MODIFIABLE`). Null for v0/absent. */
    @JvmField val txModifiable: Byte? = null
)

/** A PSBT input. A valid PSBT must contain one such input per input of the [[Global.tx]]. */
sealed class Input {
    // @formatter:off
    /** Non-witness utxo, used when spending non-segwit outputs (may also be included when spending segwit outputs). */
    abstract val nonWitnessUtxo: Transaction?
    /** Witness utxo, used when spending segwit outputs. */
    abstract val witnessUtxo: TxOut?
    /** Sighash type to be used when producing signatures for this output. */
    abstract val sighashType: Int?
    /** Signatures as would be pushed to the stack from a scriptSig or witness. */
    abstract val partialSigs: Map<PublicKey, ByteVector>
    /** Derivation paths used for the signatures. */
    abstract val derivationPaths: Map<PublicKey, KeyPathWithMaster>
    /** Redeem script for this input (when using p2sh). */
    abstract val redeemScript: List<ScriptElt>?
    /** Witness script for this input (when using p2wsh). */
    abstract val witnessScript: List<ScriptElt>?
    /** Fully constructed scriptSig with signatures and any other scripts necessary for the input to pass validation. */
    abstract val scriptSig: List<ScriptElt>?
    /** Fully constructed scriptWitness with signatures and any other scripts necessary for the input to pass validation. */
    abstract val scriptWitness: ScriptWitness?
    /** RipeMD160 preimages (e.g. for miniscript hash challenges). */
    abstract val ripemd160: Set<ByteVector>
    /** Sha256 preimages (e.g. for miniscript hash challenges). */
    abstract val sha256: Set<ByteVector>
    /** Hash160 preimages (e.g. for miniscript hash challenges). */
    abstract val hash160: Set<ByteVector>
    /** Hash256 preimages (e.g. for miniscript hash challenges). */
    abstract val hash256: Set<ByteVector>
    /** taproot keypath signature */
    abstract val taprootKeySignature: ByteVector?
    /** Derivation paths used for taproot signatures, key is the internal key */
    abstract val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>
    /** Internal key used for taproot signatures */
    abstract val taprootInternalKey: XonlyPublicKey?
    /** (optional) Unknown global entries. */
    abstract val unknown: List<DataEntry>
    // @formatter:on

    /**
     * A partially signed input without details about the utxo.
     * More signatures may need to be added before it can be finalized.
     */
    data class PartiallySignedInputWithoutUtxo(
        override val sighashType: Int?,
        override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        override val ripemd160: Set<ByteVector>,
        override val sha256: Set<ByteVector>,
        override val hash160: Set<ByteVector>,
        override val hash256: Set<ByteVector>,
        override val taprootKeySignature: ByteVector?,
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
        override val taprootInternalKey: XonlyPublicKey?,
        override val unknown: List<DataEntry>,
    ) : Input() {
        override val nonWitnessUtxo: Transaction? = null
        override val witnessUtxo: TxOut? = null
        override val redeemScript: List<ScriptElt>? = null
        override val witnessScript: List<ScriptElt>? = null
        override val partialSigs: Map<PublicKey, ByteVector> = mapOf()
        override val scriptSig: List<ScriptElt>? = null
        override val scriptWitness: ScriptWitness? = null
    }

    /**
     * A fully signed input without details about the utxo.
     * Input finalizers should keep the utxo to allow transaction extractors to verify the final network serialized
     * transaction, but it's not mandatory, so we may not have it available when parsing psbt inputs.
     */
    data class FinalizedInputWithoutUtxo(
        override val scriptWitness: ScriptWitness?,
        override val scriptSig: List<ScriptElt>?,
        override val ripemd160: Set<ByteVector>,
        override val sha256: Set<ByteVector>,
        override val hash160: Set<ByteVector>,
        override val hash256: Set<ByteVector>,
        override val unknown: List<DataEntry>
    ) : Input() {
        override val nonWitnessUtxo: Transaction? = null
        override val witnessUtxo: TxOut? = null
        override val sighashType: Int? = null
        override val partialSigs: Map<PublicKey, ByteVector> = mapOf()
        override val derivationPaths: Map<PublicKey, KeyPathWithMaster> = mapOf()
        override val taprootKeySignature: ByteVector? = null
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath> = mapOf()
        override val taprootInternalKey: XonlyPublicKey? = null
        override val redeemScript: List<ScriptElt>? = null
        override val witnessScript: List<ScriptElt>? = null
    }

    /** An input spending a segwit output. */
    sealed class WitnessInput : Input() {
        abstract val txOut: TxOut
        val amount: Satoshi by lazy { txOut.amount }
        override val witnessUtxo: TxOut? by lazy { txOut }

        /** A partially signed segwit input. More signatures may need to be added before it can be finalized. */
        data class PartiallySignedWitnessInput(
            override val txOut: TxOut,
            override val nonWitnessUtxo: Transaction?,
            override val sighashType: Int?,
            override val partialSigs: Map<PublicKey, ByteVector>,
            override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
            override val redeemScript: List<ScriptElt>?,
            override val witnessScript: List<ScriptElt>?,
            override val ripemd160: Set<ByteVector>,
            override val sha256: Set<ByteVector>,
            override val hash160: Set<ByteVector>,
            override val hash256: Set<ByteVector>,
            override val taprootKeySignature: ByteVector?,
            override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
            override val taprootInternalKey: XonlyPublicKey?,
            override val unknown: List<DataEntry>
        ) : WitnessInput() {
            override val scriptSig: List<ScriptElt>? = null
            override val scriptWitness: ScriptWitness? = null
        }

        /** A fully signed segwit input. */
        data class FinalizedWitnessInput(
            override val txOut: TxOut,
            override val nonWitnessUtxo: Transaction?,
            override val scriptWitness: ScriptWitness,
            override val scriptSig: List<ScriptElt>?,
            override val ripemd160: Set<ByteVector>,
            override val sha256: Set<ByteVector>,
            override val hash160: Set<ByteVector>,
            override val hash256: Set<ByteVector>,
            override val unknown: List<DataEntry>
        ) : WitnessInput() {
            override val sighashType: Int? = null
            override val partialSigs: Map<PublicKey, ByteVector> = mapOf()
            override val derivationPaths: Map<PublicKey, KeyPathWithMaster> = mapOf()
            override val taprootKeySignature: ByteVector? = null
            override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath> = mapOf()
            override val taprootInternalKey: XonlyPublicKey? = null
            override val redeemScript: List<ScriptElt>? = null
            override val witnessScript: List<ScriptElt>? = null
        }
    }

    /** An input spending a non-segwit output. */
    sealed class NonWitnessInput : Input() {
        abstract val inputTx: Transaction
        abstract val outputIndex: Int
        val amount: Satoshi by lazy { inputTx.txOut[outputIndex].amount }
        override val nonWitnessUtxo: Transaction? by lazy { inputTx }

        // The following fields should only be present for inputs which spend segwit outputs (including P2SH embedded ones).
        override val witnessUtxo: TxOut? = null
        override val witnessScript: List<ScriptElt>? = null
        override val taprootKeySignature: ByteVector? = null
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath> = mapOf()
        override val taprootInternalKey: XonlyPublicKey? = null

        /** A partially signed non-segwit input. More signatures may need to be added before it can be finalized. */
        data class PartiallySignedNonWitnessInput(
            override val inputTx: Transaction,
            override val outputIndex: Int,
            override val sighashType: Int?,
            override val partialSigs: Map<PublicKey, ByteVector>,
            override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
            override val redeemScript: List<ScriptElt>?,
            override val ripemd160: Set<ByteVector>,
            override val sha256: Set<ByteVector>,
            override val hash160: Set<ByteVector>,
            override val hash256: Set<ByteVector>,
            override val unknown: List<DataEntry>
        ) : NonWitnessInput() {
            override val scriptSig: List<ScriptElt>? = null
            override val scriptWitness: ScriptWitness? = null
        }

        /** A fully signed non-segwit input. */
        data class FinalizedNonWitnessInput(
            override val inputTx: Transaction,
            override val outputIndex: Int,
            override val scriptSig: List<ScriptElt>,
            override val ripemd160: Set<ByteVector>,
            override val sha256: Set<ByteVector>,
            override val hash160: Set<ByteVector>,
            override val hash256: Set<ByteVector>,
            override val unknown: List<DataEntry>
        ) : NonWitnessInput() {
            override val sighashType: Int? = null
            override val partialSigs: Map<PublicKey, ByteVector> = mapOf()
            override val derivationPaths: Map<PublicKey, KeyPathWithMaster> = mapOf()
            override val redeemScript: List<ScriptElt>? = null
            override val witnessScript: List<ScriptElt>? = null
            override val scriptWitness: ScriptWitness? = null
        }
    }
}

/** A PSBT output. A valid PSBT must contain one such output per output of the [[Global.tx]]. */
sealed class Output {
    // @formatter:off
    /** Redeem script for this output (when using p2sh). */
    abstract val redeemScript: List<ScriptElt>?
    /** Witness script for this output (when using p2wsh). */
    abstract val witnessScript: List<ScriptElt>?
    /** Derivation paths used to produce the public keys associated to this output. */
    abstract val derivationPaths: Map<PublicKey, KeyPathWithMaster>
    /** Internal key used to produce the public key associated to this output. */
    abstract val taprootInternalKey: XonlyPublicKey?
    /** Taproot Derivation paths used to produce the public keys associated to this output. */
    abstract val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>
    /** (optional) Unknown global entries. */
    abstract val unknown: List<DataEntry>
    // @formatter:on

    /** A non-segwit output. */
    data class NonWitnessOutput(
        override val redeemScript: List<ScriptElt>?,
        override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        override val unknown: List<DataEntry>
    ) : Output() {
        override val witnessScript: List<ScriptElt>? = null
        override val taprootInternalKey: XonlyPublicKey? = null
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath> = mapOf()
    }

    /** A segwit output. */
    data class WitnessOutput(
        override val witnessScript: List<ScriptElt>?,
        override val redeemScript: List<ScriptElt>?,
        override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        override val taprootInternalKey: XonlyPublicKey?,
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
        override val unknown: List<DataEntry>
    ) : Output()

    /** An output for which usage of segwit is currently unknown. */
    data class UnspecifiedOutput(
        override val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        override val taprootInternalKey: XonlyPublicKey?,
        override val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
        override val unknown: List<DataEntry>
    ) : Output() {
        override val redeemScript: List<ScriptElt>? = null
        override val witnessScript: List<ScriptElt>? = null
    }
}

sealed class UpdateFailure {
    data class InvalidInput(val reason: String) : UpdateFailure()
    data class InvalidNonWitnessUtxo(val reason: String) : UpdateFailure()
    data class InvalidWitnessUtxo(val reason: String) : UpdateFailure()
    data class CannotCombine(val reason: String) : UpdateFailure()
    data class CannotJoin(val reason: String) : UpdateFailure()
    data class CannotUpdateInput(val index: Int, val reason: String) : UpdateFailure()
    data class CannotUpdateOutput(val index: Int, val reason: String) : UpdateFailure()
    data class CannotSignInput(val index: Int, val reason: String) : UpdateFailure()
    data class CannotFinalizeInput(val index: Int, val reason: String) : UpdateFailure()
    data class CannotExtractTx(val reason: String) : UpdateFailure()
}

data class SignPsbtResult(val psbt: Psbt, val sig: ByteVector)

sealed class ParseFailure {
    object InvalidMagicBytes : ParseFailure()
    object InvalidSeparator : ParseFailure()
    object DuplicateKeys : ParseFailure()
    data class InvalidPsbtVersion(val reason: String) : ParseFailure()
    data class UnsupportedPsbtVersion(val version: Long) : ParseFailure()
    data class InvalidGlobalTx(val reason: String) : ParseFailure()
    object GlobalTxMissing : ParseFailure()
    data class InvalidExtendedPublicKey(val reason: String) : ParseFailure()
    data class InvalidTxInput(val reason: String) : ParseFailure()
    data class InvalidTxOutput(val reason: String) : ParseFailure()
    object InvalidContent : ParseFailure()
}

/**
 * Builds the appropriate [Input] subtype from the parsed fields. Shared by the reader and by
 * [Psbt.combine]. P2A (anchor) and emptied/finalized inputs are detected here.
 */
internal fun createInput(
    txIn: TxIn,
    nonWitnessUtxo: Transaction?,
    witnessUtxo: TxOut?,
    sighashType: Int?,
    partialSigs: Map<PublicKey, ByteVector>,
    derivationPaths: Map<PublicKey, KeyPathWithMaster>,
    redeemScript: List<ScriptElt>?,
    witnessScript: List<ScriptElt>?,
    scriptSig: List<ScriptElt>?,
    scriptWitness: ScriptWitness?,
    ripemd160: Set<ByteVector>,
    sha256: Set<ByteVector>,
    hash160: Set<ByteVector>,
    hash256: Set<ByteVector>,
    taprootKeySignature: ByteVector?,
    taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
    taprootInternalKey: XonlyPublicKey?,
    unknown: List<DataEntry>
): Input {
    val outputIndex = txIn.outPoint.index.toInt()
    val emptied = redeemScript == null && witnessScript == null && partialSigs.isEmpty() && derivationPaths.isEmpty() && sighashType == null
    return when {
        // @formatter:off
        // If the input is P2A, it doesn't need any signature to be finalized, anyone can spend it.
        witnessUtxo != null && witnessUtxo.publicKeyScript == Script.write(Script.pay2anchor).byteVector() -> Input.WitnessInput.FinalizedWitnessInput(witnessUtxo, nonWitnessUtxo, Script.witnessPay2anchor, scriptSig, ripemd160, sha256, hash160, hash256, unknown)
        nonWitnessUtxo != null && nonWitnessUtxo.txOut[outputIndex].publicKeyScript == Script.write(Script.pay2anchor).byteVector() -> Input.WitnessInput.FinalizedWitnessInput(nonWitnessUtxo.txOut[outputIndex], nonWitnessUtxo, Script.witnessPay2anchor, scriptSig, ripemd160, sha256, hash160, hash256, unknown)
        // If the input is finalized, it must have been emptied otherwise it's invalid.
        witnessUtxo != null && scriptWitness != null && emptied -> Input.WitnessInput.FinalizedWitnessInput(witnessUtxo, nonWitnessUtxo, scriptWitness, scriptSig, ripemd160, sha256, hash160, hash256, unknown)
        nonWitnessUtxo != null && scriptSig != null && emptied -> Input.NonWitnessInput.FinalizedNonWitnessInput(nonWitnessUtxo, txIn.outPoint.index.toInt(), scriptSig, ripemd160, sha256, hash160, hash256, unknown)
        (scriptSig != null || scriptWitness != null) && emptied -> Input.FinalizedInputWithoutUtxo(scriptWitness, scriptSig, ripemd160, sha256, hash160, hash256, unknown)
        witnessUtxo != null -> Input.WitnessInput.PartiallySignedWitnessInput(witnessUtxo, nonWitnessUtxo, sighashType, partialSigs, derivationPaths, redeemScript, witnessScript, ripemd160, sha256, hash160, hash256, taprootKeySignature, taprootDerivationPaths, taprootInternalKey, unknown)
        nonWitnessUtxo != null -> Input.NonWitnessInput.PartiallySignedNonWitnessInput(nonWitnessUtxo, txIn.outPoint.index.toInt(), sighashType, partialSigs, derivationPaths, redeemScript, ripemd160, sha256, hash160, hash256, unknown)
        else -> Input.PartiallySignedInputWithoutUtxo(sighashType, derivationPaths, ripemd160, sha256, hash160, hash256, taprootKeySignature, taprootDerivationPaths, taprootInternalKey, unknown)
        // @formatter:on
    }
}

/** Builds the appropriate [Output] subtype from the parsed fields. Shared by the reader and by [Psbt.combine]. */
internal fun createOutput(
    redeemScript: List<ScriptElt>?,
    witnessScript: List<ScriptElt>?,
    derivationPaths: Map<PublicKey, KeyPathWithMaster>,
    taprootInternalKey: XonlyPublicKey?,
    taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
    unknown: List<DataEntry>
): Output = when {
    witnessScript != null -> Output.WitnessOutput(witnessScript, redeemScript, derivationPaths, taprootInternalKey, taprootDerivationPaths, unknown)
    redeemScript != null -> Output.NonWitnessOutput(redeemScript, derivationPaths, unknown)
    else -> Output.UnspecifiedOutput(derivationPaths, taprootInternalKey, taprootDerivationPaths, unknown)
}
