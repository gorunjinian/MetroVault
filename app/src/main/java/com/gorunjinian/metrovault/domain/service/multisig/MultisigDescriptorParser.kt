package com.gorunjinian.metrovault.domain.service.multisig

import com.gorunjinian.metrovault.core.logging.AppLog
import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType
import com.gorunjinian.metrovault.data.model.Result

/**
 * Parser for multisig wallet configurations.
 *
 * Single content-format router for everything the import screen can receive
 * (after the QR transport layer has already unwrapped UR/BBQr to text):
 * - Plain output descriptors: `wsh(sortedmulti(...))`, with or without checksum/comments
 * - BSMS (BIP-0129) descriptor records, delegated to [BSMS]
 * - ColdCard multisig setup files (`Name:`/`Policy:`/`Derivation:` text), delegated to
 *   [ColdCardSetupFile]
 *
 * @see BSMS
 * @see ColdCardSetupFile
 */
class MultisigDescriptorParser {

    companion object {
        private const val TAG = "MultisigDescriptorParser"

        // Regex to match key with origin: [fingerprint/path]xpub/<0;1>/* or [fingerprint/path]xpub/*
        // Example: [b68bb824/48'/1'/0'/2']tpubDFBh.../<0;1>/*
        // Group 1: fingerprint (8 hex chars)
        // Group 2: derivation path (optional, starts with /)
        // Group 3: xpub string
        //
        // Supported xpub prefixes:
        //   x/X = BIP32 legacy (xpub/xprv)
        //   t/T = BIP32 testnet (tpub/tprv)
        //   y/Y = BIP49 P2SH-P2WPKH (ypub) or BIP48 P2SH-P2WSH multisig (Ypub)
        //   u/U = BIP49 testnet (upub) or BIP48 testnet P2SH-P2WSH multisig (Upub)
        //   z/Z = BIP84 P2WPKH (zpub) or BIP48 P2WSH multisig (Zpub)
        //   v/V = BIP84 testnet (vpub) or BIP48 testnet P2WSH multisig (Vpub)
        private val KEY_PATTERN = Regex(
            """\[([a-fA-F0-9]{8})(/[^]]+)?]([xXtTyYuUzZvV]pub[a-zA-Z0-9]+)(?:/<[^>]+>/\*|/\*|\*)?"""
        )

        // Regex to extract threshold from sortedmulti(m, ...) or multi(m, ...)
        private val THRESHOLD_PATTERN = Regex("""(?:sorted)?multi\((\d+),""")
    }

    /** The content format the input was recognized as. */
    enum class SourceFormat {
        DESCRIPTOR,
        BSMS,
        SETUP_FILE
    }

    /**
     * Result of a successful parse: the config plus format-specific metadata.
     *
     * @property config The parsed multisig configuration
     * @property sourceFormat Which content format the input was recognized as
     * @property suggestedName Wallet name carried by the input (setup files only)
     * @property verificationAddress First-address verification string (BSMS only)
     */
    data class ParsedMultisig(
        val config: MultisigConfig,
        val sourceFormat: SourceFormat,
        val suggestedName: String? = null,
        val verificationAddress: String? = null
    )

    /**
     * Parse a multisig configuration from any supported text format.
     *
     * @param input The scanned/decoded text (descriptor, BSMS record, or setup file)
     * @param localFingerprints Fingerprints of local wallets, to identify which keys we own
     */
    fun parse(input: String, localFingerprints: List<String>): Result<ParsedMultisig, String> {
        return try {
            AppLog.d(TAG) { "Parsing multisig input against ${localFingerprints.size} local fingerprint(s)" }

            if (ColdCardSetupFile.isSetupFile(input)) {
                AppLog.d(TAG) { "Detected ColdCard setup file format" }
                parseSetupFile(input, localFingerprints)
            } else {
                parseDescriptorOrBsms(input, localFingerprints)
            }
        } catch (e: Exception) {
            AppLog.e(TAG, e) { "Failed to parse multisig input: ${e.message}" }
            Result.Error("Failed to parse descriptor: ${e.message}")
        }
    }

    // ==================== Setup File Path ====================

    private fun parseSetupFile(
        input: String,
        localFingerprints: List<String>
    ): Result<ParsedMultisig, String> {
        val parsed = when (val result = ColdCardSetupFile.parse(input)) {
            is Result.Success -> result.value
            is Result.Error -> return Result.Error(result.error)
        }

        val cosigners = parsed.keys.map { key ->
            CosignerInfo(
                xpub = key.xpub,
                fingerprint = key.fingerprint,
                derivationPath = key.derivationPath,
                isLocal = isLocalFingerprint(key.fingerprint, localFingerprints)
            )
        }

        return finalizeConfig(
            threshold = parsed.threshold,
            cosigners = cosigners,
            scriptType = parsed.scriptType,
            rawDescriptor = ColdCardSetupFile.toDescriptor(parsed)
        ).fold(
            onSuccess = { config ->
                Result.Success(
                    ParsedMultisig(
                        config = config,
                        sourceFormat = SourceFormat.SETUP_FILE,
                        suggestedName = parsed.name
                    )
                )
            },
            onError = { Result.Error(it) }
        )
    }

    // ==================== Descriptor / BSMS Path ====================

    private fun parseDescriptorOrBsms(
        input: String,
        localFingerprints: List<String>
    ): Result<ParsedMultisig, String> {
        // Use BSMS module to extract descriptor from BSMS or plain format
        val extracted = BSMS.extractFromInput(input)
        val extractedDescriptor = extracted.descriptor
        if (extracted.isBsmsFormat) {
            AppLog.d(TAG) { "Input was BSMS format" }
        }

        // Remove checksum if present (everything after #)
        val cleanDescriptor = extractedDescriptor.substringBefore("#").trim()

        // Detect script type
        val scriptType = MultisigScriptType.fromDescriptor(cleanDescriptor)
        AppLog.d(TAG) { "Detected script type: $scriptType" }

        // Extract threshold
        val thresholdMatch = THRESHOLD_PATTERN.find(cleanDescriptor)
        if (thresholdMatch == null) {
            AppLog.e(TAG) { "Could not find threshold pattern in descriptor" }
            return Result.Error("Could not find multisig threshold in descriptor")
        }
        val threshold = thresholdMatch.groupValues[1].toIntOrNull()
            ?: return Result.Error("Invalid threshold value")
        AppLog.d(TAG) { "Threshold (m): $threshold" }

        // Extract all keys
        val keyMatches = KEY_PATTERN.findAll(cleanDescriptor).toList()
        AppLog.d(TAG) { "Key matches found: ${keyMatches.size}" }

        if (keyMatches.isEmpty()) {
            AppLog.e(TAG) { "No keys matched in descriptor" }
            return Result.Error("No valid keys found in descriptor. Make sure the descriptor is in standard format.")
        }

        val cosigners = keyMatches.map { match ->
            val fingerprint = match.groupValues[1].lowercase()
            val path = match.groupValues[2].removePrefix("/")
            val xpub = match.groupValues[3]
            CosignerInfo(
                xpub = xpub,
                fingerprint = fingerprint,
                derivationPath = path,
                isLocal = isLocalFingerprint(fingerprint, localFingerprints)
            )
        }

        return finalizeConfig(
            threshold = threshold,
            cosigners = cosigners,
            scriptType = scriptType,
            rawDescriptor = extractedDescriptor // Keep extracted descriptor (handles both BSMS and plain)
        ).fold(
            onSuccess = { config ->
                Result.Success(
                    ParsedMultisig(
                        config = config,
                        sourceFormat = if (extracted.isBsmsFormat) SourceFormat.BSMS else SourceFormat.DESCRIPTOR,
                        verificationAddress = extracted.verificationAddress
                    )
                )
            },
            onError = { Result.Error(it) }
        )
    }

    // ==================== Shared Validation ====================

    /**
     * Common validation and config construction for all content formats.
     */
    private fun finalizeConfig(
        threshold: Int,
        cosigners: List<CosignerInfo>,
        scriptType: MultisigScriptType,
        rawDescriptor: String
    ): Result<MultisigConfig, String> {
        val n = cosigners.size
        AppLog.d(TAG) { "Total cosigners (n): $n" }

        if (threshold > n) {
            return Result.Error("Threshold ($threshold) cannot be greater than number of keys ($n)")
        }
        if (threshold < 1) {
            return Result.Error("Threshold must be at least 1")
        }

        val localKeys = cosigners.filter { it.isLocal }
        if (localKeys.isEmpty()) {
            return Result.Error("None of the cosigner keys match your local wallets. Import the corresponding single-sig wallet first.")
        }
        AppLog.d(TAG) { "Local keys found: ${localKeys.size}" }

        return Result.Success(
            MultisigConfig(
                m = threshold,
                n = n,
                cosigners = cosigners,
                localKeyFingerprints = localKeys.map { it.fingerprint },
                scriptType = scriptType,
                rawDescriptor = rawDescriptor
            )
        )
    }

    private fun isLocalFingerprint(fingerprint: String, localFingerprints: List<String>): Boolean =
        localFingerprints.any { it.equals(fingerprint, ignoreCase = true) }
}
