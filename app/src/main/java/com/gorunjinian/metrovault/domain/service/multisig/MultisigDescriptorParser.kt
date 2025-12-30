package com.gorunjinian.metrovault.domain.service.multisig

import android.util.Log
import com.gorunjinian.metrovault.data.model.CosignerInfo
import com.gorunjinian.metrovault.data.model.MultisigConfig
import com.gorunjinian.metrovault.data.model.MultisigScriptType

/**
 * Parser for multisig output descriptors.
 *
 * Supports both plain Output Descriptor format and BSMS (BIP-0129) format.
 * BSMS parsing is delegated to the centralized BSMS module.
 *
 * @see BSMS
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

    /**
     * Result of parsing a descriptor.
     */
    sealed class ParseResult {
        data class Success(val config: MultisigConfig) : ParseResult()
        data class Error(val message: String) : ParseResult()
    }

    /**
     * Data class for BSMS format parsing result.
     * Contains the extracted descriptor and optional verification address.
     *
     * @see BSMS.ExtractedData
     */
    data class BsmsData(
        val descriptor: String,
        val verificationAddress: String? = null,
        val pathRestrictions: String? = null,
        val isBsmsFormat: Boolean = false
    )

    /**
     * Parse a multisig descriptor string.
     * Supports both plain Output Descriptor format and BSMS format.
     *
     * @param descriptor The descriptor string (with or without checksum)
     * @param localFingerprints List of fingerprints from local wallets to identify which keys we own
     * @return ParseResult with MultisigConfig on success, or error message
     */
    fun parse(descriptor: String, localFingerprints: List<String>): ParseResult {
        try {
            Log.d(TAG, "Parsing descriptor: ${descriptor.take(100)}...")
            Log.d(TAG, "Local fingerprints to match: $localFingerprints")

            // Use BSMS module to extract descriptor from BSMS or plain format
            val bsmsData = extractFromBsmsOrPlain(descriptor)
            val extractedDescriptor = bsmsData.descriptor
            val verificationAddress = bsmsData.verificationAddress

            Log.d(TAG, "Extracted descriptor: ${extractedDescriptor.take(100)}...")
            if (bsmsData.isBsmsFormat) {
                Log.d(TAG, "Input was BSMS format")
            }
            if (verificationAddress != null) {
                Log.d(TAG, "BSMS verification address: $verificationAddress")
            }
            if (bsmsData.pathRestrictions != null) {
                Log.d(TAG, "BSMS path restrictions: ${bsmsData.pathRestrictions}")
            }

            // Remove checksum if present (everything after #)
            val cleanDescriptor = extractedDescriptor.substringBefore("#").trim()
            Log.d(TAG, "Clean descriptor (no checksum): ${cleanDescriptor.take(100)}...")

            // Detect script type
            val scriptType = MultisigScriptType.fromDescriptor(cleanDescriptor)
            Log.d(TAG, "Detected script type: $scriptType")

            // Extract threshold
            val thresholdMatch = THRESHOLD_PATTERN.find(cleanDescriptor)
            if (thresholdMatch == null) {
                Log.e(TAG, "Could not find threshold pattern in: ${cleanDescriptor.take(200)}")
                return ParseResult.Error("Could not find multisig threshold in descriptor")
            }
            val threshold = thresholdMatch.groupValues[1].toIntOrNull()
                ?: return ParseResult.Error("Invalid threshold value")
            Log.d(TAG, "Threshold (m): $threshold")

            // Extract all keys
            val keyMatches = KEY_PATTERN.findAll(cleanDescriptor).toList()
            Log.d(TAG, "Key matches found: ${keyMatches.size}")

            if (keyMatches.isEmpty()) {
                Log.e(TAG, "No keys matched. Descriptor content around keys: ${cleanDescriptor.take(500)}")
                return ParseResult.Error("No valid keys found in descriptor. Make sure the descriptor is in standard format.")
            }

            val cosigners = keyMatches.map { match ->
                val fingerprint = match.groupValues[1].lowercase()
                val path = match.groupValues[2].removePrefix("/")
                val xpub = match.groupValues[3]
                val isLocal = localFingerprints.any { it.equals(fingerprint, ignoreCase = true) }

                Log.d(TAG, "Found key: fingerprint=$fingerprint, path=$path, isLocal=$isLocal")

                CosignerInfo(
                    xpub = xpub,
                    fingerprint = fingerprint,
                    derivationPath = path,
                    isLocal = isLocal
                )
            }

            val n = cosigners.size
            Log.d(TAG, "Total cosigners (n): $n")

            // Validate threshold
            if (threshold > n) {
                return ParseResult.Error("Threshold ($threshold) cannot be greater than number of keys ($n)")
            }
            if (threshold < 1) {
                return ParseResult.Error("Threshold must be at least 1")
            }

            // Check if we have at least one local key
            val localKeys = cosigners.filter { it.isLocal }
            if (localKeys.isEmpty()) {
                return ParseResult.Error("None of the cosigner keys match your local wallets. Import the corresponding single-sig wallet first.")
            }
            Log.d(TAG, "Local keys found: ${localKeys.size}")

            val config = MultisigConfig(
                m = threshold,
                n = n,
                cosigners = cosigners,
                localKeyFingerprints = localKeys.map { it.fingerprint },
                scriptType = scriptType,
                rawDescriptor = extractedDescriptor // Keep extracted descriptor (handles both BSMS and plain)
            )

            return ParseResult.Success(config)

        } catch (e: Exception) {
            Log.e(TAG, "Failed to parse descriptor: ${e.message}", e)
            return ParseResult.Error("Failed to parse descriptor: ${e.message}")
        }
    }

    /**
     * Extract descriptor from BSMS or plain format.
     * Delegates to centralized BSMS module for parsing.
     *
     * @param input The input string (BSMS format or plain descriptor)
     * @return BsmsData with extracted information
     */
    private fun extractFromBsmsOrPlain(input: String): BsmsData {
        // Use centralized BSMS module for extraction
        val extracted = BSMS.extractFromInput(input)

        return BsmsData(
            descriptor = extracted.descriptor,
            verificationAddress = extracted.verificationAddress,
            pathRestrictions = extracted.pathRestrictions,
            isBsmsFormat = extracted.isBsmsFormat
        )
    }
}
