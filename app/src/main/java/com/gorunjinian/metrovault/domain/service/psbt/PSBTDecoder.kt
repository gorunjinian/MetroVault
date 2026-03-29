package com.gorunjinian.metrovault.domain.service.psbt

import android.util.Base64
import android.util.Log
import com.gorunjinian.bbqr.FileType
import com.gorunjinian.bbqr.Joined
import com.gorunjinian.bbqr.SplitOptions
import com.gorunjinian.bbqr.SplitResult
import com.gorunjinian.bcur.UR

/**
 * Decodes PSBTs from various QR code formats used by different wallets.
 *
 * Supported formats:
 * - Raw Base64: Standard PSBT encoding
 * - BBQr: Coinkite's "Better Bitcoin QR" format (B$...)
 * - BC-UR: Blockchain Commons Uniform Resources (ur:psbt/ and ur:crypto-psbt/)
 * - Hex: Raw hex-encoded PSBT
 */
@Suppress("KDocUnresolvedReference", "unused")
object PSBTDecoder {

    private const val TAG = "PSBTDecoder"

    // PSBT magic bytes in hex: 70736274ff ("psbt" + 0xff)
    private val PSBT_MAGIC = byteArrayOf(0x70, 0x73, 0x62, 0x74, 0xff.toByte())

    /**
     * Decodes a PSBT from any supported format to Base64.
     *
     * @param input Raw scanned QR content (may be partial for animated QRs)
     * @return Base64-encoded PSBT, or null if decoding fails
     */
    fun decode(input: String): String? {
        val trimmed = input.trim()

        return try {
            when {
                // BBQr format: starts with "B$"
                trimmed.startsWith("B$") -> decodeBBQrSingle(trimmed)

                // BC-UR psbt format (modern, preferred)
                trimmed.lowercase().startsWith("ur:psbt/") -> decodeURPsbt(trimmed)

                // BC-UR crypto-psbt format (legacy, still widely used)
                trimmed.lowercase().startsWith("ur:crypto-psbt/") -> decodeURPsbt(trimmed)

                // BC-UR bytes format (generic)
                trimmed.lowercase().startsWith("ur:bytes/") -> decodeURBytes(trimmed)

                // Raw Base64 PSBT - validate it starts with correct magic
                isValidBase64Psbt(trimmed) -> trimmed

                // Try hex encoding
                isValidHexPsbt(trimmed) -> hexToBase64(trimmed)

                else -> {
                    Log.w(TAG, "Unknown PSBT format, attempting Base64 decode")
                    if (isValidBase64Psbt(trimmed)) trimmed else null
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode PSBT: ${e.message}")
            null
        }
    }

    /**
     * Decodes a complete BBQr message (for non-animated single QR) using bbqr-kotlin.
     */
    private fun decodeBBQrSingle(input: String): String? {
        return try {
            val joined = Joined.fromParts(listOf(input))
            val bytes = joined.data

            if (bytes.size < 5 || !bytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
                Log.w(TAG, "Invalid PSBT magic bytes after BBQr decode")
                return null
            }

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode BBQr single: ${e.message}")
            null
        }
    }

    /**
     * Parses BBQr frame header info.
     * @return Triple(partNumber: 1-indexed, totalParts, rawData) or null
     */
    fun parseBBQrFrame(input: String): Triple<Int, Int, String>? {
        if (!input.startsWith("B$") || input.length < 8) return null

        try {
            val totalBase36 = input.substring(4, 6)
            val partBase36 = input.substring(6, 8)

            val total = totalBase36.toInt(36)
            val part = partBase36.toInt(36)

            // BBQr parts are 0-indexed, convert to 1-indexed
            return Triple(part + 1, total, input)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse BBQr header: ${e.message}")
            return null
        }
    }

    /**
     * Reassembles BBQr frames and decodes to Base64 PSBT using bbqr-kotlin.
     */
    fun decodeBBQrFrames(frames: List<String>): String? {
        if (frames.isEmpty()) return null

        return try {
            val joined = Joined.fromParts(frames)
            val bytes = joined.data

            if (bytes.size < 5 || !bytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
                Log.w(TAG, "Invalid PSBT magic after BBQr reassembly")
                return null
            }

            Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode BBQr frames: ${e.message}")
            null
        }
    }

    /**
     * Decodes BC-UR PSBT format (both ur:psbt/ and ur:crypto-psbt/) using bcur-kotlin.
     */
    private fun decodeURPsbt(input: String): String? {
        return try {
            val ur = UR.parse(input)
            val psbtBytes = ur.toBytes()

            if (psbtBytes.size >= 5 && psbtBytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
                Base64.encodeToString(psbtBytes, Base64.NO_WRAP)
            } else {
                Log.w(TAG, "Invalid PSBT magic in UR content (got ${psbtBytes.take(5).map { it.toInt() and 0xFF }})")
                null
            }
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode UR PSBT: ${e.message}")
            null
        }
    }

    /**
     * Parses BC-UR frame info for both ur:psbt/ and ur:crypto-psbt/ formats.
     *
     * UR 2.0 uses fountain codes where format is seqNum-fragLen (e.g., 19-6, 20-6).
     * The seqNum continuously increments and can exceed fragLen.
     * For fountain codes, we need fragLen unique frames to reconstruct.
     *
     * Format: ur:[type]/[seq-fragLen]/data or ur:[type]/data (single part)
     */
    fun parseURFrame(input: String): Triple<Int, Int, String>? {
        val lower = input.lowercase()

        val (_, content) = when {
            lower.startsWith("ur:psbt/") -> "ur:psbt/" to lower.removePrefix("ur:psbt/")
            lower.startsWith("ur:crypto-psbt/") -> "ur:crypto-psbt/" to lower.removePrefix("ur:crypto-psbt/")
            else -> return null
        }

        val parts = content.split("/")

        return try {
            when {
                // Multi-part with "-" format (fountain codes or simple): seqNum-fragLen/data
                parts.size >= 2 && parts[0].contains("-") && !parts[0].contains("of") -> {
                    val seqParts = parts[0].split("-")
                    val seqNum = seqParts[0].toInt()
                    val fragLen = seqParts[1].toInt()

                    val isFountainCode = seqNum > fragLen

                    if (isFountainCode) {
                        Log.d(TAG, "Detected UR fountain code: seq=$seqNum, fragLen=$fragLen")
                        Triple(seqNum, fragLen, input)
                    } else {
                        Log.d(TAG, "Parsed UR frame $seqNum of $fragLen")
                        Triple(seqNum, fragLen, input)
                    }
                }
                // Multi-part with "of" format: 1of5/checksum/data
                parts.size >= 2 && parts[0].contains("of") -> {
                    val seqParts = parts[0].split("of")
                    val seq = seqParts[0].toInt()
                    val total = seqParts[1].toInt()
                    Log.d(TAG, "Parsed UR frame $seq of $total (of-format)")
                    Triple(seq, total, input)
                }
                // Single part (no sequence info) - complete UR in one frame
                else -> {
                    Log.d(TAG, "Single-part UR detected")
                    Triple(1, 1, input)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse UR frame: ${e.message}")
            null
        }
    }

    /**
     * Decodes BC-UR bytes format (generic) using bcur-kotlin.
     */
    private fun decodeURBytes(input: String): String? {
        return try {
            val ur = UR.parse(input)
            Base64.encodeToString(ur.toBytes(), Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.w(TAG, "Failed to decode UR bytes: ${e.message}")
            null
        }
    }

    /**
     * Encodes a Base64 PSBT to BBQr format using bbqr-kotlin.
     *
     * @return List of BBQr frame strings, or null on error
     */
    fun encodeToBBQr(psbtBase64: String, options: SplitOptions = SplitOptions()): List<String>? {
        return try {
            val psbtBytes = Base64.decode(psbtBase64, Base64.NO_WRAP)
            val result = SplitResult.fromData(psbtBytes, FileType.Psbt, options)
            result.parts
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode PSBT to BBQr: ${e.message}")
            null
        }
    }

    // ==================== Helper Functions ====================

    private fun isValidBase64Psbt(input: String): Boolean {
        return try {
            val bytes = Base64.decode(input, Base64.DEFAULT)
            bytes.size >= 5 && bytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)
        } catch (_: Exception) {
            false
        }
    }

    private fun isValidHexPsbt(input: String): Boolean {
        if (input.length < 10 || input.length % 2 != 0) return false
        return input.lowercase().startsWith("70736274ff")
    }

    private fun hexToBase64(hex: String): String? {
        val bytes = decodeHex(hex) ?: return null
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
    }

    private fun decodeHex(hex: String): ByteArray? {
        return try {
            val clean = hex.replace(" ", "").replace("\n", "")
            if (clean.length % 2 != 0) return null

            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) {
            null
        }
    }
}
