package com.gorunjinian.metrovault.lib.bitcoin

import com.gorunjinian.metrovault.lib.bitcoin.crypto.Pack
import com.gorunjinian.metrovault.lib.bitcoin.io.ByteArrayInput
import com.gorunjinian.metrovault.lib.bitcoin.io.readNBytes
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import com.gorunjinian.metrovault.lib.bitcoin.utils.getOrElse

/**
 * Parses PSBT bytes (BIP-174 v0 and BIP-370 v2) into a [Psbt]. Extracted from Psbt's companion;
 * [Psbt.read] delegates here. For v2 the transaction is synthesized from the per-input/output
 * fields (see readV2).
 */
internal object PsbtReader {
    fun read(input: com.gorunjinian.metrovault.lib.bitcoin.io.Input): Either<ParseFailure, Psbt> {
        /********** Magic header **********/
        if (input.read() != 0x70 || input.read() != 0x73 || input.read() != 0x62 || input.read() != 0x74) {
            return Either.Left(ParseFailure.InvalidMagicBytes)
        }
        if (input.read() != 0xff) {
            return Either.Left(ParseFailure.InvalidSeparator)
        }

        /********** Global types **********/
        val global = run {
            val globalMap = readDataMap(input).getOrElse {
                return when (it) {
                    is ReadEntryFailure.DuplicateKeys -> Either.Left(ParseFailure.DuplicateKeys)
                    else -> Either.Left(ParseFailure.InvalidContent)
                }
            }
            // PSBT v2 (BIP-370) has no global unsigned tx; it carries the transaction across
            // per-input/output fields. Detect the version up front and hand off to the v2
            // reader, leaving the v0 path below completely unchanged.
            val psbtVersion = globalMap.find { it.key.size() == 1 && it.key[0] == 0xfb.toByte() }?.let { entry ->
                when {
                    entry.value.size() != 4 -> return Either.Left(ParseFailure.InvalidPsbtVersion("version must contain exactly 4 bytes"))
                    else -> Pack.int32LE(entry.value.bytes).toUInt().toLong()
                }
            } ?: 0L
            when {
                psbtVersion == 1L -> return Either.Left(ParseFailure.UnsupportedPsbtVersion(1L))
                psbtVersion >= 2L -> return readV2(input, globalMap, psbtVersion)
            }
            val keyTypes = setOf(0x00.toByte(), 0x01.toByte(), 0xfb.toByte())
            val (known, unknown) = globalMap.partition { keyTypes.contains(it.key[0]) }
            val version = known.find { it.key[0] == 0xfb.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidPsbtVersion("version key must contain exactly 1 byte"))
                    it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidPsbtVersion("version must contain exactly 4 bytes"))
                    else -> {
                        val v = Pack.int32LE(it.value.bytes).toUInt().toLong()
                        when {
                            v > Psbt.Version -> return Either.Left(ParseFailure.UnsupportedPsbtVersion(v))
                            else -> v
                        }
                    }
                }
            } ?: 0L
            val tx = known.find { it.key[0] == 0x00.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidGlobalTx("global tx key must contain exactly 1 byte"))
                    else -> {
                        val tx = try {
                            Transaction.read(it.value.bytes, Protocol.PROTOCOL_VERSION or Transaction.SERIALIZE_TRANSACTION_NO_WITNESS)
                        } catch (e: Exception) {
                            return Either.Left(ParseFailure.InvalidGlobalTx(e.message ?: "failed to parse transaction"))
                        }
                        when {
                            tx.txIn.any { input -> input.hasWitness || !input.signatureScript.isEmpty() } -> return Either.Left(ParseFailure.InvalidGlobalTx("global tx inputs must have empty scriptSigs and witness"))
                            else -> tx
                        }
                    }
                }
            } ?: return Either.Left(ParseFailure.GlobalTxMissing)
            val xpubs = known.filter { it.key[0] == 0x01.toByte() }.map {
                when {
                    it.key.size() != 79 -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> must contain 78 bytes"))
                    else -> {
                        val xpub = ByteArrayInput(it.key.drop(1).toByteArray())
                        val prefix = Pack.int32BE(xpub).toUInt().toLong()
                        val depth = xpub.read()
                        val parent = Pack.int32BE(xpub).toUInt().toLong()
                        val childNumber = Pack.int32BE(xpub).toUInt().toLong()
                        val chainCode = ByteVector32(xpub.readNBytes(32)!!)
                        val publicKey = ByteVector(xpub.readNBytes(33)!!)
                        when {
                            it.value.size() != 4 * (depth + 1) -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> must contain the master key fingerprint and derivation path"))
                            else -> {
                                val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                                val derivationPath = KeyPath((0 until depth).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                                when {
                                    derivationPath.lastChildNumber != childNumber -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> last child number mismatch"))
                                    else -> ExtendedPublicKeyWithMaster(prefix, masterKeyFingerprint, DeterministicWallet.ExtendedPublicKey(publicKey, chainCode, depth, derivationPath, parent))
                                }
                            }
                        }
                    }
                }
            }
            Global(version, tx, xpubs, unknown)
        }

        /********** Inputs **********/
        val inputs = global.tx.txIn.map { txIn ->
            val keyTypes = setOf<Byte>(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x13, 0x16, 0x17, 0x0a, 0x0b, 0x0c, 0x0d)
            val entries = readDataMap(input).getOrElse {
                return when (it) {
                    is ReadEntryFailure.DuplicateKeys -> Either.Left(ParseFailure.DuplicateKeys)
                    else -> Either.Left(ParseFailure.InvalidContent)
                }
            }
            val (known, unknown) = entries.partition { keyTypes.contains(it.key[0]) }
            val nonWitnessUtxo = known.find { it.key[0] == 0x00.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("non-witness utxo key must contain exactly 1 byte"))
                    else -> {
                        val inputTx = try {
                            Transaction.read(it.value.bytes)
                        } catch (e: Exception) {
                            return Either.Left(ParseFailure.InvalidTxInput(e.message ?: "failed to parse transaction"))
                        }
                        when {
                            inputTx.txid != txIn.outPoint.txid || txIn.outPoint.index >= inputTx.txOut.size -> return Either.Left(ParseFailure.InvalidTxInput("non-witness utxo does not match psbt outpoint"))
                            else -> inputTx
                        }
                    }
                }
            }
            val witnessUtxo = known.find { it.key[0] == 0x01.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("witness utxo key must contain exactly 1 byte"))
                    else -> {
                        val txOut = try {
                            TxOut.read(it.value.bytes)
                        } catch (e: Exception) {
                            return Either.Left(ParseFailure.InvalidTxInput(e.message ?: "failed to parse transaction output"))
                        }
                        nonWitnessUtxo?.let { tx -> if (tx.txOut[txIn.outPoint.index.toInt()] != txOut) return Either.Left(ParseFailure.InvalidTxInput("witness utxo does not match non-witness utxo output")) }
                        txOut
                    }
                }
            }
            val partialSigs = known.filter { it.key[0] == 0x02.toByte() }.map {
                when {
                    it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxInput("public key must contain exactly 33 bytes"))
                    else -> PublicKey(it.key.drop(1)) to it.value
                }
            }.toMap()
            val sighashType = known.find { it.key[0] == 0x03.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("sighash type key must contain exactly 1 byte"))
                    it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidTxInput("sighash type must contain exactly 4 bytes"))
                    else -> Pack.int32LE(it.value.bytes)
                }
            }
            val redeemScript = known.find { it.key[0] == 0x04.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("redeem script key must contain exactly 1 byte"))
                    else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse redeem script")) }
                }
            }
            val witnessScript = known.find { it.key[0] == 0x05.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("witness script key must contain exactly 1 byte"))
                    else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse witness script")) }
                }
            }
            val derivationPaths = known.filter { it.key[0] == 0x06.toByte() }.map {
                when {
                    it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxInput("bip32 derivation public key must contain exactly 33 bytes"))
                    it.value.size() < 4 || it.value.size() % 4 != 0 -> return Either.Left(ParseFailure.InvalidTxInput("bip32 derivation must contain master key fingerprint and child indexes"))
                    else -> {
                        val publicKey = PublicKey(it.key.drop(1))
                        val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                        val childCount = (it.value.size() / 4) - 1
                        val derivationPath = KeyPath((0 until childCount).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                        publicKey to KeyPathWithMaster(masterKeyFingerprint, derivationPath)
                    }
                }
            }.toMap()
            val scriptSig = known.find { it.key[0] == 0x07.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("script sig key must contain exactly 1 byte"))
                    else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse script sig")) }
                }
            }
            val scriptWitness = known.find { it.key[0] == 0x08.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("script witness key must contain exactly 1 byte"))
                    else -> try {
                        ScriptWitness.read(it.value.bytes)
                    } catch (e: Exception) {
                        return Either.Left(ParseFailure.InvalidTxInput(e.message ?: "failed to parse script witness"))
                    }
                }
            }
            val taprootKeySignature = known.find { it.key[0] == 0x13.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("taproot keypath signature key must contain exactly 1 byte"))
                    it.value.size() != 64 && it.value.size() != 65 -> return Either.Left(ParseFailure.InvalidTxInput("taproot keypath signature must contain 64 or 65 bytes"))
                    else -> it.value
                }
            }
            val taprootDerivationPaths = known.filter { it.key[0] == 0x16.toByte() }.map {
                when {
                    it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("taproot derivation path key must contain exactly 32 bytes"))
                    else -> {
                        val xonlyPublicKey = XonlyPublicKey(it.key.drop(1).toByteArray().byteVector32())
                        val path = TaprootBip32DerivationPath.read(it.value.toByteArray())
                        xonlyPublicKey to path
                    }
                }
            }.toMap()
            val taprootInternalKey = known.find { it.key[0] == 0x17.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("taproot internal key entry must have an empty key"))
                    it.value.size() != 32 -> return Either.Left(ParseFailure.InvalidTxInput("taproot internal key entry must have a 32 bytes value"))
                    else -> XonlyPublicKey(it.value.toByteArray().byteVector32())
                }
            }
            val ripemd160Preimages = known.filter { it.key[0] == 0x0a.toByte() }.map {
                when {
                    it.key.size() != 21 -> return Either.Left(ParseFailure.InvalidTxInput("ripemd160 hash must contain exactly 20 bytes"))
                    !it.key.drop(1).contentEquals(Crypto.ripemd160(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid ripemd160 preimage"))
                    else -> it.value
                }
            }.toSet()
            val sha256Preimages = known.filter { it.key[0] == 0x0b.toByte() }.map {
                when {
                    it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("sha256 hash must contain exactly 32 bytes"))
                    !it.key.drop(1).contentEquals(Crypto.sha256(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid sha256 preimage"))
                    else -> it.value
                }
            }.toSet()
            val hash160Preimages = known.filter { it.key[0] == 0x0c.toByte() }.map {
                when {
                    it.key.size() != 21 -> return Either.Left(ParseFailure.InvalidTxInput("hash160 hash must contain exactly 20 bytes"))
                    !it.key.drop(1).contentEquals(Crypto.hash160(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid hash160 preimage"))
                    else -> it.value
                }
            }.toSet()
            val hash256Preimages = known.filter { it.key[0] == 0x0d.toByte() }.map {
                when {
                    it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("hash256 hash must contain exactly 32 bytes"))
                    !it.key.drop(1).contentEquals(Crypto.hash256(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid hash256 preimage"))
                    else -> it.value
                }
            }.toSet()
            createInput(
                txIn,
                nonWitnessUtxo,
                witnessUtxo,
                sighashType,
                partialSigs,
                derivationPaths,
                redeemScript,
                witnessScript,
                scriptSig,
                scriptWitness,
                ripemd160Preimages,
                sha256Preimages,
                hash160Preimages,
                hash256Preimages,
                taprootKeySignature,
                taprootDerivationPaths,
                taprootInternalKey,
                unknown
            )
        }

        /********** Outputs **********/
        val outputs = global.tx.txOut.map {
            val keyTypes = setOf<Byte>(0x00, 0x01, 0x02, 0x05, 0x07)
            val entries = readDataMap(input).getOrElse {
                return when (it) {
                    is ReadEntryFailure.DuplicateKeys -> Either.Left(ParseFailure.DuplicateKeys)
                    else -> Either.Left(ParseFailure.InvalidContent)
                }
            }
            val (known, unknown) = entries.partition { keyTypes.contains(it.key[0]) }
            val redeemScript = known.find { it.key[0] == 0x00.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("redeem script key must contain exactly 1 byte"))
                    else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxOutput("failed to parse redeem script")) }
                }
            }
            val witnessScript = known.find { it.key[0] == 0x01.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("witness script key must contain exactly 1 byte"))
                    else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxOutput("failed to parse witness script")) }
                }
            }
            val derivationPaths = known.filter { it.key[0] == 0x02.toByte() }.map {
                when {
                    it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxOutput("bip32 derivation public key must contain exactly 33 bytes"))
                    it.value.size() < 4 || it.value.size() % 4 != 0 -> return Either.Left(ParseFailure.InvalidTxOutput("bip32 derivation must contain master key fingerprint and child indexes"))
                    else -> {
                        val publicKey = PublicKey(it.key.drop(1))
                        val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                        val childCount = (it.value.size() / 4) - 1
                        val derivationPath = KeyPath((0 until childCount).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                        publicKey to KeyPathWithMaster(masterKeyFingerprint, derivationPath)
                    }
                }
            }.toMap()
            val taprootInternalKey = known.find { it.key[0] == 0x05.toByte() }?.let {
                when {
                    it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot internal key entry must have an empty key"))
                    it.value.size() != 32 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot internal key entry must have a 32 bytes value"))
                    else -> XonlyPublicKey(it.value.toByteArray().byteVector32())
                }
            }
            val taprootDerivationPaths = known.filter { it.key[0] == 0x07.toByte() }.map {
                when {
                    it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot derivation path key must contain exactly 32 bytes"))
                    else -> {
                        val xonlyPublicKey = XonlyPublicKey(it.key.drop(1).toByteArray().byteVector32())
                        val path = TaprootBip32DerivationPath.read(it.value.toByteArray())
                        xonlyPublicKey to path
                    }
                }
            }.toMap()
            createOutput(redeemScript, witnessScript, derivationPaths, taprootInternalKey, taprootDerivationPaths, unknown)
        }

        return if (input.availableBytes != 0) {
            Either.Left(ParseFailure.InvalidContent)
        } else {
            Either.Right(Psbt(global, inputs, outputs))
        }
    }

    /**
     * Reads a PSBT v2 (BIP-370). Unlike v0 there is no global unsigned transaction; the tx is
     * carried across the global tx-version / fallback-locktime / counts and the per-input
     * (`0x0e/0x0f/0x10`) and per-output (`0x03/0x04`) fields. We synthesize a v0-shaped
     * [Transaction] from those fields so the signer, analyzer and finalizer operate unchanged on
     * [Global.tx]. A silent-payment output legitimately omits `PSBT_OUT_SCRIPT`; its synthesized
     * script is empty until [SilentPaymentSender][com.gorunjinian.metrovault.domain.service.silentpayments.SilentPaymentSender]
     * fills it. Writing back is handled by [write] when [Global.version] >= 2.
     */
    private fun readV2(
        input: com.gorunjinian.metrovault.lib.bitcoin.io.Input,
        globalMap: List<DataEntry>,
        psbtVersion: Long
    ): Either<ParseFailure, Psbt> {
        if (psbtVersion > 2L) return Either.Left(ParseFailure.UnsupportedPsbtVersion(psbtVersion))
        if (globalMap.any { it.key.size() == 1 && it.key[0] == 0x00.toByte() }) {
            return Either.Left(ParseFailure.InvalidGlobalTx("PSBT_GLOBAL_UNSIGNED_TX is not allowed in PSBTv2"))
        }

val globalKeyTypes = setOf<Byte>(0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0xfb.toByte())
        val (knownGlobal, unknownGlobal) = globalMap.partition { globalKeyTypes.contains(it.key[0]) }

        val txVersion = knownGlobal.find { it.key[0] == 0x02.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidGlobalTx("tx version key must contain exactly 1 byte"))
                it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidGlobalTx("tx version must contain exactly 4 bytes"))
                else -> Pack.int32LE(it.value.bytes).toUInt().toLong()
            }
        } ?: return Either.Left(ParseFailure.InvalidGlobalTx("PSBT_GLOBAL_TX_VERSION is required in PSBTv2"))

        val fallbackLocktime = knownGlobal.find { it.key[0] == 0x03.toByte() }?.let {
            when {
                it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidGlobalTx("fallback locktime must contain exactly 4 bytes"))
                else -> Pack.int32LE(it.value.bytes).toUInt().toLong()
            }
        }

        val inputCount = knownGlobal.find { it.key[0] == 0x04.toByte() }?.let {
            runCatching { BtcSerializer.varint(ByteArrayInput(it.value.toByteArray())).toInt() }
                .getOrElse { return Either.Left(ParseFailure.InvalidGlobalTx("invalid input count")) }
        } ?: return Either.Left(ParseFailure.InvalidGlobalTx("PSBT_GLOBAL_INPUT_COUNT is required in PSBTv2"))

        val outputCount = knownGlobal.find { it.key[0] == 0x05.toByte() }?.let {
            runCatching { BtcSerializer.varint(ByteArrayInput(it.value.toByteArray())).toInt() }
                .getOrElse { return Either.Left(ParseFailure.InvalidGlobalTx("invalid output count")) }
        } ?: return Either.Left(ParseFailure.InvalidGlobalTx("PSBT_GLOBAL_OUTPUT_COUNT is required in PSBTv2"))

        val txModifiable = knownGlobal.find { it.key[0] == 0x06.toByte() }?.let {
            when {
                it.value.size() != 1 -> return Either.Left(ParseFailure.InvalidGlobalTx("tx modifiable must be a single byte"))
                else -> it.value[0]
            }
        }

        val xpubs = knownGlobal.filter { it.key[0] == 0x01.toByte() }.map {
            when {
                it.key.size() != 79 -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> must contain 78 bytes"))
                else -> {
                    val xpub = ByteArrayInput(it.key.drop(1).toByteArray())
                    val prefix = Pack.int32BE(xpub).toUInt().toLong()
                    val depth = xpub.read()
                    val parent = Pack.int32BE(xpub).toUInt().toLong()
                    val childNumber = Pack.int32BE(xpub).toUInt().toLong()
                    val chainCode = ByteVector32(xpub.readNBytes(32)!!)
                    val publicKey = ByteVector(xpub.readNBytes(33)!!)
                    when {
                        it.value.size() != 4 * (depth + 1) -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> must contain the master key fingerprint and derivation path"))
                        else -> {
                            val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                            val derivationPath = KeyPath((0 until depth).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                            when {
                                derivationPath.lastChildNumber != childNumber -> return Either.Left(ParseFailure.InvalidExtendedPublicKey("<xpub> last child number mismatch"))
                                else -> ExtendedPublicKeyWithMaster(prefix, masterKeyFingerprint, DeterministicWallet.ExtendedPublicKey(publicKey, chainCode, depth, derivationPath, parent))
                            }
                        }
                    }
                }
            }
        }

        /********** Inputs (v2) **********/
        val parsedInputs = ArrayList<ParsedV2Input>(inputCount)
        for (i in 0 until inputCount) {
            val entries = readDataMap(input).getOrElse {
                return when (it) {
                    is ReadEntryFailure.DuplicateKeys -> Either.Left(ParseFailure.DuplicateKeys)
                    else -> Either.Left(ParseFailure.InvalidContent)
                }
            }
            parsedInputs.add(parseV2Input(entries).getOrElse { return Either.Left(it) })
        }

        /********** Outputs (v2) **********/
        val parsedOutputs = ArrayList<ParsedV2Output>(outputCount)
        for (i in 0 until outputCount) {
            val entries = readDataMap(input).getOrElse {
                return when (it) {
                    is ReadEntryFailure.DuplicateKeys -> Either.Left(ParseFailure.DuplicateKeys)
                    else -> Either.Left(ParseFailure.InvalidContent)
                }
            }
            parsedOutputs.add(parseV2Output(entries).getOrElse { return Either.Left(it) })
        }

        if (input.availableBytes != 0) return Either.Left(ParseFailure.InvalidContent)

        // Synthesize the transaction inputs from the per-input outpoint/sequence fields.
        val txIns = ArrayList<TxIn>(parsedInputs.size)
        for (f in parsedInputs) {
            val prevTxid = f.prevTxid ?: return Either.Left(ParseFailure.InvalidTxInput("PSBT_IN_PREVIOUS_TXID is required in PSBTv2"))
            val prevIndex = f.prevIndex ?: return Either.Left(ParseFailure.InvalidTxInput("PSBT_IN_OUTPUT_INDEX is required in PSBTv2"))
            // PSBT_IN_PREVIOUS_TXID is the txid in internal (little-endian) byte order — exactly
            // what TxHash stores — so it maps to TxHash directly, no reversal.
            val outPoint = OutPoint(TxHash(prevTxid), prevIndex)
            f.nonWitnessUtxo?.let { tx ->
                if (tx.txid != outPoint.txid || prevIndex >= tx.txOut.size) {
                    return Either.Left(ParseFailure.InvalidTxInput("non-witness utxo does not match psbt outpoint"))
                }
            }
            if (f.nonWitnessUtxo != null && f.witnessUtxo != null &&
                f.nonWitnessUtxo.txOut[prevIndex.toInt()] != f.witnessUtxo) {
                return Either.Left(ParseFailure.InvalidTxInput("witness utxo does not match non-witness utxo output"))
            }
            txIns.add(TxIn(outPoint, ByteVector.empty, f.sequence ?: TxIn.SEQUENCE_FINAL))
        }

        // Synthesize the transaction outputs from the per-output amount/script fields.
        val txOuts = ArrayList<TxOut>(parsedOutputs.size)
        for (f in parsedOutputs) {
            val amount = f.amount ?: return Either.Left(ParseFailure.InvalidTxOutput("PSBT_OUT_AMOUNT is required in PSBTv2"))
            val hasSpInfo = f.unknown.any { it.key.size() == 1 && it.key[0] == 0x09.toByte() }
            if (f.script == null && !hasSpInfo) {
                return Either.Left(ParseFailure.InvalidTxOutput("either PSBT_OUT_SCRIPT or PSBT_OUT_SP_V0_INFO is required in PSBTv2"))
            }
            txOuts.add(TxOut(Satoshi(amount), f.script ?: ByteVector.empty))
        }

        val locktime = computeV2Locktime(parsedInputs, fallbackLocktime)
        val tx = Transaction(txVersion, txIns, txOuts, locktime)
        val global = Global(psbtVersion, tx, xpubs, unknownGlobal, fallbackLocktime, txModifiable)

        val inputs = txIns.indices.map { idx ->
            val f = parsedInputs[idx]
            createInput(
                txIns[idx], f.nonWitnessUtxo, f.witnessUtxo, f.sighashType, f.partialSigs, f.derivationPaths,
                f.redeemScript, f.witnessScript, f.scriptSig, f.scriptWitness,
                f.ripemd160, f.sha256, f.hash160, f.hash256,
                f.taprootKeySignature, f.taprootDerivationPaths, f.taprootInternalKey, f.unknown
            )
        }
        val outputs = parsedOutputs.map { f ->
            createOutput(f.redeemScript, f.witnessScript, f.derivationPaths, f.taprootInternalKey, f.taprootDerivationPaths, f.unknown)
        }

        return Either.Right(Psbt(global, inputs, outputs))
    }

    /** Parsed fields of a PSBT v2 input map (v0 fields + the v2 outpoint/sequence fields). */
    private class ParsedV2Input(
        val nonWitnessUtxo: Transaction?,
        val witnessUtxo: TxOut?,
        val sighashType: Int?,
        val partialSigs: Map<PublicKey, ByteVector>,
        val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        val redeemScript: List<ScriptElt>?,
        val witnessScript: List<ScriptElt>?,
        val scriptSig: List<ScriptElt>?,
        val scriptWitness: ScriptWitness?,
        val ripemd160: Set<ByteVector>,
        val sha256: Set<ByteVector>,
        val hash160: Set<ByteVector>,
        val hash256: Set<ByteVector>,
        val taprootKeySignature: ByteVector?,
        val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
        val taprootInternalKey: XonlyPublicKey?,
        val unknown: List<DataEntry>,
        val prevTxid: ByteVector32?,
        val prevIndex: Long?,
        val sequence: Long?
    )

    /** Parsed fields of a PSBT v2 output map (v0 fields + the v2 amount/script fields). */
    private class ParsedV2Output(
        val redeemScript: List<ScriptElt>?,
        val witnessScript: List<ScriptElt>?,
        val derivationPaths: Map<PublicKey, KeyPathWithMaster>,
        val taprootInternalKey: XonlyPublicKey?,
        val taprootDerivationPaths: Map<XonlyPublicKey, TaprootBip32DerivationPath>,
        val unknown: List<DataEntry>,
        val amount: Long?,
        val script: ByteVector?
    )

    private fun parseV2Input(entries: List<DataEntry>): Either<ParseFailure, ParsedV2Input> {
        val keyTypes = setOf<Byte>(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x06, 0x07, 0x08, 0x13, 0x16, 0x17, 0x0a, 0x0b, 0x0c, 0x0d, 0x0e, 0x0f, 0x10)
        val (known, unknown) = entries.partition { keyTypes.contains(it.key[0]) }
        val nonWitnessUtxo = known.find { it.key[0] == 0x00.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("non-witness utxo key must contain exactly 1 byte"))
                else -> runCatching { Transaction.read(it.value.bytes) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse transaction")) }
            }
        }
        val witnessUtxo = known.find { it.key[0] == 0x01.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("witness utxo key must contain exactly 1 byte"))
                else -> runCatching { TxOut.read(it.value.bytes) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse transaction output")) }
            }
        }
        val partialSigs = known.filter { it.key[0] == 0x02.toByte() }.map {
            when {
                it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxInput("public key must contain exactly 33 bytes"))
                else -> PublicKey(it.key.drop(1)) to it.value
            }
        }.toMap()
        val sighashType = known.find { it.key[0] == 0x03.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("sighash type key must contain exactly 1 byte"))
                it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidTxInput("sighash type must contain exactly 4 bytes"))
                else -> Pack.int32LE(it.value.bytes)
            }
        }
        val redeemScript = known.find { it.key[0] == 0x04.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("redeem script key must contain exactly 1 byte"))
                else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse redeem script")) }
            }
        }
        val witnessScript = known.find { it.key[0] == 0x05.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("witness script key must contain exactly 1 byte"))
                else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse witness script")) }
            }
        }
        val derivationPaths = known.filter { it.key[0] == 0x06.toByte() }.map {
            when {
                it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxInput("bip32 derivation public key must contain exactly 33 bytes"))
                it.value.size() < 4 || it.value.size() % 4 != 0 -> return Either.Left(ParseFailure.InvalidTxInput("bip32 derivation must contain master key fingerprint and child indexes"))
                else -> {
                    val publicKey = PublicKey(it.key.drop(1))
                    val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                    val childCount = (it.value.size() / 4) - 1
                    val derivationPath = KeyPath((0 until childCount).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                    publicKey to KeyPathWithMaster(masterKeyFingerprint, derivationPath)
                }
            }
        }.toMap()
        val scriptSig = known.find { it.key[0] == 0x07.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("script sig key must contain exactly 1 byte"))
                else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse script sig")) }
            }
        }
        val scriptWitness = known.find { it.key[0] == 0x08.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("script witness key must contain exactly 1 byte"))
                else -> runCatching { ScriptWitness.read(it.value.bytes) }.getOrElse { return Either.Left(ParseFailure.InvalidTxInput("failed to parse script witness")) }
            }
        }
        val taprootKeySignature = known.find { it.key[0] == 0x13.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("taproot keypath signature key must contain exactly 1 byte"))
                it.value.size() != 64 && it.value.size() != 65 -> return Either.Left(ParseFailure.InvalidTxInput("taproot keypath signature must contain 64 or 65 bytes"))
                else -> it.value
            }
        }
        val taprootDerivationPaths = known.filter { it.key[0] == 0x16.toByte() }.map {
            when {
                it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("taproot derivation path key must contain exactly 32 bytes"))
                else -> {
                    val xonlyPublicKey = XonlyPublicKey(it.key.drop(1).toByteArray().byteVector32())
                    val path = TaprootBip32DerivationPath.read(it.value.toByteArray())
                    xonlyPublicKey to path
                }
            }
        }.toMap()
        val taprootInternalKey = known.find { it.key[0] == 0x17.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("taproot internal key entry must have an empty key"))
                it.value.size() != 32 -> return Either.Left(ParseFailure.InvalidTxInput("taproot internal key entry must have a 32 bytes value"))
                else -> XonlyPublicKey(it.value.toByteArray().byteVector32())
            }
        }
        val ripemd160Preimages = known.filter { it.key[0] == 0x0a.toByte() }.map {
            when {
                it.key.size() != 21 -> return Either.Left(ParseFailure.InvalidTxInput("ripemd160 hash must contain exactly 20 bytes"))
                !it.key.drop(1).contentEquals(Crypto.ripemd160(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid ripemd160 preimage"))
                else -> it.value
            }
        }.toSet()
        val sha256Preimages = known.filter { it.key[0] == 0x0b.toByte() }.map {
            when {
                it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("sha256 hash must contain exactly 32 bytes"))
                !it.key.drop(1).contentEquals(Crypto.sha256(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid sha256 preimage"))
                else -> it.value
            }
        }.toSet()
        val hash160Preimages = known.filter { it.key[0] == 0x0c.toByte() }.map {
            when {
                it.key.size() != 21 -> return Either.Left(ParseFailure.InvalidTxInput("hash160 hash must contain exactly 20 bytes"))
                !it.key.drop(1).contentEquals(Crypto.hash160(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid hash160 preimage"))
                else -> it.value
            }
        }.toSet()
        val hash256Preimages = known.filter { it.key[0] == 0x0d.toByte() }.map {
            when {
                it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxInput("hash256 hash must contain exactly 32 bytes"))
                !it.key.drop(1).contentEquals(Crypto.hash256(it.value)) -> return Either.Left(ParseFailure.InvalidTxInput("invalid hash256 preimage"))
                else -> it.value
            }
        }.toSet()
        val prevTxid = known.find { it.key[0] == 0x0e.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("previous txid key must contain exactly 1 byte"))
                it.value.size() != 32 -> return Either.Left(ParseFailure.InvalidTxInput("previous txid must contain exactly 32 bytes"))
                else -> it.value.toByteArray().byteVector32()
            }
        }
        val prevIndex = known.find { it.key[0] == 0x0f.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("output index key must contain exactly 1 byte"))
                it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidTxInput("output index must contain exactly 4 bytes"))
                else -> Pack.int32LE(it.value.bytes).toUInt().toLong()
            }
        }
        val sequence = known.find { it.key[0] == 0x10.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxInput("sequence key must contain exactly 1 byte"))
                it.value.size() != 4 -> return Either.Left(ParseFailure.InvalidTxInput("sequence must contain exactly 4 bytes"))
                else -> Pack.int32LE(it.value.bytes).toUInt().toLong()
            }
        }
        return Either.Right(
            ParsedV2Input(
                nonWitnessUtxo, witnessUtxo, sighashType, partialSigs, derivationPaths, redeemScript, witnessScript,
                scriptSig, scriptWitness, ripemd160Preimages, sha256Preimages, hash160Preimages, hash256Preimages,
                taprootKeySignature, taprootDerivationPaths, taprootInternalKey, unknown, prevTxid, prevIndex, sequence
            )
        )
    }

    private fun parseV2Output(entries: List<DataEntry>): Either<ParseFailure, ParsedV2Output> {
        val keyTypes = setOf<Byte>(0x00, 0x01, 0x02, 0x03, 0x04, 0x05, 0x07)
        val (known, unknown) = entries.partition { keyTypes.contains(it.key[0]) }
        val redeemScript = known.find { it.key[0] == 0x00.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("redeem script key must contain exactly 1 byte"))
                else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxOutput("failed to parse redeem script")) }
            }
        }
        val witnessScript = known.find { it.key[0] == 0x01.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("witness script key must contain exactly 1 byte"))
                else -> runCatching { Script.parse(it.value) }.getOrElse { return Either.Left(ParseFailure.InvalidTxOutput("failed to parse witness script")) }
            }
        }
        val derivationPaths = known.filter { it.key[0] == 0x02.toByte() }.map {
            when {
                it.key.size() != 34 -> return Either.Left(ParseFailure.InvalidTxOutput("bip32 derivation public key must contain exactly 33 bytes"))
                it.value.size() < 4 || it.value.size() % 4 != 0 -> return Either.Left(ParseFailure.InvalidTxOutput("bip32 derivation must contain master key fingerprint and child indexes"))
                else -> {
                    val publicKey = PublicKey(it.key.drop(1))
                    val masterKeyFingerprint = Pack.int32BE(it.value.take(4).toByteArray()).toUInt().toLong()
                    val childCount = (it.value.size() / 4) - 1
                    val derivationPath = KeyPath((0 until childCount).map { i -> Pack.int32LE(it.value.slice(4 * (i + 1), 4 * (i + 2)).toByteArray()).toUInt().toLong() })
                    publicKey to KeyPathWithMaster(masterKeyFingerprint, derivationPath)
                }
            }
        }.toMap()
        val amount = known.find { it.key[0] == 0x03.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("amount key must contain exactly 1 byte"))
                it.value.size() != 8 -> return Either.Left(ParseFailure.InvalidTxOutput("amount must contain exactly 8 bytes"))
                else -> Pack.int64LE(it.value.bytes)
            }
        }
        val script = known.find { it.key[0] == 0x04.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("script key must contain exactly 1 byte"))
                else -> it.value
            }
        }
        val taprootInternalKey = known.find { it.key[0] == 0x05.toByte() }?.let {
            when {
                it.key.size() != 1 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot internal key entry must have an empty key"))
                it.value.size() != 32 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot internal key entry must have a 32 bytes value"))
                else -> XonlyPublicKey(it.value.toByteArray().byteVector32())
            }
        }
        val taprootDerivationPaths = known.filter { it.key[0] == 0x07.toByte() }.map {
            when {
                it.key.size() != 33 -> return Either.Left(ParseFailure.InvalidTxOutput("taproot derivation path key must contain exactly 32 bytes"))
                else -> {
                    val xonlyPublicKey = XonlyPublicKey(it.key.drop(1).toByteArray().byteVector32())
                    val path = TaprootBip32DerivationPath.read(it.value.toByteArray())
                    xonlyPublicKey to path
                }
            }
        }.toMap()
        return Either.Right(ParsedV2Output(redeemScript, witnessScript, derivationPaths, taprootInternalKey, taprootDerivationPaths, unknown, amount, script))
    }

    /**
     * Determines the synthesized transaction locktime per BIP-370: the max of any per-input
     * required locktimes (height preferred when mixed), else the fallback locktime, else 0.
     * Per-input `PSBT_IN_REQUIRED_TIME_LOCKTIME` (0x11) / `_HEIGHT_LOCKTIME` (0x12) are left in
     * each input's `unknown` (preserved on write); we read them here only to compute locktime.
     */
    private fun computeV2Locktime(inputs: List<ParsedV2Input>, fallbackLocktime: Long?): Long {
        val fallback = fallbackLocktime ?: 0L
        fun required(unknown: List<DataEntry>, keyType: Byte): Long? =
            unknown.firstOrNull { it.key.size() == 1 && it.key[0] == keyType && it.value.size() == 4 }
                ?.let { Pack.int32LE(it.value.bytes).toUInt().toLong() }
        val heights = inputs.map { required(it.unknown, 0x12) }
        val times = inputs.map { required(it.unknown, 0x11) }
        val maxHeight = heights.filterNotNull().maxOrNull()
        val maxTime = times.filterNotNull().maxOrNull()
        return when {
            maxHeight == null && maxTime == null -> fallback
            maxHeight != null && heights.all { it != null } -> maxHeight
            maxTime != null && times.all { it != null } -> maxTime
            maxHeight != null -> maxHeight
            else -> maxTime ?: fallback
        }
    }


    private sealed class ReadEntryFailure {
        object DuplicateKeys : ReadEntryFailure()
        object InvalidData : ReadEntryFailure()
        object EndOfDataMap : ReadEntryFailure()
    }

    private fun readDataMap(input: com.gorunjinian.metrovault.lib.bitcoin.io.Input, entries: List<DataEntry> = listOf()): Either<ReadEntryFailure, List<DataEntry>> {
        return when (val result = readDataEntry(input)) {
            is Either.Right -> readDataMap(input, entries + result.value)
            is Either.Left -> when (result.value) {
                is ReadEntryFailure.EndOfDataMap -> {
                    if (entries.map { it.key }.toSet().size != entries.size) {
                        Either.Left(ReadEntryFailure.DuplicateKeys)
                    } else {
                        Either.Right(entries)
                    }
                }

                is ReadEntryFailure.InvalidData -> Either.Left(ReadEntryFailure.InvalidData)
                else -> Either.Left(result.value)
            }
        }
    }

    private fun readDataEntry(input: com.gorunjinian.metrovault.lib.bitcoin.io.Input): Either<ReadEntryFailure, DataEntry> {
        if (input.availableBytes == 0) return Either.Left(ReadEntryFailure.InvalidData)
        val keyLength = BtcSerializer.varint(input).toInt()
        if (keyLength == 0) return Either.Left(ReadEntryFailure.EndOfDataMap)
        val key = input.readNBytes(keyLength) ?: return Either.Left(ReadEntryFailure.InvalidData)

        if (input.availableBytes == 0) return Either.Left(ReadEntryFailure.InvalidData)
        val valueLength = BtcSerializer.varint(input).toInt()
        val value = input.readNBytes(valueLength) ?: return Either.Left(ReadEntryFailure.InvalidData)

        return Either.Right(DataEntry(ByteVector(key), ByteVector(value)))
    }
}
