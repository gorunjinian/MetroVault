package com.gorunjinian.metrovault.core.qr

import com.gorunjinian.bcur.registry.CryptoCoinInfo
import com.gorunjinian.bcur.registry.CryptoECKey
import com.gorunjinian.bcur.registry.CryptoHDKey
import com.gorunjinian.bcur.registry.CryptoOutput
import com.gorunjinian.bcur.registry.ScriptExpression
import com.gorunjinian.bcur.registry.UROutputDescriptor
import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.vaultovich.DeterministicWallet
import com.gorunjinian.vaultovich.KeyPath
import com.gorunjinian.vaultovich.byteVector
import com.gorunjinian.vaultovich.byteVector32

/**
 * Rebuilds descriptor text from the BC-UR registry types that carry one: `crypto-output`,
 * `output-descriptor` and the HD keys inside them. Shared by the multisig import scanner and the
 * scan-anything classifier so the same QR always yields the same descriptor (its xpubs feed the
 * stored descriptor and checksum).
 */
object URDescriptors {

    private const val TAG = "URDescriptors"

    /**
     * Descriptor for a `crypto-output`: a multisig (`multi` / `sortedmulti`) or a single HD key,
     * wrapped in its script expressions. Null for an EC-key output or one with no expressions.
     */
    fun fromCryptoOutput(output: CryptoOutput): String? {
        val expressions = output.scriptExpressions
        if (expressions.isEmpty()) return null

        val multiKey = output.multiKey
        val hdKey = output.hdKey
        val inner = when {
            multiKey != null -> {
                val hdKeys = multiKey.hdKeys
                if (hdKeys.isEmpty()) return null
                val isSorted = expressions.any { it == ScriptExpression.SORTED_MULTISIG }
                val multiFunc = if (isSorted) "sortedmulti" else "multi"
                "$multiFunc(${multiKey.threshold},${hdKeys.joinToString(",") { formatHDKey(it) }})"
            }
            hdKey != null -> formatHDKey(hdKey)
            else -> return null
        }
        return wrapInScriptExpressions(inner, expressions)
    }

    /**
     * Descriptor text of an `output-descriptor` (BCR-2023-010). Its source may name keys by
     * position (`wpkh(@0)`); each `@n` is replaced with key n from the record's key list.
     * Placeholders without a matching key are left as they are.
     */
    fun fromOutputDescriptor(descriptor: UROutputDescriptor): String {
        val keys = descriptor.keys.orEmpty()
        if (keys.isEmpty()) return descriptor.source
        return KEY_PLACEHOLDER.replace(descriptor.source) { match ->
            when (val key = keys.getOrNull(match.groupValues[1].toInt())) {
                is CryptoHDKey -> formatHDKey(key)
                is CryptoECKey -> key.data.toHex()
                else -> match.value
            }
        }
    }

    /**
     * A descriptor key expression for an HD key: `[fingerprint/origin]xpub/children`. The xpub's
     * depth, child number and parent field are rebuilt from the origin path and source
     * fingerprint, as the multisig import has always done; descriptor consumers read only the
     * key and chain code.
     */
    fun formatHDKey(hdKey: CryptoHDKey): String {
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

                val prefix = if (isTestnet) DeterministicWallet.tpub else DeterministicWallet.xpub
                sb.append(extPubKey.encode(prefix))
            } catch (e: Exception) {
                AppLog.e(TAG) { "Failed to encode xpub: ${e.message}" }
                return ""
            }
        }

        val childPath = hdKey.children?.path
        if (!childPath.isNullOrEmpty()) {
            if (!childPath.startsWith("/")) sb.append("/")
            sb.append(childPath)
        }

        return sb.toString()
    }

    private fun wrapInScriptExpressions(inner: String, expressions: List<ScriptExpression>): String {
        var result = inner
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

    private fun ByteArray.toHex(): String = joinToString("") { String.format("%02x", it) }

    private val KEY_PLACEHOLDER = Regex("@(\\d+)")
}
