package com.gorunjinian.metrovault.lib.qrtools

import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.domain.service.psbt.PSBTDecoder
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.byteVector32
import com.gorunjinian.metrovault.lib.qrtools.registry.CryptoCoinInfo
import com.gorunjinian.metrovault.lib.qrtools.registry.CryptoHDKey
import com.gorunjinian.metrovault.lib.qrtools.registry.CryptoOutput
import com.gorunjinian.metrovault.lib.qrtools.registry.ScriptExpression
import com.gorunjinian.metrovault.lib.qrtools.registry.UROutputDescriptor
import com.gorunjinian.metrovault.lib.qrtools.thirdparty.URDecoder
import java.io.ByteArrayOutputStream
import java.util.zip.Inflater

/**
 * Helper class to track animated QR scanning for descriptors.
 * Supports: UR:CRYPTO-OUTPUT, UR:OUTPUT-DESCRIPTOR, BBQr with text content
 * 
 * Similar to AnimatedQRScanner but returns raw descriptor string instead of PSBT.
 */
class DescriptorQRScanner {
    // For non-UR formats (BBQr)
    private val receivedFrames = mutableMapOf<Int, String>()
    private var expectedTotal: Int? = null
    private var detectedFormat: String? = null
    
    // For UR formats - use URDecoder which handles fountain codes
    private var urDecoder: URDecoder? = null
    private var urFrameCount: Int = 0
    private var urEstimatedTotal: Int = 0
    
    // Single-frame result
    private var singleFrameResult: String? = null
    
    /**
     * Process a scanned frame.
     * @return Progress percentage (0-100), or null if frame is invalid
     */
    fun processFrame(content: String): Int? {
        val lower = content.lowercase()
        
        // Detect format on first frame
        if (detectedFormat == null) {
            detectedFormat = when {
                content.startsWith("B$") -> "bbqr"
                lower.startsWith("ur:crypto-output/") -> "ur-crypto-output"
                lower.startsWith("ur:output-descriptor/") -> "ur-output-descriptor"
                lower.startsWith("ur:bytes/") -> "ur-bytes"  // BSMS exported as raw bytes
                lower.startsWith("wsh(") || lower.startsWith("sh(") ||
                lower.startsWith("wpkh(") || lower.startsWith("pkh(") ||
                lower.startsWith("tr(") -> "plain"
                else -> "unknown"
            }
            android.util.Log.d("DescriptorQRScanner", "Detected format: $detectedFormat")
        }

        return when (detectedFormat) {
            "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> processURFrame(content)
            "bbqr" -> processBBQrFrame(content)
            "plain" -> {
                singleFrameResult = content
                100 // Complete immediately
            }
            else -> {
                // Try to handle as single plain text
                singleFrameResult = content
                100
            }
        }
    }
    
    /**
     * Process UR format frames using URDecoder
     */
    private fun processURFrame(content: String): Int? {
        try {
            // Initialize URDecoder if needed
            if (urDecoder == null) {
                urDecoder = URDecoder()
                android.util.Log.d("DescriptorQRScanner", "Initialized URDecoder for fountain codes")
            }
            
            val decoder = urDecoder!!
            decoder.receivePart(content)
            urFrameCount++
            
            // Get progress info
            val progress = decoder.estimatedPercentComplete
            if (urEstimatedTotal == 0 && decoder.expectedPartCount > 0) {
                urEstimatedTotal = decoder.expectedPartCount
            }
            
            val percentComplete = (progress * 100).toInt().coerceIn(0, 100)
            android.util.Log.d("DescriptorQRScanner", "UR frame $urFrameCount received, progress: $percentComplete%")
            
            return percentComplete
        } catch (e: Exception) {
            android.util.Log.e("DescriptorQRScanner", "Error processing UR frame: ${e.message}")
            return null
        }
    }
    
    /**
     * Process BBQr format frames
     */
    private fun processBBQrFrame(content: String): Int? {
        val parsed = PSBTDecoder.parseBBQrFrame(content) ?: return null
        val (partNum, totalParts, _) = parsed
        
        if (expectedTotal == null) {
            expectedTotal = totalParts
        } else if (expectedTotal != totalParts) {
            android.util.Log.w("DescriptorQRScanner", "Total parts changed, resetting")
            reset()
            expectedTotal = totalParts
        }
        
        // Store the full frame (including header) for later decoding
        receivedFrames[partNum] = content
        android.util.Log.d("DescriptorQRScanner", "BBQr frame $partNum/$totalParts, have ${receivedFrames.size} frames")
        
        return ((receivedFrames.size * 100) / totalParts)
    }
    
    /**
     * Check if all frames have been received
     */
    fun isComplete(): Boolean {
        return when (detectedFormat) {
            "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> {
                val result = urDecoder?.result
                result?.type == ResultType.SUCCESS
            }
            "bbqr" -> {
                val total = expectedTotal ?: return false
                receivedFrames.size >= total
            }
            "plain", "unknown" -> singleFrameResult != null
            else -> false
        }
    }
    
    /**
     * Get the assembled descriptor string.
     * Returns null if not complete.
     */
    fun getResult(): String? {
        if (!isComplete()) return null

        return when (detectedFormat) {
            "ur-crypto-output", "ur-output-descriptor" -> {
                // Get UR from decoder and use DescriptorDecoder to convert to string
                val result = urDecoder?.result
                if (result?.type == ResultType.SUCCESS) {
                    try {
                        val ur = result.ur
                        // Use DescriptorDecoder's reconstruction logic
                        when (val decoded = ur.decodeFromRegistry()) {
                            is CryptoOutput -> {
                                reconstructDescriptorFromCryptoOutput(decoded)
                            }

                            is UROutputDescriptor -> {
                                decoded.source
                            }

                            else -> {
                                android.util.Log.w("DescriptorQRScanner", "Unknown UR decoded type: ${decoded?.javaClass}")
                                null
                            }
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DescriptorQRScanner", "Failed to decode UR result: ${e.message}")
                        null
                    }
                } else null
            }
            "ur-bytes" -> {
                // UR:BYTES contains raw bytes - decode to string (BSMS format)
                val result = urDecoder?.result
                if (result?.type == ResultType.SUCCESS) {
                    try {
                        val ur = result.ur
                        val bytes = ur.toBytes()
                        if (bytes != null) {
                            val content = String(bytes, Charsets.UTF_8)
                            android.util.Log.d("DescriptorQRScanner", "UR:BYTES decoded: ${content.take(100)}...")
                            content
                        } else {
                            android.util.Log.e("DescriptorQRScanner", "UR:BYTES toBytes() returned null")
                            null
                        }
                    } catch (e: Exception) {
                        android.util.Log.e("DescriptorQRScanner", "Failed to decode UR:BYTES: ${e.message}")
                        null
                    }
                } else null
            }
            "bbqr" -> {
                // Reassemble BBQr frames and decode
                val frames = receivedFrames.entries.sortedBy { it.key }.map { it.value }
                decodeBBQrToDescriptor(frames)
            }
            "plain", "unknown" -> singleFrameResult
            else -> null
        }
    }
    
    /**
     * Reconstructs descriptor string from CryptoOutput
     */
    private fun reconstructDescriptorFromCryptoOutput(output: CryptoOutput): String? {
        // Directly reconstruct from the parsed CryptoOutput object
        return reconstructFromCryptoOutput(output)
    }
    
    /**
     * Manual reconstruction from CryptoOutput
     */
    private fun reconstructFromCryptoOutput(output: CryptoOutput): String? {
        val expressions = output.scriptExpressions ?: return null
        val multiKey = output.multiKey
        
        if (multiKey != null) {
            val threshold = multiKey.threshold
            val hdKeys = multiKey.hdKeys ?: return null
            
            val isSorted = expressions.any { 
                it == ScriptExpression.SORTED_MULTISIG 
            }
            val multiFunc = if (isSorted) "sortedmulti" else "multi"
            
            val keyStrings = hdKeys.map { key -> formatHDKeyForDescriptor(key) }
            var result = "$multiFunc($threshold,${keyStrings.joinToString(",")})"
            
            // Wrap in script expressions
            for (expr in expressions.reversed()) {
                result = when (expr) {
                    ScriptExpression.WITNESS_SCRIPT_HASH -> "wsh($result)"
                    ScriptExpression.SCRIPT_HASH -> "sh($result)"
                    ScriptExpression.PUBLIC_KEY_HASH -> "pkh($result)"
                    ScriptExpression.WITNESS_PUBLIC_KEY_HASH -> "wpkh($result)"
                    ScriptExpression.TAPROOT -> "tr($result)"
                    else -> result
                }
            }
            return result
        }
        return null
    }
    
    private fun formatHDKeyForDescriptor(hdKey: CryptoHDKey): String {
        val sb = StringBuilder()

        val origin = hdKey.origin
        var originPath = ""
        if (origin != null) {
            val fingerprintBytes = origin.sourceFingerprint
            val fingerprint = if (fingerprintBytes != null && fingerprintBytes.size >= 4) {
                fingerprintBytes.joinToString("") { String.format("%02x", it) }
            } else ""
            originPath = origin.path ?: ""
            if (fingerprint.isNotEmpty() || originPath.isNotEmpty()) {
                sb.append("[$fingerprint/$originPath]")
            }
        }

        // Determine if testnet
        val isTestnet = hdKey.useInfo?.network == CryptoCoinInfo.Network.TESTNET

        val keyBytes = hdKey.key
        val chainCodeBytes = hdKey.chainCode ?: ByteArray(32)

        if (keyBytes != null && keyBytes.size == 33 && chainCodeBytes.size == 32) {
            try {
                // Determine depth from origin path (count path components)
                val depth = if (originPath.isEmpty()) 0 else originPath.split("/").filter { it.isNotEmpty() }.size

                // Get parent fingerprint (use origin's source fingerprint or 0)
                val parentFingerprint = origin?.sourceFingerprint?.let { fp ->
                    if (fp.size >= 4) {
                        ((fp[0].toLong() and 0xFF) shl 24) or
                        ((fp[1].toLong() and 0xFF) shl 16) or
                        ((fp[2].toLong() and 0xFF) shl 8) or
                        (fp[3].toLong() and 0xFF)
                    } else 0L
                } ?: 0L

                // Get child number from last path component
                val childNumber = originPath.split("/").lastOrNull { it.isNotEmpty() }
                    ?.let { component ->
                    val cleaned = component.replace("'", "").replace("h", "")
                    val num = cleaned.toLongOrNull() ?: 0L
                    if (component.contains("'") || component.contains("h")) {
                        num + 0x80000000L
                    } else num
                } ?: 0L

                // Create ExtendedPublicKey and encode properly
                val extPubKey = DeterministicWallet.ExtendedPublicKey(
                    publickeybytes = keyBytes.byteVector(),
                    chaincode = chainCodeBytes.byteVector32(),
                    depth = depth,
                    path = KeyPath(listOf(childNumber)),
                    parent = parentFingerprint
                )

                // Encode with appropriate prefix (xpub/tpub for now, could extend to zpub/vpub)
                val prefix = if (isTestnet) {
                    DeterministicWallet.tpub
                } else {
                    DeterministicWallet.xpub
                }
                sb.append(extPubKey.encode(prefix))
            } catch (e: Exception) {
                android.util.Log.e("DescriptorQRScanner", "Failed to encode xpub: ${e.message}")
                // Fallback - shouldn't happen with valid keys
                return ""
            }
        }

        // Children path (wildcard derivation suffix)
        val children = hdKey.children
        if (children != null) {
            val childPath = children.path
            if (!childPath.isNullOrEmpty()) {
                if (!childPath.startsWith("/")) sb.append("/")
                sb.append(childPath)
            }
        }

        return sb.toString()
    }
    
    /**
     * Decode BBQr frames to descriptor string
     */
    private fun decodeBBQrToDescriptor(frames: List<String>): String? {
        if (frames.isEmpty()) return null
        
        try {
            val encoding = frames[0][2]
            
            // Sort and extract data
            val sortedFrames = frames
                .mapNotNull { frame ->
                    PSBTDecoder.parseBBQrFrame(frame)?.let { (part, _, raw) ->
                        part to raw.substring(8)
                    }
                }
                .sortedBy { it.first }
                .map { it.second }
            
            val combinedData = sortedFrames.joinToString("")
            
            // Handle different BBQr encodings
            // U = Uncompressed UTF-8 (raw text, no encoding)
            // H = Hex encoded
            // 2 = Base32 encoded
            // Z = Zlib compressed + Base32 encoded
            return when (encoding) {
                'U' -> combinedData  // Uncompressed UTF-8 - data is already the string
                'H' -> decodeHex(combinedData)?.let { String(it, Charsets.UTF_8) }
                '2' -> decodeBase32(combinedData)?.let { String(it, Charsets.UTF_8) }
                'Z' -> decodeZlibBase32(combinedData)?.let { String(it, Charsets.UTF_8) }
                else -> {
                    android.util.Log.w("DescriptorQRScanner", "Unknown BBQr encoding: $encoding")
                    null
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DescriptorQRScanner", "BBQr decode error: ${e.message}")
            return null
        }
    }
    
    // Helper decode functions
    private fun decodeHex(hex: String): ByteArray? {
        return try {
            val clean = hex.replace(" ", "").replace("\n", "")
            if (clean.length % 2 != 0) return null
            ByteArray(clean.length / 2) { i ->
                clean.substring(i * 2, i * 2 + 2).toInt(16).toByte()
            }
        } catch (_: Exception) { null }
    }
    
    private fun decodeBase32(input: String): ByteArray? {
        return try {
            val clean = input.uppercase().replace("=", "")
            val alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567"
            val bits = StringBuilder()
            for (c in clean) {
                val index = alphabet.indexOf(c)
                if (index < 0) return null
                bits.append(index.toString(2).padStart(5, '0'))
            }
            val bytes = mutableListOf<Byte>()
            var i = 0
            while (i + 8 <= bits.length) {
                bytes.add(bits.substring(i, i + 8).toInt(2).toByte())
                i += 8
            }
            bytes.toByteArray()
        } catch (_: Exception) { null }
    }
    
    private fun decodeZlibBase32(input: String): ByteArray? {
        val compressed = decodeBase32(input) ?: return null
        return try {
            val inflater = Inflater(true)
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
        } catch (_: Exception) { null }
    }
    
    /**
     * Reset scanner state
     */
    fun reset() {
        receivedFrames.clear()
        expectedTotal = null
        detectedFormat = null
        urDecoder = null
        urFrameCount = 0
        urEstimatedTotal = 0
        singleFrameResult = null
    }
    
    /**
     * Get progress string
     */
    fun getProgressString(): String {
        return when (detectedFormat) {
            "ur-crypto-output", "ur-output-descriptor", "ur-bytes" -> {
                val progress = (urDecoder?.estimatedPercentComplete ?: 0.0) * 100
                "${progress.toInt()}% BC-UR"
            }
            "bbqr" -> {
                val total = expectedTotal ?: return "Waiting..."
                "${receivedFrames.size}/$total BBQr"
            }
            else -> "Scanning..."
        }
    }
    
    fun getDetectedFormat(): String? = detectedFormat
}
