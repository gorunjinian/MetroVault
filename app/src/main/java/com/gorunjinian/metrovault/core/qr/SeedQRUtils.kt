package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.vaultovich.BIP39Wordlist
import com.gorunjinian.vaultovich.MnemonicCode
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest

/**
 * SeedQR encoding and decoding utilities.
 *
 * Implements both Standard SeedQR and CompactSeedQR formats as specified in:
 * https://github.com/SeedSigner/seedsigner/blob/dev/docs/seed_qr/README.md
 *
 * Standard SeedQR:
 * - Encodes each word as a 4-digit zero-padded index (0000-2047)
 * - 12 words = 48 digits, 24 words = 96 digits
 * - Uses QR Numeric mode for efficiency
 * - Human-readable when decoded by any QR reader
 *
 * CompactSeedQR:
 * - Encodes entropy bits directly (excluding checksum)
 * - 12 words = 128 bits (16 bytes), 24 words = 256 bits (32 bytes)
 * - Uses QR Binary mode for smallest QR size
 * - Not human-readable; requires specialized decoder
 */
object SeedQRUtils {

    /**
     * Converts a mnemonic word list to a Standard SeedQR digit string.
     *
     * Each word is converted to its 4-digit zero-padded index in the BIP39 wordlist.
     * Example: "vacuum" (index 1924) -> "1924", "bridge" (index 222) -> "0222"
     *
     * @param words List of mnemonic words (12 or 24 words)
     * @return Digit string (48 or 96 characters) or null on error
     */
    fun mnemonicToStandardSeedQR(words: List<String>): String? {
        if (words.size != 12 && words.size != 24) return null

        val wordlist = BIP39Wordlist.getEnglishWordlist()
        if (wordlist.isEmpty()) return null

        return try {
            words.joinToString("") { word ->
                val index = wordlist.indexOf(word.lowercase())
                if (index < 0) throw IllegalArgumentException("Invalid word: $word")
                index.toString().padStart(4, '0')
            }
        } catch (e: Exception) {
            AppLog.e("SeedQRUtils") { "Error encoding to Standard SeedQR: ${e.message}" }
            null
        }
    }

    /**
     * Decodes a Standard SeedQR digit string back to mnemonic words.
     *
     * The digit string is split into 4-digit groups, each representing a word index.
     * Validates the resulting mnemonic using BIP39 checksum.
     *
     * @param digitString The digit string from scanning a Standard SeedQR (48 or 96 digits)
     * @return List of mnemonic words or null on error/invalid checksum
     */
    fun standardSeedQRToMnemonic(digitString: String): List<String>? {
        // Validate length: 48 digits = 12 words, 96 digits = 24 words
        if (digitString.length != 48 && digitString.length != 96) return null
        if (!digitString.all { it.isDigit() }) return null

        val wordlist = BIP39Wordlist.getEnglishWordlist()
        if (wordlist.isEmpty()) return null

        return try {
            val words = digitString.chunked(4).map { indexStr ->
                val index = indexStr.toInt()
                if (index !in 0..<2048) throw IllegalArgumentException("Invalid index: $index")
                wordlist[index]
            }

            // Validate checksum
            MnemonicCode.validate(words)
            words
        } catch (e: Exception) {
            AppLog.e("SeedQRUtils") { "Error decoding Standard SeedQR: ${e.message}" }
            null
        }
    }

    // ============== CompactSeedQR ==============

    /**
     * Converts a mnemonic word list to a CompactSeedQR byte array.
     *
     * Each word's 11-bit index is concatenated, then the last 4/8 checksum bits
     * are stripped to produce 128 bits (16 bytes) for 12 words or 256 bits (32 bytes) for 24 words.
     *
     * @param words List of mnemonic words (12 or 24 words)
     * @return Byte array (16 or 32 bytes) or null on error
     */
    fun mnemonicToCompactSeedQR(words: List<String>): ByteArray? {
        if (words.size != 12 && words.size != 24) return null

        val wordlist = BIP39Wordlist.getEnglishWordlist()
        if (wordlist.isEmpty()) return null

        return try {
            // Convert words to indices
            val indices = words.map { word ->
                val index = wordlist.indexOf(word.lowercase())
                if (index < 0) throw IllegalArgumentException("Invalid word: $word")
                index
            }

            // Convert indices to 11-bit binary values and concatenate
            val bits = indices.flatMap { index ->
                (10 downTo 0).map { bit -> (index shr bit) and 1 == 1 }
            }

            // Strip checksum bits (4 bits for 12 words, 8 bits for 24 words)
            val checksumBits = words.size / 3  // 4 for 12 words, 8 for 24 words
            val entropyBits = bits.dropLast(checksumBits)

            // Convert bits to bytes
            entropyBits.chunked(8).map { byteBits ->
                byteBits.foldIndexed(0) { i, acc, bit ->
                    if (bit) acc or (1 shl (7 - i)) else acc
                }.toByte()
            }.toByteArray()
        } catch (e: Exception) {
            AppLog.e("SeedQRUtils") { "Error encoding to CompactSeedQR: ${e.message}" }
            null
        }
    }

    /**
     * Decodes a CompactSeedQR byte array back to mnemonic words.
     *
     * The byte array represents the entropy without checksum. This function:
     * 1. Computes the checksum from the entropy
     * 2. Concatenates entropy + checksum bits
     * 3. Splits into 11-bit groups to get word indices
     * 4. Looks up words and validates the result
     *
     * @param bytes The byte array from scanning a CompactSeedQR (16 or 32 bytes)
     * @return List of mnemonic words or null on error
     */
    fun compactSeedQRToMnemonic(bytes: ByteArray): List<String>? {
        // Validate length: 16 bytes = 12 words, 32 bytes = 24 words
        if (bytes.size != 16 && bytes.size != 32) return null

        val wordlist = BIP39Wordlist.getEnglishWordlist()
        if (wordlist.isEmpty()) return null

        return try {
            // Convert bytes to bits (handle signed bytes properly with 'and 0xFF')
            val entropyBits = bytes.flatMap { byte ->
                val unsignedByte = byte.toInt() and 0xFF
                (7 downTo 0).map { bit -> (unsignedByte shr bit) and 1 == 1 }
            }

            // Compute SHA-256 checksum
            val sha256 = MessageDigest.getInstance("SHA-256").digest(bytes)

            // Take first N bits of checksum (N = entropy_bits / 32)
            // For 128 bits (16 bytes): 128/32 = 4 checksum bits
            // For 256 bits (32 bytes): 256/32 = 8 checksum bits
            val checksumBitCount = entropyBits.size / 32
            val checksumByte = sha256[0].toInt() and 0xFF

            val checksum = (7 downTo (8 - checksumBitCount)).map { bit ->
                (checksumByte shr bit) and 1 == 1
            }

            // Concatenate entropy + checksum
            val allBits = entropyBits + checksum

            // Split into 11-bit groups and convert to word indices
            val words = allBits.chunked(11).map { indexBits ->
                if (indexBits.size != 11) {
                    throw IllegalArgumentException("Incomplete 11-bit group")
                }
                val index = indexBits.foldIndexed(0) { i, acc, bit ->
                    if (bit) acc or (1 shl (10 - i)) else acc
                }
                if (index !in 0..<2048) throw IllegalArgumentException("Invalid index")
                wordlist[index]
            }

            // Validate the resulting mnemonic
            MnemonicCode.validate(words)
            words
        } catch (e: Exception) {
            AppLog.e("SeedQRUtils") { "Error decoding CompactSeedQR: ${e.message}" }
            null
        }
    }

    /**
     * Decodes a scanned CompactSeedQR.
     *
     * CompactSeedQR is bare entropy with no checksum: the checksum words are derived from it, so
     * *every* 16- or 32-byte payload decodes to a valid mnemonic. Two rules keep ordinary QR codes
     * from being read as seeds, which would offer the user a wallet anyone could guess:
     * - Only [byteSegment] is decoded, and only if it is the whole code. ZXing's raw bytes won't
     *   do: they are the data region with headers and padding, exactly 16 bytes for many small
     *   codes whatever those hold.
     * - Readable text is refused. About one 12-word CompactSeedQR in 262,000 is readable by chance
     *   and is refused with it (for 24 words the odds are negligible).
     *
     * @param text The QR's text as ZXing decoded it
     * @param byteSegment The code's single byte-mode segment, or null if it isn't one
     * @return List of mnemonic words, or null if this isn't a CompactSeedQR
     */
    fun decodeCompactSeedQR(text: String, byteSegment: ByteArray?): List<String>? {
        if (byteSegment == null) return null
        // More characters than bytes: the code has other segments besides this one.
        if (text.length > byteSegment.size) return null
        if (isReadableText(byteSegment)) return null
        return compactSeedQRToMnemonic(byteSegment)
    }

    /** Valid UTF-8 without control characters other than tabs and line breaks. */
    private fun isReadableText(bytes: ByteArray): Boolean = try {
        Charsets.UTF_8.newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
            .decode(ByteBuffer.wrap(bytes))
            .none { it.isISOControl() && it !in "\t\r\n" }
    } catch (_: CharacterCodingException) {
        false
    }
}
