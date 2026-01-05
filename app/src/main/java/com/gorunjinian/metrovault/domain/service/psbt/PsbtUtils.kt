package com.gorunjinian.metrovault.domain.service.psbt

import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.*
import com.gorunjinian.metrovault.lib.bitcoin.utils.Either
import java.io.ByteArrayOutputStream

/**
 * Shared utilities and data classes for PSBT operations.
 */
internal object PsbtUtils {
    private const val TAG = "PsbtUtils"

    /**
     * Parses a PSBT from Base64, with fallback to strip malformed global xpubs.
     * @return Parsed Psbt or null if parsing fails
     */
    fun parsePsbt(psbtBase64: String): Psbt? {
        val psbtBytes = try {
            android.util.Base64.decode(psbtBase64, android.util.Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Base64 decode failed: ${e.message}")
            return null
        }

        return when (val psbtResult = Psbt.read(psbtBytes)) {
            is Either.Right -> psbtResult.value
            is Either.Left -> {
                // Retry with stripped global xpubs
                val strippedBytes = stripGlobalXpubs(psbtBytes) ?: return null
                when (val retryResult = Psbt.read(strippedBytes)) {
                    is Either.Right -> retryResult.value
                    is Either.Left -> null
                }
            }
        }
    }

    /**
     * Extracts the scriptPubKey from a PSBT input.
     */
    fun getInputScriptPubKey(input: Input): ByteVector? {
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.txOut.publicKeyScript
            is Input.WitnessInput.FinalizedWitnessInput -> input.txOut.publicKeyScript
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> {
                input.inputTx.txOut.getOrNull(input.outputIndex)?.publicKeyScript
            }
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> {
                input.inputTx.txOut.getOrNull(input.outputIndex)?.publicKeyScript
            }
            is Input.PartiallySignedInputWithoutUtxo -> null
            is Input.FinalizedInputWithoutUtxo -> null
        }
    }

    /**
     * Extracts the satoshi value from a PSBT input.
     */
    fun getInputValue(input: Input?): Long {
        return when (input) {
            is Input.WitnessInput.PartiallySignedWitnessInput -> input.amount.toLong()
            is Input.WitnessInput.FinalizedWitnessInput -> input.amount.toLong()
            is Input.NonWitnessInput.PartiallySignedNonWitnessInput -> input.amount.toLong()
            is Input.NonWitnessInput.FinalizedNonWitnessInput -> input.amount.toLong()
            is Input.PartiallySignedInputWithoutUtxo -> 0L
            is Input.FinalizedInputWithoutUtxo -> 0L
            null -> 0L
        }
    }

    /**
     * Extracts an address string from a transaction output.
     */
    fun extractAddressFromOutput(txOut: TxOut, chainHash: BlockHash): String {
        return try {
            val scriptPubKey = Script.parse(txOut.publicKeyScript)
            when (val result = Bitcoin.addressFromPublicKeyScript(chainHash, scriptPubKey)) {
                is Either.Right -> result.value
                is Either.Left -> "Unknown"
            }
        } catch (_: Exception) {
            "Unknown"
        }
    }

    /**
     * Strips global xpub entries (keytype 0x01) from PSBT bytes.
     * This is a workaround for PSBTs with malformed xpub metadata that fail strict validation.
     * The global xpubs are not required for signing - we use derivation paths from inputs instead.
     */
    fun stripGlobalXpubs(psbtBytes: ByteArray): ByteArray? {
        return try {
            // PSBT structure: magic (5) + global section + inputs + outputs
            if (psbtBytes.size < 5) return null

            // Verify magic header: "psbt\xff"
            if (psbtBytes[0] != 0x70.toByte() ||
                psbtBytes[1] != 0x73.toByte() ||
                psbtBytes[2] != 0x62.toByte() ||
                psbtBytes[3] != 0x74.toByte() ||
                psbtBytes[4] != 0xff.toByte()) {
                return null
            }

            val output = ByteArrayOutputStream()
            // Write magic header
            output.write(psbtBytes, 0, 5)

            var pos = 5

            // Parse global section, excluding xpub entries (keytype 0x01)
            while (pos < psbtBytes.size) {
                // Read key length
                val (keyLen, keyLenBytes) = readVarint(psbtBytes, pos)
                if (keyLen == 0L) {
                    // End of global section - write separator
                    output.write(0x00)
                    pos += keyLenBytes
                    break
                }

                pos += keyLenBytes
                if (pos + keyLen > psbtBytes.size) return null

                val keyStart = pos
                val keyFirstByte = psbtBytes[pos]
                pos += keyLen.toInt()

                // Read value length
                val (valueLen, valueLenBytes) = readVarint(psbtBytes, pos)
                pos += valueLenBytes
                if (pos + valueLen > psbtBytes.size) return null

                val valueStart = pos
                pos += valueLen.toInt()

                // Skip xpub entries (keytype 0x01), keep everything else
                if (keyFirstByte != 0x01.toByte()) {
                    writeVarint(keyLen, output)
                    output.write(psbtBytes, keyStart, keyLen.toInt())
                    writeVarint(valueLen, output)
                    output.write(psbtBytes, valueStart, valueLen.toInt())
                } else {
                    Log.d(TAG, "Stripping global xpub entry")
                }
            }

            // Copy the rest of the PSBT (inputs and outputs) unchanged
            if (pos < psbtBytes.size) {
                output.write(psbtBytes, pos, psbtBytes.size - pos)
            }

            output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Error stripping global xpubs: ${e.message}")
            null
        }
    }

    /**
     * Reads a varint from byte array at given position.
     * @return Pair of (value, bytesRead)
     */
    fun readVarint(bytes: ByteArray, pos: Int): Pair<Long, Int> {
        if (pos >= bytes.size) return Pair(0, 0)
        val first = bytes[pos].toInt() and 0xFF
        return when {
            first < 0xFD -> Pair(first.toLong(), 1)
            first == 0xFD -> {
                if (pos + 2 >= bytes.size) return Pair(0, 0)
                val v = ((bytes[pos + 1].toInt() and 0xFF) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 8)).toLong()
                Pair(v, 3)
            }
            first == 0xFE -> {
                if (pos + 4 >= bytes.size) return Pair(0, 0)
                val v = ((bytes[pos + 1].toInt() and 0xFF) or
                        ((bytes[pos + 2].toInt() and 0xFF) shl 8) or
                        ((bytes[pos + 3].toInt() and 0xFF) shl 16) or
                        ((bytes[pos + 4].toInt() and 0xFF) shl 24)).toLong() and 0xFFFFFFFFL
                Pair(v, 5)
            }
            else -> {
                if (pos + 8 >= bytes.size) return Pair(0, 0)
                var v = 0L
                for (i in 0..7) {
                    v = v or ((bytes[pos + 1 + i].toLong() and 0xFF) shl (i * 8))
                }
                Pair(v, 9)
            }
        }
    }

    /**
     * Writes a varint to output stream.
     */
    fun writeVarint(value: Long, output: ByteArrayOutputStream) {
        when {
            value < 0xFD -> output.write(value.toInt())
            value <= 0xFFFF -> {
                output.write(0xFD)
                output.write((value and 0xFF).toInt())
                output.write(((value shr 8) and 0xFF).toInt())
            }
            value <= 0xFFFFFFFFL -> {
                output.write(0xFE)
                output.write((value and 0xFF).toInt())
                output.write(((value shr 8) and 0xFF).toInt())
                output.write(((value shr 16) and 0xFF).toInt())
                output.write(((value shr 24) and 0xFF).toInt())
            }
            else -> {
                output.write(0xFF)
                for (i in 0..7) {
                    output.write(((value shr (i * 8)) and 0xFF).toInt())
                }
            }
        }
    }
}

/**
 * Internal data class for address key information used in PSBT signing.
 */
internal data class AddressKeyInfo(
    val changeIndex: Long,
    val addressIndex: Long,
    val publicKey: PublicKey
)

/**
 * Internal data class for input signature analysis results.
 */
internal data class InputSignatureInfo(
    val isMultisig: Boolean,
    val requiredSignatures: Int,
    val totalSigners: Int,
    val currentSignatures: Int
)
