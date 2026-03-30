package com.gorunjinian.metrovault.lib.qrtools

import com.gorunjinian.bbqr.ContinuousJoinResult
import com.gorunjinian.bbqr.ContinuousJoiner
import com.gorunjinian.bcur.CborItem
import com.gorunjinian.bcur.ResultType
import com.gorunjinian.bcur.URDecoder
import com.gorunjinian.bcur.registry.CryptoCoinInfo
import com.gorunjinian.bcur.registry.CryptoHDKey
import com.gorunjinian.bcur.registry.CryptoOutput
import com.gorunjinian.bcur.registry.ScriptExpression
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.metrovault.lib.bitcoin.DeterministicWallet
import com.gorunjinian.metrovault.lib.bitcoin.KeyPath
import com.gorunjinian.metrovault.lib.bitcoin.byteVector
import com.gorunjinian.metrovault.lib.bitcoin.byteVector32

/**
 * Helper class to track animated QR scanning for descriptors.
 * Supports: UR:CRYPTO-OUTPUT, UR:OUTPUT-DESCRIPTOR, BBQr with text content
 *
 * Similar to AnimatedQRScanner but returns raw descriptor string instead of PSBT.
 */
class DescriptorQRScanner {
    // For UR formats - bcur-kotlin library handles fountain codes
    private var urDecoder: URDecoder? = null
    private var urFrameCount: Int = 0
    private var urEstimatedTotal: Int = 0

    // For BBQr formats - bbqr-kotlin library handles streaming decoding
    private var bbqrJoiner: ContinuousJoiner? = null
    private var bbqrTotalParts: Int = 0
    private var bbqrReceivedParts: Int = 0
    private var bbqrCompleteData: ByteArray? = null

    private var detectedFormat: String? = null

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
     * Process UR format frames using bcur-kotlin URDecoder
     */
    private fun processURFrame(content: String): Int? {
        try {
            if (urDecoder == null) {
                urDecoder = URDecoder()
                android.util.Log.d("DescriptorQRScanner", "Initialized URDecoder for fountain codes")
            }

            val decoder = urDecoder!!
            decoder.receivePart(content)
            urFrameCount++

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
     * Process BBQr frame using ContinuousJoiner from bbqr-kotlin
     */
    private fun processBBQrFrame(content: String): Int? {
        try {
            if (bbqrJoiner == null) {
                bbqrJoiner = ContinuousJoiner()
            }

            return when (val result = bbqrJoiner!!.addPart(content)) {
                is ContinuousJoinResult.NotStarted -> {
                    android.util.Log.d("DescriptorQRScanner", "BBQr: not started yet")
                    0
                }
                is ContinuousJoinResult.InProgress -> {
                    bbqrReceivedParts++
                    if (bbqrTotalParts == 0) {
                        bbqrTotalParts = bbqrReceivedParts + result.partsLeft
                    }
                    val progress = ((bbqrTotalParts - result.partsLeft) * 100) / bbqrTotalParts
                    android.util.Log.d("DescriptorQRScanner", "BBQr: ${result.partsLeft} parts left, progress: $progress%")
                    progress
                }
                is ContinuousJoinResult.Complete -> {
                    bbqrCompleteData = result.joined.data
                    android.util.Log.d("DescriptorQRScanner", "BBQr: complete, ${bbqrCompleteData!!.size} bytes")
                    100
                }
            }
        } catch (e: Exception) {
            android.util.Log.e("DescriptorQRScanner", "Error processing BBQr frame: ${e.message}")
            return null
        }
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
            "bbqr" -> bbqrCompleteData != null
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
                val result = urDecoder?.result
                if (result?.type == ResultType.SUCCESS) {
                    try {
                        val ur = result.ur!!
                        val item = CborItem.decode(ur.cborData)

                        when (ur.type) {
                            "crypto-output" -> {
                                val decoded = CryptoOutput.fromCbor(item)
                                reconstructFromCryptoOutput(decoded)
                            }
                            "output-descriptor" -> {
                                val decoded = UROutputDescriptor.fromCbor(item)
                                decoded.source
                            }
                            else -> {
                                android.util.Log.w("DescriptorQRScanner", "Unknown UR type: ${ur.type}")
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
                val result = urDecoder?.result
                if (result?.type == ResultType.SUCCESS) {
                    try {
                        val ur = result.ur!!
                        val bytes = ur.toBytes()
                        val content = String(bytes, Charsets.UTF_8)
                        android.util.Log.d("DescriptorQRScanner", "UR:BYTES decoded: ${content.take(100)}...")
                        content
                    } catch (e: Exception) {
                        android.util.Log.e("DescriptorQRScanner", "Failed to decode UR:BYTES: ${e.message}")
                        null
                    }
                } else null
            }
            "bbqr" -> {
                bbqrCompleteData?.let { bytes ->
                    String(bytes, Charsets.UTF_8)
                }
            }
            "plain", "unknown" -> singleFrameResult
            else -> null
        }
    }

    /**
     * Manual reconstruction from CryptoOutput
     */
    private fun reconstructFromCryptoOutput(output: CryptoOutput): String? {
        val expressions = output.scriptExpressions
        if (expressions.isEmpty()) return null
        val multiKey = output.multiKey

        if (multiKey != null) {
            val threshold = multiKey.threshold
            val hdKeys = multiKey.hdKeys
            if (hdKeys.isEmpty()) return null

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
        val isTestnet = hdKey.useInfo?.resolvedNetwork == CryptoCoinInfo.Network.TESTNET

        val keyBytes = hdKey.key
        val chainCodeBytes = hdKey.chainCode ?: ByteArray(32)

        if (keyBytes.size == 33 && chainCodeBytes.size == 32) {
            try {
                val depth = if (originPath.isEmpty()) 0 else originPath.split("/").filter { it.isNotEmpty() }.size

                val parentFingerprint = origin?.sourceFingerprint?.let { fp ->
                    if (fp.size >= 4) {
                        ((fp[0].toLong() and 0xFF) shl 24) or
                        ((fp[1].toLong() and 0xFF) shl 16) or
                        ((fp[2].toLong() and 0xFF) shl 8) or
                        (fp[3].toLong() and 0xFF)
                    } else 0L
                } ?: 0L

                val childNumber = originPath.split("/").lastOrNull { it.isNotEmpty() }
                    ?.let { component ->
                    val cleaned = component.replace("'", "").replace("h", "")
                    val num = cleaned.toLongOrNull() ?: 0L
                    if (component.contains("'") || component.contains("h")) {
                        num + 0x80000000L
                    } else num
                } ?: 0L

                val extPubKey = DeterministicWallet.ExtendedPublicKey(
                    publickeybytes = keyBytes.byteVector(),
                    chaincode = chainCodeBytes.byteVector32(),
                    depth = depth,
                    path = KeyPath(listOf(childNumber)),
                    parent = parentFingerprint
                )

                val prefix = if (isTestnet) {
                    DeterministicWallet.tpub
                } else {
                    DeterministicWallet.xpub
                }
                sb.append(extPubKey.encode(prefix))
            } catch (e: Exception) {
                android.util.Log.e("DescriptorQRScanner", "Failed to encode xpub: ${e.message}")
                return ""
            }
        }

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
     * Reset scanner state
     */
    fun reset() {
        detectedFormat = null
        urDecoder = null
        urFrameCount = 0
        urEstimatedTotal = 0
        bbqrJoiner = null
        bbqrTotalParts = 0
        bbqrReceivedParts = 0
        bbqrCompleteData = null
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
                if (bbqrTotalParts > 0) {
                    "${bbqrTotalParts - (bbqrTotalParts - bbqrReceivedParts).coerceAtLeast(0)}/$bbqrTotalParts BBQr"
                } else {
                    "Scanning BBQr..."
                }
            }
            else -> "Scanning..."
        }
    }

    fun getDetectedFormat(): String? = detectedFormat
}
