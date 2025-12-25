package com.gorunjinian.metrovault.lib.qrtools

import android.content.Context
import android.util.Log
import com.gorunjinian.metrovault.lib.bitcoin.BIP39Wordlist
import com.gorunjinian.metrovault.lib.bitcoin.MnemonicCode
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
    
    // ============== Standard SeedQR ==============
    
    /**
     * Converts a mnemonic word list to a Standard SeedQR digit string.
     * 
     * Each word is converted to its 4-digit zero-padded index in the BIP39 wordlist.
     * Example: "vacuum" (index 1924) → "1924", "bridge" (index 222) → "0222"
     * 
     * @param words List of mnemonic words (12 or 24 words)
     * @param context Android context for loading wordlist
     * @return Digit string (48 or 96 characters) or null on error
     */
    fun mnemonicToStandardSeedQR(words: List<String>, context: Context): String? {
        if (words.size != 12 && words.size != 24) return null
        
        val wordlist = BIP39Wordlist.getEnglishWordlist(context)
        if (wordlist.isEmpty()) return null
        
        return try {
            words.joinToString("") { word ->
                val index = wordlist.indexOf(word.lowercase())
                if (index < 0) throw IllegalArgumentException("Invalid word: $word")
                index.toString().padStart(4, '0')
            }
        } catch (e: Exception) {
            Log.e("SeedQRUtils", "Error encoding to Standard SeedQR: ${e.message}")
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
     * @param context Android context for loading wordlist
     * @return List of mnemonic words or null on error/invalid checksum
     */
    fun standardSeedQRToMnemonic(digitString: String, context: Context): List<String>? {
        // Validate length: 48 digits = 12 words, 96 digits = 24 words
        if (digitString.length != 48 && digitString.length != 96) return null
        if (!digitString.all { it.isDigit() }) return null
        
        val wordlist = BIP39Wordlist.getEnglishWordlist(context)
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
            Log.e("SeedQRUtils", "Error decoding Standard SeedQR: ${e.message}")
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
     * @param context Android context for loading wordlist
     * @return Byte array (16 or 32 bytes) or null on error
     */
    fun mnemonicToCompactSeedQR(words: List<String>, context: Context): ByteArray? {
        if (words.size != 12 && words.size != 24) return null
        
        val wordlist = BIP39Wordlist.getEnglishWordlist(context)
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
            Log.e("SeedQRUtils", "Error encoding to CompactSeedQR: ${e.message}")
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
     * @param context Android context for loading wordlist
     * @return List of mnemonic words or null on error
     */
    fun compactSeedQRToMnemonic(bytes: ByteArray, context: Context): List<String>? {
        // Validate length: 16 bytes = 12 words, 32 bytes = 24 words
        if (bytes.size != 16 && bytes.size != 32) return null
        
        val wordlist = BIP39Wordlist.getEnglishWordlist(context)
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
            Log.e("SeedQRUtils", "Error decoding CompactSeedQR: ${e.message}")
            null
        }
    }
    
    // ============== Detection and Auto-Decode ==============
    
    /**
     * Attempts to detect the SeedQR format and decode accordingly.
     * 
     * Detection logic:
     * - If content is all digits and 48 or 96 chars → Standard SeedQR
     * - Otherwise, try to interpret as binary/CompactSeedQR
     * 
     * @param content The raw content from QR scan
     * @param context Android context for loading wordlist
     * @return List of mnemonic words or null if not a valid SeedQR
     */
    fun decodeSeedQR(content: String, context: Context): List<String>? {
        return decodeSeedQR(content, null, context)
    }
    
    /**
     * Attempts to detect the SeedQR format and decode accordingly.
     * 
     * Detection logic:
     * - If content is all digits and 48 or 96 chars → Standard SeedQR
     * - If rawBytes is provided with size 16 or 32 → CompactSeedQR
     * - Otherwise, try to interpret content as binary/CompactSeedQR
     * 
     * @param content The text content from QR scan
     * @param rawBytes Optional raw bytes from QR scan (for binary CompactSeedQR)
     * @param context Android context for loading wordlist
     * @return List of mnemonic words or null if not a valid SeedQR
     */
    fun decodeSeedQR(content: String, rawBytes: ByteArray?, context: Context): List<String>? {
        // Try Standard SeedQR first (digit string)
        if (content.all { it.isDigit() } && (content.length == 48 || content.length == 96)) {
            val result = standardSeedQRToMnemonic(content, context)
            if (result != null) return result
        }
        
        // Try CompactSeedQR with raw bytes (preferred for binary data)
        if (rawBytes != null && rawBytes.isNotEmpty()) {
            // First try direct extraction if size is exactly right
            if (rawBytes.size == 16 || rawBytes.size == 32) {
                val result = compactSeedQRToMnemonic(rawBytes, context)
                if (result != null) return result
            }
            
            // Try parsing ZXing raw bytes format which includes QR metadata header
            val extractedBytes = extractBinaryDataFromZXingRawBytes(rawBytes)
            if (extractedBytes != null && (extractedBytes.size == 16 || extractedBytes.size == 32)) {
                val result = compactSeedQRToMnemonic(extractedBytes, context)
                if (result != null) return result
            }
        }
        
        // Fallback: Try CompactSeedQR from text (may lose bytes due to encoding)
        val bytes = try {
            content.toByteArray(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
        
        if (bytes != null && (bytes.size == 16 || bytes.size == 32)) {
            val result = compactSeedQRToMnemonic(bytes, context)
            if (result != null) return result
        }
        
        return null
    }
    
    /**
     * Extracts binary data from ZXing raw bytes format.
     * 
     * ZXing raw bytes for QR codes contain the raw QR data segments including:
     * - Mode indicator (4 bits): 0100 (binary mode) = 0x4
     * - Character count (8 bits for QR version 1-9)
     * - Data bytes
     * - Padding
     * 
     * For CompactSeedQR (binary mode):
     * - 16 bytes (12 words): starts with 0x41, 0x0X where X is high nibble of first data byte
     * - 32 bytes (24 words): starts with 0x42, 0x0X where X is high nibble of first data byte
     */
    private fun extractBinaryDataFromZXingRawBytes(rawBytes: ByteArray): ByteArray? {
        if (rawBytes.size < 3) return null
        
        val firstByte = rawBytes[0].toInt() and 0xFF
        
        // Check for binary mode indicator (0x4X where X is high nibble of length)
        if ((firstByte and 0xF0) != 0x40) return null
        
        // Length is encoded in: low nibble of byte 0 + high nibble of byte 1
        val lengthHigh = firstByte and 0x0F
        val secondByte = rawBytes[1].toInt() and 0xFF
        val lengthLow = (secondByte and 0xF0) shr 4
        val dataLength = (lengthHigh shl 4) or lengthLow
        
        if (dataLength != 16 && dataLength != 32) return null
        
        // Data starts at nibble offset 12 bits from the start (1.5 bytes)
        // Each result byte spans two raw bytes (shifted by 4 bits)
        val result = ByteArray(dataLength)
        for (i in 0 until dataLength) {
            val rawIndex = 1 + i
            if (rawIndex + 1 >= rawBytes.size) return null
            val highNibble = (rawBytes[rawIndex].toInt() and 0x0F) shl 4
            val lowNibble = (rawBytes[rawIndex + 1].toInt() and 0xFF) shr 4
            result[i] = (highNibble or lowNibble).toByte()
        }
        
        return result
    }
    
    /**
     * Gets the expected word count from a SeedQR content string.
     * 
     * @param content The raw content from QR scan
     * @return Expected word count (12 or 24) or null if not a valid SeedQR format
     */
    fun getExpectedWordCount(content: String): Int? {
        // Standard SeedQR
        if (content.all { it.isDigit() }) {
            return when (content.length) {
                48 -> 12
                96 -> 24
                else -> null
            }
        }
        
        // CompactSeedQR (binary)
        val bytes = try {
            content.toByteArray(Charsets.ISO_8859_1)
        } catch (_: Exception) {
            null
        }
        
        return when (bytes?.size) {
            16 -> 12
            32 -> 24
            else -> null
        }
    }
}
