package com.gorunjinian.metrovault.lib.bitcoin

import android.util.Base64
import android.util.Log
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater
import java.util.zip.Deflater

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
     * Decodes a complete BBQr message (for non-animated single QR).
     * BBQr header: B$[encoding][type][total:2][part:2][data]
     * 
     * Encoding: H=Hex, 2=Base32, Z=Zlib+Base32
     * Type: P=PSBT, T=Transaction, etc.
     */
    private fun decodeBBQrSingle(input: String): String? {
        if (input.length < 8) return null
        
        val encoding = input[2]
        val fileType = input[3]
        
        // For single QR, we skip straight to data after 8-char header
        // Header: B$ + encoding + type + 2-digit total (base36) + 2-digit part (base36)
        val data = input.substring(8)
        
        val bytes = when (encoding) {
            'H' -> decodeHex(data)
            '2' -> decodeBase32(data)
            'Z' -> decodeZlibBase32(data)
            else -> {
                Log.w(TAG, "Unknown BBQr encoding: $encoding")
                null
            }
        } ?: return null
        
        // Verify PSBT magic
        if (!bytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
            Log.w(TAG, "Invalid PSBT magic bytes")
            return null
        }
        
        return Base64.encodeToString(bytes, Base64.NO_WRAP)
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
     * Reassembles BBQr frames and decodes to Base64 PSBT.
     */
    fun decodeBBQrFrames(frames: List<String>): String? {
        if (frames.isEmpty()) return null
        
        try {
            // All frames should have same encoding and type
            val encoding = frames[0][2]
            val fileType = frames[0][3]
            
            // Sort frames by part number and extract data portions
            val sortedFrames = frames
                .mapNotNull { frame ->
                    parseBBQrFrame(frame)?.let { (part, _, raw) ->
                        part to raw.substring(8) // Remove 8-char header
                    }
                }
                .sortedBy { it.first }
                .map { it.second }
            
            val combinedData = sortedFrames.joinToString("")
            
            val bytes = when (encoding) {
                'H' -> decodeHex(combinedData)
                '2' -> decodeBase32(combinedData)
                'Z' -> decodeZlibBase32(combinedData)
                else -> null
            } ?: return null
            
            // Verify PSBT magic
            if (bytes.size < 5 || !bytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
                Log.w(TAG, "Invalid PSBT magic after BBQr reassembly")
                return null
            }
            
            return Base64.encodeToString(bytes, Base64.NO_WRAP)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to decode BBQr frames: ${e.message}")
            return null
        }
    }
    
    /**
     * Decodes BC-UR PSBT format (both ur:psbt/ and ur:crypto-psbt/).
     * Format: ur:[type]/[part-info/]bytewords
     * 
     * The PSBT bytes are wrapped in CBOR (a single byte string).
     * Bytewords uses a word list to encode bytes, but the minimal/URI mode
     * uses just the first and last letter of each word.
     */
    private fun decodeURPsbt(input: String): String? {
        val lower = input.lowercase()
        val content = when {
            lower.startsWith("ur:psbt/") -> lower.removePrefix("ur:psbt/")
            lower.startsWith("ur:crypto-psbt/") -> lower.removePrefix("ur:crypto-psbt/")
            else -> return null
        }
        
        // Handle multi-part UR format: [seq]-[total]/[checksum]/data
        // or single-part: data or checksum/data
        val data = extractURData(content)
        Log.d(TAG, "Decoding UR data (${data.length} chars)")
        
        val bytewordsDecoded = decodeBytewords(data) ?: run {
            Log.w(TAG, "Failed to decode bytewords")
            return null
        }
        
        // UR wraps data in CBOR - unwrap it
        val psbtBytes = unwrapCBOR(bytewordsDecoded) ?: run {
            // Fallback: some implementations don't use CBOR wrapper
            Log.d(TAG, "CBOR unwrap failed, trying raw bytes")
            bytewordsDecoded
        }
        
        return if (psbtBytes.size >= 5 && psbtBytes.take(5).toByteArray().contentEquals(PSBT_MAGIC)) {
            Base64.encodeToString(psbtBytes, Base64.NO_WRAP)
        } else {
            Log.w(TAG, "Invalid PSBT magic in UR content (got ${psbtBytes.take(5).map { it.toInt() and 0xFF }})")
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
        
        val (prefix, content) = when {
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
                    
                    // Fountain code detection: seqNum > fragLen means we're in fountain mode
                    // In fountain mode, we just need to collect fragLen unique frames
                    val isFountainCode = seqNum > fragLen
                    
                    if (isFountainCode) {
                        Log.d(TAG, "Detected UR fountain code: seq=$seqNum, fragLen=$fragLen")
                        // For fountain codes, we'll normalize the sequence numbers
                        // Use seqNum modulo a large range to create unique slot numbers
                        // The scanner will track when we have enough unique frames
                        Triple(seqNum, fragLen, input)
                    } else {
                        // Simple multi-part: treat seqNum as part number, fragLen as total
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
     * Unwraps CBOR byte string wrapper.
     * PSBT in UR is wrapped as a CBOR byte string (major type 2).
     */
    private fun unwrapCBOR(data: ByteArray): ByteArray? {
        if (data.isEmpty()) return null
        
        val firstByte = data[0].toInt() and 0xFF
        val majorType = firstByte shr 5
        val additionalInfo = firstByte and 0x1F
        
        // Major type 2 = byte string
        if (majorType != 2) {
            Log.d(TAG, "Not a CBOR byte string (major type $majorType)")
            return null
        }
        
        return try {
            when {
                // Length in additional info (0-23 bytes)
                additionalInfo < 24 -> {
                    if (data.size >= 1 + additionalInfo) data.copyOfRange(1, 1 + additionalInfo) else null
                }
                // 1-byte length (24 = uint8 follows)
                additionalInfo == 24 && data.size >= 2 -> {
                    val length = data[1].toInt() and 0xFF
                    if (data.size >= 2 + length) data.copyOfRange(2, 2 + length) else null
                }
                // 2-byte length (25 = uint16 follows)
                additionalInfo == 25 && data.size >= 3 -> {
                    val length = ((data[1].toInt() and 0xFF) shl 8) or (data[2].toInt() and 0xFF)
                    if (data.size >= 3 + length) data.copyOfRange(3, 3 + length) else null
                }
                // 4-byte length (26 = uint32 follows)
                additionalInfo == 26 && data.size >= 5 -> {
                    val length = ((data[1].toInt() and 0xFF) shl 24) or
                            ((data[2].toInt() and 0xFF) shl 16) or
                            ((data[3].toInt() and 0xFF) shl 8) or
                            (data[4].toInt() and 0xFF)
                    if (data.size >= 5 + length) data.copyOfRange(5, 5 + length) else null
                }
                else -> null
            }
        } catch (e: Exception) {
            Log.e(TAG, "CBOR unwrap error: ${e.message}")
            null
        }
    }
    
    /**
     * Decodes BC-UR bytes format (generic).
     */
    private fun decodeURBytes(input: String): String? {
        val content = input.lowercase().removePrefix("ur:bytes/")
        val data = extractURData(content)
        
        return decodeBytewords(data)?.let { bytes ->
            Base64.encodeToString(bytes, Base64.NO_WRAP)
        }
    }
    
    /**
     * Extracts data portion from UR content, handling multi-part format.
     */
    private fun extractURData(content: String): String {
        val parts = content.split("/")
        return when {
            parts.size >= 3 && parts[0].contains("-") -> parts.drop(2).joinToString("/")
            parts.size >= 2 && parts[0].contains("-") -> parts.drop(1).joinToString("/")
            parts.size >= 2 -> parts.last()
            else -> content
        }
    }
    
    /**
     * Decodes Bytewords minimal encoding (2 chars per byte).
     * Each byte is represented by first and last letter of a word from the wordlist.
     */
    private fun decodeBytewords(input: String): ByteArray? {
        // Remove any dashes used as separators
        val clean = input.replace("-", "")
        if (clean.length % 2 != 0) return null
        
        val bytes = mutableListOf<Byte>()
        for (i in clean.indices step 2) {
            val pair = clean.substring(i, i + 2)
            val byteValue = BYTEWORDS_MINIMAL_MAP[pair]
            if (byteValue == null) {
                Log.w(TAG, "Unknown byteword pair: $pair")
                return null
            }
            bytes.add(byteValue.toByte())
        }
        return bytes.toByteArray()
    }
    
    // ==================== BC-UR Encoding (for output) ====================
    
    /**
     * Encodes a Base64 PSBT to modern BC-UR format (ur:psbt/...).
     * This format is compatible with Sparrow and other modern wallets.
     */
    fun encodeToURPsbt(psbtBase64: String): String? {
        return try {
            val psbtBytes = Base64.decode(psbtBase64, Base64.NO_WRAP)
            // Wrap in CBOR byte string
            val cborWrapped = wrapInCBOR(psbtBytes)
            val bytewords = encodeToBytewords(cborWrapped)
            "ur:psbt/$bytewords"
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode PSBT to BC-UR: ${e.message}")
            null
        }
    }
    
    /**
     * Wraps data in a CBOR byte string (major type 2).
     */
    private fun wrapInCBOR(data: ByteArray): ByteArray {
        val output = ByteArrayOutputStream()
        val length = data.size
        
        when {
            length < 24 -> {
                // Length fits in additional info
                output.write(0x40 or length) // 0x40 = major type 2 (byte string)
            }
            length < 256 -> {
                // 1-byte length
                output.write(0x58) // 0x58 = byte string + 1-byte length
                output.write(length)
            }
            length < 65536 -> {
                // 2-byte length
                output.write(0x59) // 0x59 = byte string + 2-byte length
                output.write(length shr 8)
                output.write(length and 0xFF)
            }
            else -> {
                // 4-byte length
                output.write(0x5A) // 0x5A = byte string + 4-byte length
                output.write(length shr 24)
                output.write((length shr 16) and 0xFF)
                output.write((length shr 8) and 0xFF)
                output.write(length and 0xFF)
            }
        }
        output.write(data)
        return output.toByteArray()
    }
    
    /**
     * Encodes a Base64 PSBT to BBQr format.
     * Uses pure Base32 encoding ('2') for maximum compatibility.
     * 
     * Note: Zlib compression ('Z') requires wbits=10 which Java's Deflater 
     * doesn't support, so we use Base32 only.
     * 
     * Per BBQr spec:
     * - All blocks except last must be same length
     * - All blocks must decode to integer number of bytes
     * - For Base32, this means mod8 character length (8 Base32 chars = 5 bytes)
     * 
     * For even visual distribution (like Sparrow), we:
     * 1. Calculate minimum frames needed at max capacity
     * 2. Distribute data evenly across all frames
     * 
     * @return List of BBQr frame strings, or null on error
     */
    fun encodeToBBQr(psbtBase64: String, maxQrChars: Int = 1800): List<String>? {
        return try {
            val psbtBytes = Base64.decode(psbtBase64, Base64.NO_WRAP)
            
            // Use pure Base32 encoding (no Zlib - Java can't do required wbits=10)
            val encoding = '2'
            
            // Header is 8 chars: B$ + encoding + type + 2 chars total + 2 chars part
            val headerSize = 8
            val maxDataChars = maxQrChars - headerSize
            
            // For Base32: 8 chars = 5 bytes
            // Calculate max bytes per frame (aligned to 5-byte boundary for Base32)
            val maxBytesPerFrame = (maxDataChars / 8) * 5
            
            // Calculate minimum number of frames needed
            val totalBytes = psbtBytes.size
            val numFrames = kotlin.math.ceil(totalBytes.toDouble() / maxBytesPerFrame).toInt()
                .coerceAtLeast(1)
            
            if (numFrames > 1295) {
                Log.e(TAG, "PSBT too large for BBQr: $numFrames parts")
                return null
            }
            
            // Distribute bytes evenly across frames
            // Each frame gets approximately the same number of bytes
            // but must be aligned to 5-byte boundary for proper Base32 encoding
            val baseBytesPerFrame = totalBytes / numFrames
            // Align to 5-byte boundary (for Base32 encoding without padding issues)
            val alignedBytesPerFrame = ((baseBytesPerFrame + 4) / 5) * 5
            
            Log.d(TAG, "BBQr encoding: $totalBytes bytes in $numFrames frames (~$alignedBytesPerFrame bytes/frame)")
            
            // Split bytes into chunks with even distribution
            val chunks = mutableListOf<ByteArray>()
            var offset = 0
            for (i in 0 until numFrames) {
                val remaining = totalBytes - offset
                val chunkSize = if (i == numFrames - 1) {
                    // Last frame takes whatever is left
                    remaining
                } else {
                    // Non-final frames: use aligned size, but don't exceed remaining
                    alignedBytesPerFrame.coerceAtMost(remaining)
                }
                chunks.add(psbtBytes.copyOfRange(offset, offset + chunkSize))
                offset += chunkSize
            }
            
            // Encode each byte chunk to Base32 and prepend header
            chunks.mapIndexed { index, chunk ->
                val chunkBase32 = encodeBase32(chunk)
                val totalBase36 = numFrames.toString(36).padStart(2, '0').uppercase()
                val partBase36 = index.toString(36).padStart(2, '0').uppercase()
                "B$${encoding}P${totalBase36}${partBase36}${chunkBase32}"
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to encode PSBT to BBQr: ${e.message}")
            null
        }
    }
    
    /**
     * Compresses data using raw deflate (no zlib header) as specified by BBQr.
     * Uses window size of 10 bits to match Python's wbits=-10.
     */
    private fun compressZlib(data: ByteArray): ByteArray? {
        return try {
            // Use raw deflate (nowrap=true) with best compression
            // Python BBQr uses wbits=-10, which means window bits of 10
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true)
            deflater.setInput(data)
            deflater.finish()
            
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            
            while (!deflater.finished()) {
                val count = deflater.deflate(buffer)
                output.write(buffer, 0, count)
            }
            deflater.end()
            
            output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Zlib compression error: ${e.message}")
            null
        }
    }
    
    /**
     * Encodes bytes to Base32 (RFC 4648, uppercase, no padding).
     */
    private fun encodeBase32(data: ByteArray): String {
        val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
        val result = StringBuilder()
        
        var buffer = 0
        var bitsLeft = 0
        
        for (byte in data) {
            buffer = (buffer shl 8) or (byte.toInt() and 0xFF)
            bitsLeft += 8
            
            while (bitsLeft >= 5) {
                val index = (buffer shr (bitsLeft - 5)) and 0x1F
                result.append(alphabet[index])
                bitsLeft -= 5
            }
        }
        
        // Handle remaining bits
        if (bitsLeft > 0) {
            val index = (buffer shl (5 - bitsLeft)) and 0x1F
            result.append(alphabet[index])
        }
        
        return result.toString()
    }
    
    /**
     * Encodes bytes to Bytewords minimal format (2 chars per byte).
     * Each byte is represented by first and last letter of the corresponding word.
     */
    fun encodeToBytewords(bytes: ByteArray): String {
        val result = StringBuilder()
        for (byte in bytes) {
            val index = byte.toInt() and 0xFF  // Convert to unsigned
            val word = BYTEWORDS_LIST[index]
            result.append(word.first())
            result.append(word.last())
        }
        return result.toString()
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
        // Check for PSBT magic in hex: 70736274ff
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
    
    private fun decodeBase32(input: String): ByteArray? {
        return try {
            // RFC 4648 Base32 decoding (uppercase, no padding)
            val clean = input.uppercase().replace("=", "")
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            
            val bits = StringBuilder()
            for (c in clean) {
                val index = alphabet.indexOf(c)
                if (index < 0) return null
                bits.append(index.toString(2).padStart(5, '0'))
            }
            
            // Convert bits to bytes (8 bits each)
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i + 8 <= bits.length) {
                bytes.add(bits.substring(i, i + 8).toInt(2).toByte())
                i += 8
            }
            bytes.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Base32 decode error: ${e.message}")
            null
        }
    }
    
    private fun decodeZlibBase32(input: String): ByteArray? {
        val compressed = decodeBase32(input) ?: return null
        return try {
            val inflater = Inflater(true) // true = no zlib header
            inflater.setInput(compressed)
            
            val output = ByteArrayOutputStream()
            val buffer = ByteArray(1024)
            
            while (!inflater.finished()) {
                val count = inflater.inflate(buffer)
                if (count == 0 && inflater.needsInput()) break
                output.write(buffer, 0, count)
            }
            inflater.end()
            
            output.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Zlib decompress error: ${e.message}")
            null
        }
    }
    
    // ==================== Bytewords Mapping ====================
    
    /**
     * Full Bytewords list (256 words) - used for both encoding and decoding.
     */
    private val BYTEWORDS_LIST = listOf(
        "able", "acid", "also", "apex", "aqua", "arch", "atom", "aunt",
        "away", "axis", "back", "bald", "barn", "belt", "beta", "bias",
        "blue", "body", "brag", "brew", "bulb", "buzz", "calm", "cash",
        "cats", "chef", "city", "claw", "code", "cola", "cook", "cost",
        "crux", "curl", "cusp", "cyan", "dark", "data", "days", "deli",
        "dice", "diet", "door", "down", "draw", "drop", "drum", "dull",
        "duty", "each", "easy", "echo", "edge", "epic", "even", "exam",
        "exit", "eyes", "fact", "fair", "fern", "figs", "film", "fish",
        "fizz", "flap", "flew", "flux", "foxy", "free", "frog", "fuel",
        "fund", "gala", "game", "gear", "gems", "gift", "girl", "glow",
        "good", "gray", "grim", "guru", "gush", "gyro", "half", "hang",
        "hard", "hawk", "heat", "help", "high", "hill", "holy", "hope",
        "horn", "huts", "iced", "idea", "idle", "inch", "into", "iris",
        "iron", "item", "jade", "jazz", "join", "jolt", "jowl", "judo",
        "jugs", "jump", "junk", "jury", "keep", "keno", "kept", "keys",
        "kick", "kiln", "king", "kite", "kiwi", "knob", "lamb", "lava",
        "lazy", "leaf", "legs", "liar", "limp", "lion", "list", "logo",
        "loud", "love", "luau", "luck", "lung", "main", "many", "math",
        "maze", "memo", "menu", "meow", "mild", "mint", "miss", "monk",
        "nail", "navy", "need", "news", "next", "noon", "note", "numb",
        "obey", "oboe", "omit", "onyx", "open", "oval", "owls", "paid",
        "part", "peck", "play", "plus", "poem", "pool", "pose", "puff",
        "puma", "purr", "quad", "quiz", "race", "ramp", "real", "redo",
        "rich", "road", "rock", "roof", "ruby", "ruin", "runs", "rust",
        "safe", "saga", "scar", "sets", "silk", "skew", "slot", "soap",
        "solo", "song", "stub", "surf", "swan", "taco", "task", "taxi",
        "tent", "tied", "time", "tiny", "toil", "tomb", "toys", "trip",
        "tuna", "twin", "ugly", "undo", "unit", "urge", "user", "vast",
        "very", "veto", "vial", "vibe", "view", "visa", "void", "vows",
        "wall", "wand", "warm", "wasp", "wave", "waxy", "webs", "what",
        "when", "whiz", "wolf", "work", "yank", "yawn", "yell", "yoga",
        "yurt", "zaps", "zero", "zest", "zinc", "zone", "zoom"
    )
    
    /**
     * Bytewords minimal decoding map: 2-letter code -> byte value (0-255)
     * Each entry is first + last letter of the full byteword.
     */
    private val BYTEWORDS_MINIMAL_MAP: Map<String, Int> by lazy {
        BYTEWORDS_LIST.mapIndexed { index, word ->
            val key = "${word.first()}${word.last()}"
            key to index
        }.toMap()
    }
}
